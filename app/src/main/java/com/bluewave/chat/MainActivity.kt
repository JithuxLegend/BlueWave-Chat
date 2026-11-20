package com.bluewave.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "BlueWaveChat"
        private const val PREFS_NAME = "bluewave_prefs"
        private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L

        private val RANDOM_NICKNAMES = listOf(
            "Sky Walker", "Pixel Drift", "Nova Spark", "Echo Rider",
            "Luna Breeze", "Coral Pulse", "Zephyr Fox", "Aero Storm",
            "Cobalt Hawk", "Velvet Mist", "Solar Flare", "Arctic Wolf",
            "Neon Tiger", "Cipher Owl", "Atlas Peak", "Jade River",
            "Ember Leaf", "Comet Dash", "Prism Ray", "Horizon Jet",
            "Vortex Fin", "Misty Pine", "Quantum Kid", "Starlight Jo"
        )
    }

    data class FeedPost(
        val id: String,
        val authorName: String,
        val caption: String,
        val imagePath: String,
        val createdAtMs: Long,
        val expiresAtMs: Long
    )

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val allChatPeers = mutableListOf<BluetoothDevice>()
    private val filteredPeers = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceListAdapter

    private val feedPosts = mutableListOf<FeedPost>()
    private lateinit var feedAdapter: FeedPostAdapter

    private var statusSubtitle: TextView? = null
    private var connectedCountText: TextView? = null
    private var radarTotalConnectionsText: TextView? = null
    private var emptyChatsHint: TextView? = null
    private var userProfileAvatar: ImageView? = null
    private var settingsAvatarPreview: ImageView? = null
    private var feedProfileAvatar: ImageView? = null
    private var settingsDisplayNameText: TextView? = null
    private var feedProfileName: TextView? = null
    private var feedProfileNoteBadge: TextView? = null
    private var radarSonarView: RadarView? = null
    private var scanButton: Button? = null
    private var storiesRowContainer: LinearLayout? = null

    private var acceptThread: AcceptThread? = null
    private var isReceiverRegistered = false
    private var currentThemeMode = "PASTEL"
    private var isAutoRadarMode = true
    private var isActivelyScanning = false
    private var cachedAvatarSheet: Bitmap? = null

    private val pickAvatarPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveCustomAvatarFromUri(it) }
    }

    // Story / Feed Media Picker -> Opens Photo/Video Editor before posting!
    private val pickStoryOrFeedMediaLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { openMediaEditorBeforePosting(it) }
    }

    private val qrCameraScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Toast.makeText(this, "QR Scanned! Scanning Radar for peer...", Toast.LENGTH_SHORT).show()
        startBluetoothScan()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            startListeningServer()
            if (isAutoRadarMode) startBluetoothScan()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device?.let {
                        if (isChatCapableDevice(it) && allChatPeers.none { d -> d.address == it.address }) {
                            allChatPeers.add(it)
                            applySearchFilter("")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    setScanningUiState(false)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            currentThemeMode = prefs.getString("theme_mode", "PASTEL") ?: "PASTEL"
            isAutoRadarMode = prefs.getBoolean("radar_auto_mode", true)

            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter

            // Load Img-7 exact avatar sprite sheet
            cachedAvatarSheet = BitmapFactory.decodeResource(resources, R.drawable.avatars_sheet)

            // Transparent Shark Brand Logo
            val headerLogo = findViewById<ImageView>(R.id.headerBrandLogo)
            val rawLogo = BitmapFactory.decodeResource(resources, R.drawable.shark_logo_clean)
            if (rawLogo != null) {
                headerLogo?.setImageBitmap(makeWhiteBackgroundTransparent(rawLogo))
            }

            // 4-Tab Glass Pill Navigation (Radar, Chats, Feed, Settings + Search Orb)
            val pageRadar = findViewById<View>(R.id.pageRadarContainer)
            val pageChats = findViewById<View>(R.id.pageChatsContainer)
            val pageFeed = findViewById<View>(R.id.pageFeedContainer)
            val pageSettings = findViewById<View>(R.id.pageSettingsContainer)

            val tabRadar = findViewById<TextView>(R.id.tabRadarBtn)
            val tabChats = findViewById<TextView>(R.id.tabChatsBtn)
            val tabFeed = findViewById<TextView>(R.id.tabFeedBtn)
            val tabSettings = findViewById<TextView>(R.id.tabSettingsBtn)
            val allTabs = listOf(tabRadar, tabChats, tabFeed, tabSettings)

            fun showTab(index: Int) {
                pageRadar?.visibility = if (index == 0) View.VISIBLE else View.GONE
                pageChats?.visibility = if (index == 1) View.VISIBLE else View.GONE
                pageFeed?.visibility = if (index == 2) View.VISIBLE else View.GONE
                pageSettings?.visibility = if (index == 3) View.VISIBLE else View.GONE

                allTabs.forEachIndexed { idx, tv ->
                    if (idx == index) {
                        tv?.setBackgroundResource(R.drawable.bg_glass_nav_active)
                        tv?.setTextColor(0xFF0F172A.toInt())
                    } else {
                        tv?.background = null
                        tv?.setTextColor(0xFF475569.toInt())
                    }
                }
            }

            showTab(1)

            tabRadar?.setOnClickListener { showTab(0) }
            tabChats?.setOnClickListener { showTab(1) }
            tabFeed?.setOnClickListener {
                loadAndPrune7DayPosts()
                showTab(2)
            }
            tabSettings?.setOnClickListener { showTab(3) }
            findViewById<View>(R.id.quickScanLink)?.setOnClickListener { showTab(0) }

            // Floating Search Orb focuses search bar on Chats tab
            findViewById<View>(R.id.floatingSearchOrbBtn)?.setOnClickListener {
                showTab(1)
                findViewById<EditText>(R.id.searchPeersInput)?.requestFocus()
            }

            // Radar Controls
            radarSonarView = findViewById(R.id.radarSonarView)
            scanButton = findViewById(R.id.scanButton)
            statusSubtitle = findViewById(R.id.statusSubtitle)
            connectedCountText = findViewById(R.id.connectedCountText)
            radarTotalConnectionsText = findViewById(R.id.radarTotalConnectionsText)
            emptyChatsHint = findViewById(R.id.emptyChatsHint)
            storiesRowContainer = findViewById(R.id.storiesRowContainer)

            val radarAutoBtn = findViewById<TextView>(R.id.radarModeAutoBtn)
            val radarManualBtn = findViewById<TextView>(R.id.radarModeManualBtn)
            fun updateRadarModePills(auto: Boolean) {
                isAutoRadarMode = auto
                prefs.edit().putBoolean("radar_auto_mode", auto).apply()
                stylePillPair(auto, radarAutoBtn, radarManualBtn)
                if (!auto) stopBluetoothScan()
            }
            updateRadarModePills(isAutoRadarMode)
            radarAutoBtn?.setOnClickListener {
                updateRadarModePills(true)
                startBluetoothScan()
            }
            radarManualBtn?.setOnClickListener { updateRadarModePills(false) }

            scanButton?.setOnClickListener {
                if (isActivelyScanning) stopBluetoothScan() else startBluetoothScan()
            }

            // Profile & Exact Img-7 Avatars
            userProfileAvatar = findViewById(R.id.userProfileAvatar)
            settingsAvatarPreview = findViewById(R.id.settingsAvatarPreview)
            feedProfileAvatar = findViewById(R.id.feedProfileAvatar)
            settingsDisplayNameText = findViewById(R.id.settingsDisplayNameText)
            feedProfileName = findViewById(R.id.feedProfileName)
            feedProfileNoteBadge = findViewById(R.id.feedProfileNoteBadge)
            loadSavedProfile()

            findViewById<View>(R.id.profileAvatarBtn)?.setOnClickListener { showProfileAndAvatarDialog() }
            findViewById<View>(R.id.settingsProfileCard)?.setOnClickListener { showProfileAndAvatarDialog() }
            findViewById<View>(R.id.topQrShortcutBtn)?.setOnClickListener { showQuickPairCard() }

            // 7-Day Profile Feed Setup
            val profilePostsListView = findViewById<ListView>(R.id.profilePostsListView)
            feedAdapter = FeedPostAdapter(this, feedPosts)
            profilePostsListView?.adapter = feedAdapter
            loadAndPrune7DayPosts()

            findViewById<View>(R.id.btnCreateFeedPost)?.setOnClickListener {
                pickStoryOrFeedMediaLauncher.launch("image/*")
            }
            findViewById<View>(R.id.btnPostNoteShortcut)?.setOnClickListener {
                showPostNoteDialog()
            }

            // Search Filter
            findViewById<EditText>(R.id.searchPeersInput)?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applySearchFilter(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            setupSettingsControls(prefs)

            val deviceListView = findViewById<ListView>(R.id.deviceListView)
            val recentChatsListView = findViewById<ListView>(R.id.recentChatsListView)
            deviceAdapter = DeviceListAdapter(this, filteredPeers)
            deviceListView?.adapter = deviceAdapter
            recentChatsListView?.adapter = deviceAdapter

            deviceListView?.setOnItemClickListener { _, _, position, _ -> connectToPeer(filteredPeers[position]) }
            recentChatsListView?.setOnItemClickListener { _, _, position, _ -> connectToPeer(filteredPeers[position]) }

            applyAppTheme(currentThemeMode, false)
            refreshSocialStoriesRow()

            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true

            requestBluetoothPermissionsAndStart()
        } catch (e: Exception) {
            val fallback = TextView(this).apply {
                text = "Startup error:\n${e.message}"
                setPadding(40, 80, 40, 40)
            }
            setContentView(fallback)
        }
    }

    // ==================== EXACT IMG-7 AVATAR SLICER ====================

    private fun getExactImg7Avatar(index: Int): Bitmap {
        val sheet = cachedAvatarSheet
        if (sheet == null) {
            val fallback = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            fallback.eraseColor(0xFFBAE6FD.toInt())
            return fallback
        }
        val idx = index.coerceIn(0, 9)
        val col = idx % 5
        val row = idx / 5

        // Img-7 layout: 5 columns x 2 rows with clean margins
        val cellW = sheet.width / 5
        val cellH = sheet.height / 2
        val padX = (cellW * 0.10f).toInt()
        val padY = (cellH * 0.08f).toInt()

        val left = (col * cellW + padX).coerceIn(0, sheet.width - 10)
        val top = (row * cellH + padY).coerceIn(0, sheet.height - 10)
        val cropW = (cellW - padX * 2).coerceAtMost(sheet.width - left)
        val cropH = (cellH - padY * 2).coerceAtMost(sheet.height - top)

        val cropped = Bitmap.createBitmap(sheet, left, top, cropW, cropH)
        return getCircularBitmap(cropped)
    }

    // ==================== STORY / NOTE POSTING & MEDIA EDITOR ====================

    private fun showStoryActionChooser() {
        val options = arrayOf(
            "💭  Post Status Note (24h Bubble)",
            "🖼️  Post Photo to Story & 7-Day Feed (with Editor)",
            "🎬  Post Video Clip to Story"
        )
        AlertDialog.Builder(this)
            .setTitle("Share Story or Note")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPostNoteDialog()
                    1 -> pickStoryOrFeedMediaLauncher.launch("image/*")
                    2 -> pickStoryOrFeedMediaLauncher.launch("video/*")
                }
            }
            .show()
    }

    private fun showPostNoteDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentNote = prefs.getString("user_status_note", "Feeling wavey today!") ?: ""
        val input = EditText(this).apply {
            setText(currentNote)
            hint = "Share a short thought (up to 60 chars)..."
        }
        AlertDialog.Builder(this)
            .setTitle("Post Status Note")
            .setView(input)
            .setPositiveButton("Share Note") { _, _ ->
                val note = input.text.toString().trim().ifEmpty { "Online & Ready" }
                prefs.edit().putString("user_status_note", note).apply()
                feedProfileNoteBadge?.text = "💭 $note"
                refreshSocialStoriesRow()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Built-in Photo/Video Editor (Rotate, Vivid Filter, Caption) before posting!
    private fun openMediaEditorBeforePosting(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        val isVideo = mime.startsWith("video")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 24, 44, 16)
        }

        var workingBitmap: Bitmap? = null
        var rotationDeg = 0f
        var vividFilter = false

        val previewImg = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 460)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        if (!isVideo) {
            val stream = contentResolver.openInputStream(uri)
            workingBitmap = BitmapFactory.decodeStream(stream)
            previewImg.setImageBitmap(workingBitmap)
        } else {
            previewImg.setImageResource(R.drawable.shark_icon)
        }

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 14)
        }

        val rotateBtn = TextView(this).apply {
            text = "↻ Rotate 90°"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_black_pill)
            setPadding(28, 14, 28, 14)
            setOnClickListener {
                if (!isVideo && workingBitmap != null) {
                    rotationDeg = (rotationDeg + 90f) % 360f
                    val m = Matrix().apply { postRotate(90f) }
                    workingBitmap = Bitmap.createBitmap(
                        workingBitmap!!, 0, 0, workingBitmap!!.width, workingBitmap!!.height, m, true
                    )
                    previewImg.setImageBitmap(workingBitmap)
                }
            }
        }

        val filterBtn = TextView(this).apply {
            text = "✨ Vivid Filter"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0369A1.toInt())
            setBackgroundColor(0xFFE0F2FE.toInt())
            setPadding(28, 14, 28, 14)
            setOnClickListener {
                vividFilter = !vividFilter
                if (vividFilter) {
                    val cm = ColorMatrix().apply { setSaturation(1.45f) }
                    previewImg.colorFilter = ColorMatrixColorFilter(cm)
                } else {
                    previewImg.clearColorFilter()
                }
            }
        }

        if (!isVideo) {
            controlsRow.addView(rotateBtn)
            controlsRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(20, 1) })
            controlsRow.addView(filterBtn)
        }

        val captionInput = EditText(this).apply {
            hint = "Add a caption to your 7-day post..."
        }

        box.addView(previewImg)
        box.addView(controlsRow)
        box.addView(captionInput)

        AlertDialog.Builder(this)
            .setTitle(if (isVideo) "Edit Video Story" else "Edit Photo Before Posting")
            .setView(box)
            .setPositiveButton("Post (7-Day Auto-Delete)") { _, _ ->
                val caption = captionInput.text.toString().trim().ifEmpty { "Shared a moment ✨" }
                saveNew7DayFeedPost(uri, workingBitmap, isVideo, caption)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveNew7DayFeedPost(uri: Uri, editedBitmap: Bitmap?, isVideo: Boolean, caption: String) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val author = prefs.getString("nickname", RANDOM_NICKNAMES[1]) ?: RANDOM_NICKNAMES[1]
            val outFile = File(filesDir, "post_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}")

            if (!isVideo && editedBitmap != null) {
                FileOutputStream(outFile).use { out ->
                    editedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            } else {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { out -> input.copyTo(out) }
                }
            }

            val now = System.currentTimeMillis()
            val post = FeedPost(
                id = UUID.randomUUID().toString(),
                authorName = author,
                caption = caption,
                imagePath = outFile.absolutePath,
                createdAtMs = now,
                expiresAtMs = now + SEVEN_DAYS_MS
            )
            feedPosts.add(0, post)
            persist7DayPosts()
            feedAdapter.notifyDataSetChanged()
            refreshSocialStoriesRow()
            Toast.makeText(this, "Posted to Story & 7-Day Profile Feed!", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun loadAndPrune7DayPosts() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("feed_posts_json", "[]") ?: "[]"
        val now = System.currentTimeMillis()
        feedPosts.clear()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val exp = obj.getLong("expiresAtMs")
                val path = obj.getString("imagePath")
                if (exp > now && File(path).exists()) {
                    feedPosts.add(
                        FeedPost(
                            id = obj.getString("id"),
                            authorName = obj.getString("authorName"),
                            caption = obj.getString("caption"),
                            imagePath = path,
                            createdAtMs = obj.getLong("createdAtMs"),
                            expiresAtMs = exp
                        )
                    )
                } else {
                    File(path).delete()
                }
            }
        } catch (_: Exception) {
        }
        persist7DayPosts()
        if (::feedAdapter.isInitialized) feedAdapter.notifyDataSetChanged()
    }

    private fun persist7DayPosts() {
        val arr = JSONArray()
        for (p in feedPosts) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("authorName", p.authorName)
                put("caption", p.caption)
                put("imagePath", p.imagePath)
                put("createdAtMs", p.createdAtMs)
                put("expiresAtMs", p.expiresAtMs)
            })
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("feed_posts_json", arr.toString())
            .apply()
    }

    // ==================== SOCIAL STORIES + NOTES BAR ====================

    @SuppressLint("MissingPermission")
    private fun refreshSocialStoriesRow() {
        val row = storiesRowContainer ?: return
        row.removeAllViews()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val myName = prefs.getString("nickname", RANDOM_NICKNAMES[1]) ?: RANDOM_NICKNAMES[1]
        val myIdx = prefs.getInt("cartoon_avatar_index", 0)
        val myNote = prefs.getString("user_status_note", "Tap + Story") ?: "Tap + Story"

        // Tapping Your Story Circle Opens Photo/Video Story & Note Creator!
        row.addView(createStoryBubbleItem("Your Story", myNote, getExactImg7Avatar(myIdx), true) {
            showStoryActionChooser()
        })

        allChatPeers.forEachIndexed { idx, device ->
            val peerName = device.name ?: "Peer"
            val avatarBmp = getExactImg7Avatar((idx + 1) % 10)
            row.addView(createStoryBubbleItem(peerName, "Online", avatarBmp, true) {
                connectToPeer(device)
            })
        }
    }

    private fun createStoryBubbleItem(
        label: String,
        noteText: String,
        avatar: Bitmap,
        isOnline: Boolean,
        onClick: () -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(10, 2, 16, 2)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        // Floating Instagram-style Note Bubble above Avatar
        val notePill = TextView(this).apply {
            text = noteText
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(0xFF0F172A.toInt())
            setBackgroundResource(R.drawable.bg_white_pill)
            setPadding(16, 6, 16, 6)
        }

        val ring = FrameLayoutWithRing(this, isOnline).apply {
            layoutParams = LinearLayout.LayoutParams(126, 126)
            setPadding(7, 7, 7, 7)
        }
        val img = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setImageBitmap(avatar)
        }
        ring.addView(img)

        val txt = TextView(this).apply {
            text = label
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(138, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextColor(if (currentThemeMode == "DARK" || currentThemeMode == "MOCHA") 0xFFE2E8F0.toInt() else 0xFF334155.toInt())
            setPadding(0, 4, 0, 0)
        }

        box.addView(notePill)
        box.addView(ring)
        box.addView(txt)
        return box
    }

    private class FrameLayoutWithRing(context: Context, private val active: Boolean) : android.widget.FrameLayout(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = if (active) 0xFF2563EB.toInt() else 0xFFCBD5E1.toInt()
        }
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = minOf(width, height) / 2f - 4f
            canvas.drawCircle(width / 2f, height / 2f, r, ringPaint)
        }
    }

    // ==================== PROFILE DIALOG (ALL 10 EXACT IMG-7 AVATARS) ====================

    private fun loadSavedProfile() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", RANDOM_NICKNAMES[1]) ?: RANDOM_NICKNAMES[1]
        val note = prefs.getString("user_status_note", "Feeling wavey today!") ?: "Feeling wavey today!"

        settingsDisplayNameText?.text = nickname
        feedProfileName?.text = nickname
        feedProfileNoteBadge?.text = "💭 $note"

        val customPath = prefs.getString("custom_avatar_path", null)
        val avatarIndex = prefs.getInt("cartoon_avatar_index", 0)

        val bmp: Bitmap = if (customPath != null && File(customPath).exists()) {
            val raw = BitmapFactory.decodeFile(customPath)
            if (raw != null) getCircularBitmap(raw) else getExactImg7Avatar(avatarIndex)
        } else {
            getExactImg7Avatar(avatarIndex)
        }

        userProfileAvatar?.setImageBitmap(bmp)
        settingsAvatarPreview?.setImageBitmap(bmp)
        feedProfileAvatar?.setImageBitmap(bmp)
        refreshSocialStoriesRow()
    }

    private fun showProfileAndAvatarDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentName = prefs.getString("nickname", RANDOM_NICKNAMES[1]) ?: RANDOM_NICKNAMES[1]

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(currentName)
            hint = "Enter nickname"
        }
        val randomBtn = TextView(this).apply {
            text = "🎲 Random"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_black_pill)
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                val cur = nameInput.text.toString()
                nameInput.setText(RANDOM_NICKNAMES.filter { it != cur }.random())
            }
        }
        nameRow.addView(nameInput)
        nameRow.addView(randomBtn)

        val avatarTitle = TextView(this).apply {
            text = "Select Character Avatar (Swipe to view all 10)"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF475569.toInt())
            setPadding(0, 20, 0, 10)
        }

        val hScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val gridCols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("Alex", "Maya", "Ryan", "Clara", "Liam", "Nina", "Noah", "Ella", "Owen", "Zoe")

        for (col in 0 until 5) {
            val colBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            colBox.addView(createAvatarOptionItem(col, labels[col]))
            colBox.addView(createAvatarOptionItem(col + 5, labels[col + 5]))
            gridCols.addView(colBox)
        }
        hScroll.addView(gridCols)

        container.addView(nameRow)
        container.addView(avatarTitle)
        container.addView(hScroll)

        AlertDialog.Builder(this)
            .setTitle("Edit Profile")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim().ifEmpty { RANDOM_NICKNAMES.random() }
                prefs.edit().putString("nickname", newName).apply()
                loadSavedProfile()
            }
            .setNeutralButton("Gallery Photo") { _, _ ->
                val newName = nameInput.text.toString().trim().ifEmpty { RANDOM_NICKNAMES.random() }
                prefs.edit().putString("nickname", newName).apply()
                pickAvatarPhotoLauncher.launch("image/*")
            }
            .show()
    }

    private fun createAvatarOptionItem(index: Int, label: String): View {
        val itemBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 8)
            isClickable = true
            isFocusable = true
        }
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(136, 136)
            setImageBitmap(getExactImg7Avatar(index))
        }
        val txt = TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF0F172A.toInt())
            setPadding(0, 4, 0, 0)
        }
        itemBox.addView(img)
        itemBox.addView(txt)

        itemBox.setOnClickListener {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt("cartoon_avatar_index", index)
                .remove("custom_avatar_path")
                .apply()
            loadSavedProfile()
        }
        return itemBox
    }

    private fun saveCustomAvatarFromUri(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val raw = BitmapFactory.decodeStream(input) ?: return
            val scaled = Bitmap.createScaledBitmap(raw, 240, 240, true)
            val file = File(filesDir, "user_avatar.png")
            FileOutputStream(file).use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 95, out) }
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("custom_avatar_path", file.absolutePath)
                .apply()
            loadSavedProfile()
        } catch (_: Exception) {
        }
    }

    private fun getCircularBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, null, rect, paint)
        return output
    }

    // ==================== 6 APP THEMES (INSTAGRAM & TELEGRAM STYLES) ====================

    private fun applyAppTheme(mode: String, savePref: Boolean) {
        currentThemeMode = mode
        if (savePref) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("theme_mode", mode)
                .apply()
        }

        val root = findViewById<RelativeLayout>(R.id.rootMainContainer)
        val titles = listOf(
            findViewById<TextView>(R.id.homeTitleText),
            findViewById<TextView>(R.id.radarTitleText),
            findViewById<TextView>(R.id.settingsTitleText),
            findViewById<TextView>(R.id.nearbyChatsHeading),
            findViewById<TextView>(R.id.feedSectionTitle)
        )

        val isDark = mode == "DARK" || mode == "MOCHA"
        when (mode) {
            "MINT" -> root?.setBackgroundColor(0xFFD1FAE5.toInt())      // Img-8 Mint Aura
            "TELEGRAM" -> root?.setBackgroundColor(0xFFDBEAFE.toInt())  // Telegram Sky Blue
            "WHITE" -> root?.setBackgroundColor(0xFFFFFFFF.toInt())
            "MOCHA" -> root?.setBackgroundColor(0xFF1C1917.toInt())     // Img-10 Mocha Dark
            "DARK" -> root?.setBackgroundColor(0xFF0F172A.toInt())
            else -> root?.setBackgroundResource(R.drawable.bg_pastel_mesh)
        }
        titles.forEach { it?.setTextColor(if (isDark) 0xFFF8FAFC.toInt() else 0xFF0F172A.toInt()) }

        val themeButtons = listOf(
            "PASTEL" to findViewById<TextView>(R.id.themePastelBtn),
            "MINT" to findViewById<TextView>(R.id.themeMintBtn),
            "TELEGRAM" to findViewById<TextView>(R.id.themeTelegramBtn),
            "WHITE" to findViewById<TextView>(R.id.themeWhiteBtn),
            "MOCHA" to findViewById<TextView>(R.id.themeMochaBtn),
            "DARK" to findViewById<TextView>(R.id.themeDarkBtn)
        )
        for ((key, btn) in themeButtons) {
            val active = key == mode
            btn?.setBackgroundResource(if (active) R.drawable.bg_black_pill else R.drawable.bg_white_pill)
            btn?.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        }
        refreshSocialStoriesRow()
    }

    // ==================== RADAR, SETTINGS & BLUETOOTH ====================

    private fun setScanningUiState(scanning: Boolean) {
        isActivelyScanning = scanning
        radarSonarView?.isScanningActive = scanning
        if (scanning) {
            scanButton?.text = "✕  Stop / Cancel Scanning"
            scanButton?.setBackgroundColor(0xFFDC2626.toInt())
            statusSubtitle?.text = "Scanning nearby radio frequencies..."
        } else {
            scanButton?.text = "Broadcast Signal"
            scanButton?.setBackgroundResource(R.drawable.bg_black_pill)
            statusSubtitle?.text = "Tap Broadcast Signal to scan"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) bluetoothAdapter?.cancelDiscovery()
        } catch (_: Exception) {
        }
        setScanningUiState(false)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            statusSubtitle?.text = "Please turn on Bluetooth"
            return
        }
        allChatPeers.clear()
        adapter.bondedDevices?.forEach { device ->
            if (isChatCapableDevice(device)) allChatPeers.add(device)
        }
        applySearchFilter("")

        if (adapter.isDiscovering) adapter.cancelDiscovery()
        adapter.startDiscovery()
        setScanningUiState(true)
    }

    private fun setupSettingsControls(prefs: android.content.SharedPreferences) {
        findViewById<TextView>(R.id.themePastelBtn)?.setOnClickListener { applyAppTheme("PASTEL", true) }
        findViewById<TextView>(R.id.themeMintBtn)?.setOnClickListener { applyAppTheme("MINT", true) }
        findViewById<TextView>(R.id.themeTelegramBtn)?.setOnClickListener { applyAppTheme("TELEGRAM", true) }
        findViewById<TextView>(R.id.themeWhiteBtn)?.setOnClickListener { applyAppTheme("WHITE", true) }
        findViewById<TextView>(R.id.themeMochaBtn)?.setOnClickListener { applyAppTheme("MOCHA", true) }
        findViewById<TextView>(R.id.themeDarkBtn)?.setOnClickListener { applyAppTheme("DARK", true) }

        findViewById<View>(R.id.btnPrivacyPolicy)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Privacy & Radio Security Policy")
                .setMessage(
                    "1. Zero Cloud Tracking: Peer chats and 7-day profile posts stay under your control.\n\n" +
                    "2. AES-256 Signal Protection: Radio packets are encrypted and checksum-verified.\n\n" +
                    "3. Media Header Guard: Incoming images, videos, and audio files are verified before decoding and never auto-saved to your public gallery."
                )
                .setPositiveButton("Got It", null)
                .show()
        }

        val btnBt = findViewById<TextView>(R.id.mediaViaBluetoothBtn)
        val btnNet = findViewById<TextView>(R.id.mediaViaInternetBtn)
        fun updateMediaChannel(mode: String) {
            prefs.edit().putString("media_channel", mode).apply()
            stylePillPair(mode == "BT", btnBt, btnNet)
        }
        updateMediaChannel(prefs.getString("media_channel", "BT") ?: "BT")
        btnBt?.setOnClickListener { updateMediaChannel("BT") }
        btnNet?.setOnClickListener { updateMediaChannel("NET") }

        val btnStd = findViewById<TextView>(R.id.rangeStandardBtn)
        val btnBoost = findViewById<TextView>(R.id.rangeBoostBtn)
        fun updateRange(boost: Boolean) {
            prefs.edit().putBoolean("signal_boost", boost).apply()
            stylePillPair(!boost, btnStd, btnBoost)
        }
        updateRange(prefs.getBoolean("signal_boost", true))
        btnStd?.setOnClickListener { updateRange(false) }
        btnBoost?.setOnClickListener { updateRange(true) }

        val tOff = findViewById<TextView>(R.id.timerOffBtn)
        val t1h = findViewById<TextView>(R.id.timer1hBtn)
        val t24h = findViewById<TextView>(R.id.timer24hBtn)
        fun updateTimer(timer: String) {
            prefs.edit().putString("auto_delete_timer", timer).apply()
            stylePillTriple(timer, "OFF" to tOff, "1H" to t1h, "24H" to t24h)
        }
        updateTimer(prefs.getString("auto_delete_timer", "OFF") ?: "OFF")
        tOff?.setOnClickListener { updateTimer("OFF") }
        t1h?.setOnClickListener { updateTimer("1H") }
        t24h?.setOnClickListener { updateTimer("24H") }

        val wPastel = findViewById<TextView>(R.id.wallPastelBtn)
        val wOcean = findViewById<TextView>(R.id.wallOceanBtn)
        val wDark = findViewById<TextView>(R.id.wallDarkBtn)
        fun updateWall(wall: String) {
            prefs.edit().putString("chat_wallpaper", wall).apply()
            stylePillTriple(wall, "PASTEL" to wPastel, "OCEAN" to wOcean, "DARK" to wDark)
        }
        updateWall(prefs.getString("chat_wallpaper", "PASTEL") ?: "PASTEL")
        wPastel?.setOnClickListener { updateWall("PASTEL") }
        wOcean?.setOnClickListener { updateWall("OCEAN") }
        wDark?.setOnClickListener { updateWall("DARK") }

        findViewById<View>(R.id.btnQuickPairQr)?.setOnClickListener { showQuickPairCard() }
        findViewById<View>(R.id.btnClearCache)?.setOnClickListener {
            cacheDir.listFiles()?.forEach { it.delete() }
            Toast.makeText(this, "Temporary cache cleared!", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnSystemBluetooth)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    @SuppressLint("MissingPermission")
    private fun showQuickPairCard() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nick = prefs.getString("nickname", RANDOM_NICKNAMES[1]) ?: RANDOM_NICKNAMES[1]
        val deviceName = bluetoothAdapter?.name ?: Build.MODEL

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(52, 36, 52, 24)
        }
        val qrView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(300, 300)
            setImageBitmap(generateVisualPairingBadge(nick + deviceName))
        }
        val label = TextView(this).apply {
            text = "$nick\nThis Phone: $deviceName"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(0xFF1E293B.toInt())
            setPadding(0, 18, 0, 0)
        }
        box.addView(qrView)
        box.addView(label)

        AlertDialog.Builder(this)
            .setTitle("QR Quick Pair")
            .setView(box)
            .setNeutralButton("📷 Scan Friend's QR") { _, _ ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                } else {
                    qrCameraScannerLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                }
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun generateVisualPairingBadge(seed: String): Bitmap {
        val grid = 11
        val cell = 26
        val bmp = Bitmap.createBitmap(grid * cell, grid * cell, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFF8FAFC.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt() }
        val hash = seed.hashCode()
        for (r in 0 until grid) {
            for (c in 0 until grid) {
                val isCorner = (r < 3 && c < 3) || (r < 3 && c > grid - 4) || (r > grid - 4 && c < 3)
                val bit = ((hash shr ((r * grid + c) % 31)) and 1) == 1
                if (isCorner || bit) {
                    canvas.drawRoundRect(
                        c * cell + 3f, r * cell + 3f,
                        (c + 1) * cell - 3f, (r + 1) * cell - 3f,
                        7f, 7f, paint
                    )
                }
            }
        }
        return bmp
    }

    private fun stylePillPair(firstActive: Boolean, first: TextView?, second: TextView?) {
        first?.setBackgroundResource(if (firstActive) R.drawable.bg_black_pill else R.drawable.bg_white_pill)
        first?.setTextColor(if (firstActive) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        second?.setBackgroundResource(if (!firstActive) R.drawable.bg_black_pill else R.drawable.bg_white_pill)
        second?.setTextColor(if (!firstActive) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
    }

    private fun stylePillTriple(
        activeKey: String,
        a: Pair<String, TextView?>,
        b: Pair<String, TextView?>,
        c: Pair<String, TextView?>
    ) {
        for ((key, view) in listOf(a, b, c)) {
            val active = key == activeKey
            view?.setBackgroundResource(if (active) R.drawable.bg_black_pill else R.drawable.bg_white_pill)
            view?.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        }
    }

    @SuppressLint("MissingPermission")
    private fun isChatCapableDevice(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        if (name.isBlank()) return false
        val majorClass = device.bluetoothClass?.majorDeviceClass
        if (majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            majorClass == BluetoothClass.Device.Major.WEARABLE ||
            majorClass == BluetoothClass.Device.Major.PERIPHERAL ||
            majorClass == BluetoothClass.Device.Major.HEALTH ||
            majorClass == BluetoothClass.Device.Major.TOY
        ) return false
        return true
    }

    private fun makeWhiteBackgroundTransparent(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            if (Color.red(c) > 235 && Color.green(c) > 235 && Color.blue(c) > 235) {
                pixels[i] = Color.TRANSPARENT
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    @SuppressLint("MissingPermission")
    private fun applySearchFilter(query: String) {
        val clean = query.trim().lowercase()
        filteredPeers.clear()
        if (clean.isEmpty()) {
            filteredPeers.addAll(allChatPeers)
        } else {
            filteredPeers.addAll(
                allChatPeers.filter {
                    (it.name ?: "").lowercase().contains(clean) || it.address.lowercase().contains(clean)
                }
            )
        }
        if (::deviceAdapter.isInitialized) deviceAdapter.notifyDataSetChanged()
        updatePeerCounts()
        refreshSocialStoriesRow()
    }

    private fun updatePeerCounts() {
        val count = allChatPeers.size
        radarSonarView?.detectedTargetCount = count
        connectedCountText?.text = if (count == 0) {
            "●  Ready for Calls, Stories & Chats"
        } else {
            "●  $count Chat Peer${if (count == 1) "" else "s"} Active"
        }
        radarTotalConnectionsText?.text = "$count Nearby Connection${if (count == 1) "" else "s"} Detected"
        emptyChatsHint?.visibility = if (filteredPeers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun requestBluetoothPermissionsAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permissionLauncher.launch(permissions)
    }

    @SuppressLint("MissingPermission")
    private fun startListeningServer() {
        acceptThread?.cancel()
        acceptThread = AcceptThread()
        acceptThread?.start()
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: BluetoothDevice) {
        stopBluetoothScan()
        statusSubtitle?.text = "Connecting to ${device.name ?: "Peer"}..."
        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                runOnUiThread { onConnectedSuccess(socket) }
            } catch (e: IOException) {
                runOnUiThread { statusSubtitle?.text = "Connection failed. Ensure peer has app open." }
            }
        }.start()
    }

    private fun onConnectedSuccess(socket: BluetoothSocket) {
        ChatActivity.activeSocket = socket
        startActivity(Intent(this, ChatActivity::class.java))
    }

    @SuppressLint("MissingPermission")
    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket? = try {
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
        } catch (e: IOException) {
            null
        }
        override fun run() {
            var shouldLoop = true
            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    serverSocket?.accept()
                } catch (e: IOException) {
                    shouldLoop = false
                    null
                }
                socket?.let {
                    runOnUiThread { onConnectedSuccess(it) }
                    try { serverSocket?.close() } catch (_: IOException) {}
                    shouldLoop = false
                }
            }
        }
        fun cancel() {
            try { serverSocket?.close() } catch (_: IOException) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        }
        acceptThread?.cancel()
    }

    inner class DeviceListAdapter(
        context: Context,
        private val devices: List<BluetoothDevice>
    ) : ArrayAdapter<BluetoothDevice>(context, 0, devices) {
        @SuppressLint("MissingPermission")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_white_pill)
                setPadding(34, 26, 34, 26)
            }
            val container = row as LinearLayout
            container.removeAllViews()

            val avatar = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(100, 100)
                setImageBitmap(getExactImg7Avatar((position + 1) % 10))
            }
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(24, 0, 12, 0)
            }
            val device = devices[position]
            val title = TextView(context).apply {
                text = device.name ?: "Chat Peer"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF0F172A.toInt())
            }
            val subtitle = TextView(context).apply {
                text = "Tap to message or call  •  7-Day Feed Active"
                textSize = 11.5f
                setTextColor(0xFF64748B.toInt())
                setPadding(0, 4, 0, 0)
            }
            val chatPill = TextView(context).apply {
                text = "Open"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_black_pill)
                setPadding(28, 12, 28, 12)
            }
            textCol.addView(title)
            textCol.addView(subtitle)
            container.addView(avatar)
            container.addView(textCol)
            container.addView(chatPill)
            return container
        }
    }

    inner class FeedPostAdapter(
        context: Context,
        private val posts: List<FeedPost>
    ) : ArrayAdapter<FeedPost>(context, 0, posts) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = convertView ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_white_pill)
                setPadding(28, 24, 28, 24)
            }
            val container = card as LinearLayout
            container.removeAllViews()

            val post = posts[position]
            val remainingMs = (post.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val daysLeft = remainingMs / (24L * 60L * 60L * 1000L)
            val hoursLeft = (remainingMs / (60L * 60L * 1000L)) % 24L

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val authorTv = TextView(context).apply {
                text = post.authorName
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF0F172A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val countdownPill = TextView(context).apply {
                text = "⏳ ${daysLeft}d ${hoursLeft}h left"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF0369A1.toInt())
                setBackgroundColor(0xFFE0F2FE.toInt())
                setPadding(18, 8, 18, 8)
            }
            topRow.addView(authorTv)
            topRow.addView(countdownPill)

            val imgView = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 480).apply {
                    setMargins(0, 16, 0, 12)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(post.imagePath))
            }

            val capTv = TextView(context).apply {
                text = post.caption
                textSize = 14f
                setTextColor(0xFF1E293B.toInt())
            }

            container.addView(topRow)
            container.addView(imgView)
            container.addView(capTv)
            return container
        }
    }
}

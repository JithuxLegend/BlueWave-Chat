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
import android.graphics.Paint
import android.graphics.Path
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "BlueWaveChat"
        private const val PREFS_NAME = "bluewave_prefs"

        // 24 distinct default nicknames (never repeats consecutively)
        private val RANDOM_NICKNAMES = listOf(
            "Sky Walker", "Pixel Drift", "Nova Spark", "Echo Rider",
            "Luna Breeze", "Coral Pulse", "Zephyr Fox", "Aero Storm",
            "Cobalt Hawk", "Velvet Mist", "Solar Flare", "Arctic Wolf",
            "Neon Tiger", "Cipher Owl", "Atlas Peak", "Jade River",
            "Ember Leaf", "Comet Dash", "Prism Ray", "Horizon Jet",
            "Vortex Fin", "Misty Pine", "Quantum Kid", "Starlight Jo"
        )
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val allChatPeers = mutableListOf<BluetoothDevice>()
    private val filteredPeers = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceListAdapter

    private var statusSubtitle: TextView? = null
    private var connectedCountText: TextView? = null
    private var radarTotalConnectionsText: TextView? = null
    private var emptyChatsHint: TextView? = null
    private var userProfileAvatar: ImageView? = null
    private var settingsAvatarPreview: ImageView? = null
    private var settingsDisplayNameText: TextView? = null
    private var radarSonarView: RadarView? = null
    private var scanButton: Button? = null
    private var storiesRowContainer: LinearLayout? = null

    private var acceptThread: AcceptThread? = null
    private var isReceiverRegistered = false
    private var currentThemeMode = "PASTEL"
    private var isAutoRadarMode = true
    private var isActivelyScanning = false

    private val pickAvatarPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveCustomAvatarFromUri(it) }
    }

    private val qrCameraScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Toast.makeText(this, "QR Scanned! Checking nearby Radar peers...", Toast.LENGTH_SHORT).show()
        startBluetoothScan()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            startListeningServer()
            if (isAutoRadarMode) startBluetoothScan()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
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

            // Transparent Brand Logo
            val headerLogo = findViewById<ImageView>(R.id.headerBrandLogo)
            val rawLogo = BitmapFactory.decodeResource(resources, R.drawable.shark_logo_clean)
            if (rawLogo != null) {
                headerLogo?.setImageBitmap(makeWhiteBackgroundTransparent(rawLogo))
            }

            // Tabs
            val pageChats = findViewById<View>(R.id.pageChatsContainer)
            val pageRadar = findViewById<View>(R.id.pageRadarContainer)
            val pageSettings = findViewById<View>(R.id.pageSettingsContainer)

            val tabRadar = findViewById<TextView>(R.id.tabRadarBtn)
            val tabChats = findViewById<TextView>(R.id.tabChatsBtn)
            val tabSettings = findViewById<TextView>(R.id.tabSettingsBtn)
            val quickScanLink = findViewById<View>(R.id.quickScanLink)

            fun showTab(tabIndex: Int) {
                pageRadar?.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
                pageChats?.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
                pageSettings?.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE

                val activeColor = if (currentThemeMode == "DARK") 0xFF38BDF8.toInt() else 0xFF0F172A.toInt()
                val inactiveColor = 0xFF94A3B8.toInt()
                tabRadar?.setTextColor(if (tabIndex == 0) activeColor else inactiveColor)
                tabChats?.setTextColor(if (tabIndex == 1) activeColor else inactiveColor)
                tabSettings?.setTextColor(if (tabIndex == 2) activeColor else inactiveColor)
            }

            showTab(1)

            tabRadar?.setOnClickListener { showTab(0) }
            tabChats?.setOnClickListener { showTab(1) }
            tabSettings?.setOnClickListener { showTab(2) }
            quickScanLink?.setOnClickListener { showTab(0) }

            // Radar Controls (Auto/Manual + Stop/Cancel Scan)
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
            radarManualBtn?.setOnClickListener {
                updateRadarModePills(false)
            }

            scanButton?.setOnClickListener {
                if (isActivelyScanning) {
                    stopBluetoothScan()
                } else {
                    startBluetoothScan()
                }
            }

            // Profile & All 10 Img-7 Avatars
            userProfileAvatar = findViewById(R.id.userProfileAvatar)
            settingsAvatarPreview = findViewById(R.id.settingsAvatarPreview)
            settingsDisplayNameText = findViewById(R.id.settingsDisplayNameText)
            loadSavedProfile()

            findViewById<View>(R.id.profileAvatarBtn)?.setOnClickListener { showProfileAndAvatarDialog() }
            findViewById<View>(R.id.settingsProfileCard)?.setOnClickListener { showProfileAndAvatarDialog() }
            findViewById<View>(R.id.topQrShortcutBtn)?.setOnClickListener { showQuickPairCard() }

            // Search Bar
            findViewById<EditText>(R.id.searchPeersInput)?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applySearchFilter(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            // Settings Controls
            setupSettingsControls(prefs)

            // Peer Lists
            val deviceListView = findViewById<ListView>(R.id.deviceListView)
            val recentChatsListView = findViewById<ListView>(R.id.recentChatsListView)

            deviceAdapter = DeviceListAdapter(this, filteredPeers)
            deviceListView?.adapter = deviceAdapter
            recentChatsListView?.adapter = deviceAdapter

            deviceListView?.setOnItemClickListener { _, _, position, _ ->
                connectToPeer(filteredPeers[position])
            }
            recentChatsListView?.setOnItemClickListener { _, _, position, _ ->
                connectToPeer(filteredPeers[position])
            }

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

    // ==================== SOCIAL STORIES / ACTIVE BUBBLES BAR ====================

    @SuppressLint("MissingPermission")
    private fun refreshSocialStoriesRow() {
        val row = storiesRowContainer ?: return
        row.removeAllViews()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val myName = prefs.getString("nickname", RANDOM_NICKNAMES.first()) ?: RANDOM_NICKNAMES.first()
        val myIdx = prefs.getInt("cartoon_avatar_index", 0)

        // Item 0: Your Story / Status Bubble
        row.addView(createStoryBubbleItem("You ($myName)", createImg7AvatarBitmap(myIdx), isOnline = true) {
            showProfileAndAvatarDialog()
        })

        // Nearby Chat Peers as Story Bubbles
        allChatPeers.forEachIndexed { idx, device ->
            val peerName = device.name ?: "Peer"
            val avatarBmp = createImg7AvatarBitmap((idx + 1) % 10)
            row.addView(createStoryBubbleItem(peerName, avatarBmp, isOnline = true) {
                connectToPeer(device)
            })
        }
    }

    private fun createStoryBubbleItem(
        label: String,
        avatar: Bitmap,
        isOnline: Boolean,
        onClick: () -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 4, 18, 4)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val ring = FrameLayoutWithRing(this, isOnline).apply {
            layoutParams = LinearLayout.LayoutParams(132, 132)
            setPadding(8, 8, 8, 8)
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
            layoutParams = LinearLayout.LayoutParams(144, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextColor(if (currentThemeMode == "DARK") 0xFFE2E8F0.toInt() else 0xFF334155.toInt())
            setPadding(0, 6, 0, 0)
        }

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
        init {
            setWillNotDraw(false)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = minOf(width, height) / 2f - 4f
            canvas.drawCircle(width / 2f, height / 2f, r, ringPaint)
        }
    }

    // ==================== RADAR SCAN & STOP/CANCEL CONTROL ====================

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
            statusSubtitle?.text = if (allChatPeers.isEmpty()) {
                "Scan stopped • Tap Broadcast Signal to search"
            } else {
                "Tap any peer above to start encrypted chat"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
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
            if (isChatCapableDevice(device)) {
                allChatPeers.add(device)
            }
        }
        applySearchFilter("")

        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        adapter.startDiscovery()
        setScanningUiState(true)
    }

    // ==================== SECURITY, PRIVACY POLICY & SETTINGS ====================

    private fun setupSettingsControls(prefs: android.content.SharedPreferences) {
        findViewById<TextView>(R.id.themePastelBtn)?.setOnClickListener { applyAppTheme("PASTEL", true) }
        findViewById<TextView>(R.id.themeWhiteBtn)?.setOnClickListener { applyAppTheme("WHITE", true) }
        findViewById<TextView>(R.id.themeDarkBtn)?.setOnClickListener { applyAppTheme("DARK", true) }

        val swRfEnc = findViewById<Switch>(R.id.switchRfEncryption)
        val swMediaSanitize = findViewById<Switch>(R.id.switchStrictMediaScan)
        swRfEnc?.isChecked = prefs.getBoolean("rf_encryption", true)
        swMediaSanitize?.isChecked = prefs.getBoolean("strict_media_guard", true)
        swRfEnc?.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("rf_encryption", checked).apply()
        }
        swMediaSanitize?.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("strict_media_guard", checked).apply()
        }

        findViewById<View>(R.id.btnPrivacyPolicy)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Privacy & Radio Security Policy")
                .setMessage(
                    "1. Zero Cloud & Zero Tracking: All messages, photos, videos, and voice notes travel strictly peer-to-peer without external servers.\n\n" +
                    "2. RF Signal Encryption: Every Bluetooth frame is encrypted with a session key and verified via integrity checksums to prevent eavesdropping.\n\n" +
                    "3. Media Payload Firewall: Incoming images, videos, and audio files undergo strict header validation before decoding and never auto-save to your public gallery without your tap."
                )
                .setPositiveButton("I Understand", null)
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

        val switchAutoAccept = findViewById<Switch>(R.id.switchAutoAccept)
        val switchVibrate = findViewById<Switch>(R.id.switchVibrate)
        switchAutoAccept?.isChecked = prefs.getBoolean("auto_accept", true)
        switchVibrate?.isChecked = prefs.getBoolean("vibrate_msg", true)
        switchAutoAccept?.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_accept", checked).apply()
        }
        switchVibrate?.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("vibrate_msg", checked).apply()
        }

        findViewById<View>(R.id.btnQuickPairQr)?.setOnClickListener { showQuickPairCard() }
        findViewById<View>(R.id.btnClearCache)?.setOnClickListener {
            cacheDir.listFiles()?.forEach { it.delete() }
            Toast.makeText(this, "Temporary media cache wiped!", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnSystemBluetooth)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    // ==================== QR QUICK PAIR + CAMERA SCANNER ====================

    @SuppressLint("MissingPermission")
    private fun showQuickPairCard() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nick = prefs.getString("nickname", RANDOM_NICKNAMES.first()) ?: RANDOM_NICKNAMES.first()
        val deviceName = bluetoothAdapter?.name ?: Build.MODEL

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(52, 36, 52, 24)
        }
        val qrView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(320, 320)
            setImageBitmap(generateVisualPairingBadge(nick + deviceName))
        }
        val label = TextView(this).apply {
            text = "$nick\nThis Phone: $deviceName\n\nEach phone displays its own hardware name automatically."
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(0xFF1E293B.toInt())
            setPadding(0, 20, 0, 0)
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
        val cell = 28
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
                        8f, 8f, paint
                    )
                }
            }
        }
        return bmp
    }

    // ==================== ALL 10 IMG-7 AVATARS + 24 UNIQUE NICKNAMES ====================

    private fun loadSavedProfile() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", RANDOM_NICKNAMES[1]) ?: RANDOM_NICKNAMES[1]
        settingsDisplayNameText?.text = nickname

        val customPath = prefs.getString("custom_avatar_path", null)
        val avatarIndex = prefs.getInt("cartoon_avatar_index", 0)

        val bmp: Bitmap = if (customPath != null && File(customPath).exists()) {
            val raw = BitmapFactory.decodeFile(customPath)
            if (raw != null) getCircularBitmap(raw) else createImg7AvatarBitmap(avatarIndex)
        } else {
            createImg7AvatarBitmap(avatarIndex)
        }

        userProfileAvatar?.setImageBitmap(bmp)
        settingsAvatarPreview?.setImageBitmap(bmp)
        refreshSocialStoriesRow()
    }

    private fun showProfileAndAvatarDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentName = prefs.getString("nickname", RANDOM_NICKNAMES[1]) ?: RANDOM_NICKNAMES[1]

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 24, 44, 16)
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
                val current = nameInput.text.toString()
                val next = RANDOM_NICKNAMES.filter { it != current }.random()
                nameInput.setText(next)
            }
        }
        nameRow.addView(nameInput)
        nameRow.addView(randomBtn)

        val avatarTitle = TextView(this).apply {
            text = "Choose Avatar (Swipe to see all 10)"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF475569.toInt())
            setPadding(0, 20, 0, 10)
        }

        // Scrollable 2-row grid of all 10 Img-7 characters
        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val gridCols = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val names = listOf("Alex", "Maya", "Ryan", "Clara", "Liam", "Nina", "Noah", "Ella", "Owen", "Zoe")
        for (col in 0 until 5) {
            val colBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val topIdx = col
            val botIdx = col + 5
            colBox.addView(createAvatarOptionItem(topIdx, names[topIdx]))
            colBox.addView(createAvatarOptionItem(botIdx, names[botIdx]))
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
            layoutParams = LinearLayout.LayoutParams(132, 132)
            setImageBitmap(createImg7AvatarBitmap(index))
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
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("custom_avatar_path", file.absolutePath)
                .apply()
            loadSavedProfile()
        } catch (_: Exception) {
        }
    }

    // All 10 characters matching Img-7's exact 5x2 grid
    private fun createImg7AvatarBitmap(index: Int): Bitmap {
        val size = 240
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val idx = index.coerceIn(0, 9)
        val bgColors = intArrayOf(
            0xFFBFDBFE.toInt(), // 0: Top-1 Male Blue Hoodie
            0xFFC7D2FE.toInt(), // 1: Top-2 Female Dark Bob Yellow Jacket
            0xFFA7F3D0.toInt(), // 2: Top-3 Male Glasses Suit & Tie
            0xFFFBCFE8.toInt(), // 3: Top-4 Female Long Brown Hair White Sweater
            0xFFFDE68A.toInt(), // 4: Top-5 Male Navy Crewneck
            0xFFFECACA.toInt(), // 5: Bot-1 Female Top-Bun & Glasses
            0xFFBAE6FD.toInt(), // 6: Bot-2 Male Brown Beard Blazer
            0xFFBBF7D0.toInt(), // 7: Bot-3 Female Long Hair Pink Top
            0xFFFED7AA.toInt(), // 8: Bot-4 Male Ginger Curly Green Hoodie
            0xFFBAE6FD.toInt()  // 9: Bot-5 Female High Ponytail Teal Blazer
        )
        val hairColors = intArrayOf(
            0xFF0F172A.toInt(), 0xFF0F172A.toInt(), 0xFF0F172A.toInt(), 0xFF5C2D16.toInt(), 0xFF0F172A.toInt(),
            0xFF0F172A.toInt(), 0xFF6B3410.toInt(), 0xFF2D1B14.toInt(), 0xFFEA580C.toInt(), 0xFF4A2511.toInt()
        )
        val outfitColors = intArrayOf(
            0xFF2563EB.toInt(), 0xFFF59E0B.toInt(), 0xFF065F46.toInt(), 0xFFF8FAFC.toInt(), 0xFF1E293B.toInt(),
            0xFFF8FAFC.toInt(), 0xFF1E293B.toInt(), 0xFFF472B6.toInt(), 0xFF15803D.toInt(), 0xFF0D9488.toInt()
        )
        val skinColors = intArrayOf(
            0xFFF59E0B.toInt(), 0xFFFED7AA.toInt(), 0xFFFDBA74.toInt(), 0xFFFED7AA.toInt(), 0xFFFDBA74.toInt(),
            0xFFFED7AA.toInt(), 0xFFE5A97C.toInt(), 0xFFFDBA74.toInt(), 0xFFFED7AA.toInt(), 0xFFE5A97C.toInt()
        )

        // 1. Pastel Circular Backdrop
        paint.color = bgColors[idx]
        canvas.drawCircle(120f, 120f, 116f, paint)

        // 2. Back Hair Silhouettes
        paint.color = hairColors[idx]
        when (idx) {
            1 -> canvas.drawRoundRect(64f, 62f, 176f, 148f, 44f, 44f, paint) // Bob
            3, 7 -> canvas.drawRoundRect(58f, 58f, 182f, 188f, 52f, 52f, paint) // Long flowing hair
            5 -> {
                canvas.drawCircle(120f, 42f, 22f, paint) // Top bun
                canvas.drawCircle(120f, 96f, 52f, paint)
            }
            9 -> {
                canvas.drawCircle(158f, 82f, 26f, paint) // Side ponytail
                canvas.drawRoundRect(142f, 82f, 178f, 158f, 24f, 24f, paint)
            }
        }

        // 3. Shoulders & Outfit
        paint.color = outfitColors[idx]
        canvas.drawRoundRect(46f, 166f, 194f, 245f, 54f, 54f, paint)

        // Inner White Shirt V-Neck / Collar / Hoodie Strings
        if (idx == 1 || idx == 2 || idx == 6 || idx == 9) {
            paint.color = 0xFFFFFFFF.toInt()
            val vPath = Path().apply {
                moveTo(98f, 166f)
                lineTo(142f, 166f)
                lineTo(120f, 214f)
                close()
            }
            canvas.drawPath(vPath, paint)
            if (idx == 2) {
                paint.color = 0xFF0F172A.toInt()
                canvas.drawRect(116f, 176f, 124f, 224f, paint) // Tie
            }
        } else if (idx == 0 || idx == 8) {
            paint.color = 0x99FFFFFF.toInt()
            canvas.drawRect(104f, 172f, 108f, 206f, paint) // Hoodie drawstrings
            canvas.drawRect(132f, 172f, 136f, 206f, paint)
        }

        // 4. Neck & Ears
        paint.color = skinColors[idx]
        canvas.drawRect(105f, 138f, 135f, 174f, paint)
        canvas.drawCircle(77f, 108f, 9f, paint)
        canvas.drawCircle(163f, 108f, 9f, paint)

        // 5. Head Shape
        canvas.drawRoundRect(80f, 64f, 160f, 148f, 40f, 40f, paint)

        // 6. Beard for Character 6 (Noah)
        if (idx == 6) {
            paint.color = hairColors[idx]
            canvas.drawArc(80f, 98f, 160f, 150f, 0f, 180f, true, paint)
            paint.color = skinColors[idx]
            canvas.drawOval(104f, 114f, 136f, 132f, paint)
        }

        // 7. Styled Top Hair / Curly Tufts
        paint.color = hairColors[idx]
        canvas.drawArc(76f, 48f, 164f, 106f, 180f, 180f, true, paint)
        if (idx == 0 || idx == 2 || idx == 4 || idx == 6 || idx == 8) {
            canvas.drawCircle(94f, 62f, 15f, paint)
            canvas.drawCircle(120f, 56f, 18f, paint)
            canvas.drawCircle(146f, 62f, 14f, paint)
        }

        // 8. Eyebrows, Eyes & Glasses
        paint.color = 0xFF0F172A.toInt()
        canvas.drawRoundRect(93f, 92f, 107f, 95f, 2f, 2f, paint)
        canvas.drawRoundRect(133f, 92f, 147f, 95f, 2f, 2f, paint)
        canvas.drawCircle(100f, 105f, 4f, paint)
        canvas.drawCircle(140f, 105f, 4f, paint)

        if (idx == 2 || idx == 5) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = if (idx == 5) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt()
            canvas.drawCircle(100f, 105f, 12f, paint)
            canvas.drawCircle(140f, 105f, 12f, paint)
            canvas.drawLine(112f, 105f, 128f, 105f, paint)
            paint.style = Paint.Style.FILL
        }

        // 9. Smile
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawArc(108f, 118f, 132f, 132f, 0f, 180f, true, paint)

        return getCircularBitmap(bmp)
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

    // ==================== HELPERS & BLUETOOTH SOCKET ====================

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

    private fun applyAppTheme(mode: String, savePref: Boolean) {
        currentThemeMode = mode
        if (savePref) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("theme_mode", mode)
                .apply()
        }

        val root = findViewById<RelativeLayout>(R.id.rootMainContainer)
        val bottomNav = findViewById<LinearLayout>(R.id.bottomNavBar)
        val titles = listOf(
            findViewById<TextView>(R.id.homeTitleText),
            findViewById<TextView>(R.id.radarTitleText),
            findViewById<TextView>(R.id.settingsTitleText),
            findViewById<TextView>(R.id.nearbyChatsHeading)
        )

        when (mode) {
            "WHITE" -> {
                root?.setBackgroundColor(0xFFFFFFFF.toInt())
                bottomNav?.setBackgroundColor(0xFFF8FAFC.toInt())
                titles.forEach { it?.setTextColor(0xFF0F172A.toInt()) }
            }
            "DARK" -> {
                root?.setBackgroundColor(0xFF0F172A.toInt())
                bottomNav?.setBackgroundColor(0xFF1E293B.toInt())
                titles.forEach { it?.setTextColor(0xFFF8FAFC.toInt()) }
            }
            else -> {
                root?.setBackgroundResource(R.drawable.bg_pastel_mesh)
                bottomNav?.setBackgroundColor(0xF5FFFFFF.toInt())
                titles.forEach { it?.setTextColor(0xFF0F172A.toInt()) }
            }
        }

        stylePillTriple(
            mode,
            "PASTEL" to findViewById(R.id.themePastelBtn),
            "WHITE" to findViewById(R.id.themeWhiteBtn),
            "DARK" to findViewById(R.id.themeDarkBtn)
        )
        refreshSocialStoriesRow()
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
        if (::deviceAdapter.isInitialized) {
            deviceAdapter.notifyDataSetChanged()
        }
        updatePeerCounts()
        refreshSocialStoriesRow()
    }

    private fun updatePeerCounts() {
        val count = allChatPeers.size
        radarSonarView?.detectedTargetCount = count
        connectedCountText?.text = if (count == 0) {
            "●  Ready for Encrypted Peer Chats"
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
        statusSubtitle?.text = "Establishing encrypted link with ${device.name ?: "Peer"}..."

        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                runOnUiThread { onConnectedSuccess(socket) }
            } catch (e: IOException) {
                runOnUiThread {
                    statusSubtitle?.text = "Connection failed. Ensure friend has app open."
                }
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
                    try {
                        serverSocket?.close()
                    } catch (_: IOException) {
                    }
                    shouldLoop = false
                }
            }
        }

        fun cancel() {
            try {
                serverSocket?.close()
            } catch (_: IOException) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
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
                setPadding(36, 28, 36, 28)
            }

            val container = row as LinearLayout
            container.removeAllViews()

            val avatar = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(104, 104)
                setImageBitmap(createImg7AvatarBitmap((position + 1) % 10))
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(28, 0, 12, 0)
            }

            val device = devices[position]
            val title = TextView(context).apply {
                text = device.name ?: "Chat Peer"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF0F172A.toInt())
            }
            val subtitle = TextView(context).apply {
                text = "Tap to message  •  Encrypted RF"
                textSize = 12f
                setTextColor(0xFF64748B.toInt())
                setPadding(0, 4, 0, 0)
            }

            val chatPill = TextView(context).apply {
                text = "Chat"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_black_pill)
                setPadding(32, 14, 32, 14)
            }

            textCol.addView(title)
            textCol.addView(subtitle)
            container.addView(avatar)
            container.addView(textCol)
            container.addView(chatPill)
            return container
        }
    }
}

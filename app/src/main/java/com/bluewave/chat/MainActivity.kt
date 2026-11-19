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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "BlueWaveChat"
        private const val PREFS_NAME = "bluewave_prefs"

        private val RANDOM_NICKNAMES = listOf(
            "Ocean Nomad", "Sky Walker", "Pixel Drift", "Nova Spark",
            "Echo Rider", "Luna Breeze", "Coral Pulse", "Zephyr Fox"
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

    private var acceptThread: AcceptThread? = null
    private var isReceiverRegistered = false
    private var currentThemeMode = "PASTEL"
    private var isAutoRadarMode = true

    private val pickAvatarPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveCustomAvatarFromUri(it) }
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
                    statusSubtitle?.text = if (allChatPeers.isEmpty()) {
                        "No chat phones found nearby. Tap Broadcast Signal."
                    } else {
                        "Tap any nearby peer above to open chat"
                    }
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

            // Transparent Brand Logo beside "Blue Wave"
            val headerLogo = findViewById<ImageView>(R.id.headerBrandLogo)
            val rawLogo = BitmapFactory.decodeResource(resources, R.drawable.shark_logo_clean)
            if (rawLogo != null) {
                headerLogo?.setImageBitmap(makeWhiteBackgroundTransparent(rawLogo))
            }

            // Navigation Tabs
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

            // Radar Auto vs Manual Mode Toggle
            val radarAutoBtn = findViewById<TextView>(R.id.radarModeAutoBtn)
            val radarManualBtn = findViewById<TextView>(R.id.radarModeManualBtn)
            fun updateRadarModePills(auto: Boolean) {
                isAutoRadarMode = auto
                prefs.edit().putBoolean("radar_auto_mode", auto).apply()
                if (auto) {
                    radarAutoBtn?.setBackgroundResource(R.drawable.bg_black_pill)
                    radarAutoBtn?.setTextColor(0xFFFFFFFF.toInt())
                    radarManualBtn?.setBackgroundResource(R.drawable.bg_white_pill)
                    radarManualBtn?.setTextColor(0xFF0F172A.toInt())
                    statusSubtitle?.text = "Auto mode active • Scanning nearby peers..."
                } else {
                    radarManualBtn?.setBackgroundResource(R.drawable.bg_black_pill)
                    radarManualBtn?.setTextColor(0xFFFFFFFF.toInt())
                    radarAutoBtn?.setBackgroundResource(R.drawable.bg_white_pill)
                    radarAutoBtn?.setTextColor(0xFF0F172A.toInt())
                    bluetoothAdapter?.cancelDiscovery()
                    statusSubtitle?.text = "Manual mode • Tap Broadcast Signal to scan"
                }
            }
            updateRadarModePills(isAutoRadarMode)
            radarAutoBtn?.setOnClickListener {
                updateRadarModePills(true)
                requestBluetoothPermissionsAndStart()
            }
            radarManualBtn?.setOnClickListener {
                updateRadarModePills(false)
            }

            // Profile & Flat Vector Cartoon Avatars
            userProfileAvatar = findViewById(R.id.userProfileAvatar)
            settingsAvatarPreview = findViewById(R.id.settingsAvatarPreview)
            settingsDisplayNameText = findViewById(R.id.settingsDisplayNameText)
            loadSavedProfile()

            findViewById<View>(R.id.profileAvatarBtn)?.setOnClickListener { showProfileAndAvatarDialog() }
            findViewById<View>(R.id.settingsProfileCard)?.setOnClickListener { showProfileAndAvatarDialog() }

            // Live Search Bar
            findViewById<EditText>(R.id.searchPeersInput)?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applySearchFilter(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            // Settings Pills: Theme, Media Channel, Range Boost, Auto-Delete Timer, Chat Wallpaper
            setupSettingsControls(prefs)

            // Lists & Scan controls
            statusSubtitle = findViewById(R.id.statusSubtitle)
            connectedCountText = findViewById(R.id.connectedCountText)
            radarTotalConnectionsText = findViewById(R.id.radarTotalConnectionsText)
            emptyChatsHint = findViewById(R.id.emptyChatsHint)

            val scanButton = findViewById<View>(R.id.scanButton)
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

            scanButton?.setOnClickListener {
                startBluetoothScan()
            }

            applyAppTheme(currentThemeMode, false)

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

    // Filter out cars, headsets, earbuds, and smartwatches so only chat-capable phones/PCs appear
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
        ) {
            return false
        }
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
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (r > 235 && g > 235 && b > 235) {
                pixels[i] = Color.TRANSPARENT
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun setupSettingsControls(prefs: android.content.SharedPreferences) {
        findViewById<TextView>(R.id.themePastelBtn)?.setOnClickListener { applyAppTheme("PASTEL", true) }
        findViewById<TextView>(R.id.themeWhiteBtn)?.setOnClickListener { applyAppTheme("WHITE", true) }
        findViewById<TextView>(R.id.themeDarkBtn)?.setOnClickListener { applyAppTheme("DARK", true) }

        // Media Channel (Bluetooth Offline vs Internet Hybrid)
        val btnBt = findViewById<TextView>(R.id.mediaViaBluetoothBtn)
        val btnNet = findViewById<TextView>(R.id.mediaViaInternetBtn)
        val heroBadge = findViewById<TextView>(R.id.heroModeBadge)
        fun updateMediaChannel(mode: String) {
            prefs.edit().putString("media_channel", mode).apply()
            stylePillPair(mode == "BT", btnBt, btnNet)
            heroBadge?.text = if (mode == "BT") "Offline Mode" else "Hybrid Net"
        }
        updateMediaChannel(prefs.getString("media_channel", "BT") ?: "BT")
        btnBt?.setOnClickListener { updateMediaChannel("BT") }
        btnNet?.setOnClickListener { updateMediaChannel("NET") }

        // Bluetooth Range & Signal Boost
        val btnStd = findViewById<TextView>(R.id.rangeStandardBtn)
        val btnBoost = findViewById<TextView>(R.id.rangeBoostBtn)
        fun updateRange(boost: Boolean) {
            prefs.edit().putBoolean("signal_boost", boost).apply()
            stylePillPair(!boost, btnStd, btnBoost)
        }
        updateRange(prefs.getBoolean("signal_boost", true))
        btnStd?.setOnClickListener { updateRange(false) }
        btnBoost?.setOnClickListener { updateRange(true) }

        // Auto-Delete Chat Timer
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

        // Custom Chat Wallpaper
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

        // Switches & QR Quick Pair Card
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
        findViewById<View>(R.id.btnSystemBluetooth)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
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
    private fun showQuickPairCard() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nick = prefs.getString("nickname", "Ocean Nomad") ?: "Ocean Nomad"
        val deviceName = bluetoothAdapter?.name ?: "Android Device"

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 44, 56, 36)
        }
        val qrBitmap = generateVisualPairingBadge(nick + deviceName)
        val qrView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(340, 340)
            setImageBitmap(qrBitmap)
        }
        val label = TextView(this).apply {
            text = "$nick\nDevice ID: $deviceName\n\nAsk your friend to open Radar and tap '$deviceName' to connect instantly."
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(0xFF1E293B.toInt())
            setPadding(0, 24, 0, 0)
        }
        box.addView(qrView)
        box.addView(label)

        AlertDialog.Builder(this)
            .setTitle("Quick Pair Badge")
            .setView(box)
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

    // ==================== PROFILE & FLAT-ILLUSTRATED CARTOON AVATARS ====================

    private fun loadSavedProfile() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", RANDOM_NICKNAMES.first()) ?: RANDOM_NICKNAMES.first()
        settingsDisplayNameText?.text = nickname

        val customPath = prefs.getString("custom_avatar_path", null)
        val avatarIndex = prefs.getInt("cartoon_avatar_index", 0)

        val bmp: Bitmap = if (customPath != null && File(customPath).exists()) {
            val raw = BitmapFactory.decodeFile(customPath)
            if (raw != null) getCircularBitmap(raw) else createIllustratedAvatarBitmap(avatarIndex)
        } else {
            createIllustratedAvatarBitmap(avatarIndex)
        }

        userProfileAvatar?.setImageBitmap(bmp)
        settingsAvatarPreview?.setImageBitmap(bmp)
    }

    private fun showProfileAndAvatarDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentName = prefs.getString("nickname", RANDOM_NICKNAMES.first()) ?: RANDOM_NICKNAMES.first()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 16)
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
            setPadding(28, 18, 28, 18)
            setOnClickListener {
                nameInput.setText(RANDOM_NICKNAMES.random())
            }
        }
        nameRow.addView(nameInput)
        nameRow.addView(randomBtn)

        val avatarTitle = TextView(this).apply {
            text = "Tap a Character Avatar"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF475569.toInt())
            setPadding(0, 24, 0, 12)
        }

        val maleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val maleLabels = listOf("Alex", "Ryan", "Liam")
        for (i in 0..2) maleRow.addView(createAvatarOptionItem(i, maleLabels[i]))

        val femaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }
        val femaleLabels = listOf("Maya", "Clara", "Nina")
        for (i in 3..5) femaleRow.addView(createAvatarOptionItem(i, femaleLabels[i - 3]))

        container.addView(nameRow)
        container.addView(avatarTitle)
        container.addView(maleRow)
        container.addView(femaleRow)

        AlertDialog.Builder(this)
            .setTitle("Edit Profile")
            .setView(container)
            .setPositiveButton("Done") { _, _ ->
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
            setPadding(16, 8, 16, 8)
            isClickable = true
            isFocusable = true
        }
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(145, 145)
            setImageBitmap(createIllustratedAvatarBitmap(index))
        }
        val txt = TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF0F172A.toInt())
            setPadding(0, 4, 0, 0)
        }
        itemBox.addView(img)
        itemBox.addView(txt)

        // Silent instant update without any toast popup
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

    // Detailed flat vector-style portraits inspired by Img-7
    private fun createIllustratedAvatarBitmap(index: Int): Bitmap {
        val size = 240
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val bgColors = intArrayOf(
            0xFFBFDBFE.toInt(), // 0: Male Hoodie (Sky Blue)
            0xFFA7F3D0.toInt(), // 1: Male Glasses Suit (Mint)
            0xFFBAE6FD.toInt(), // 2: Male Bearded (Ice Blue)
            0xFFC7D2FE.toInt(), // 3: Female Bob Yellow Jacket (Periwinkle)
            0xFFFBCFE8.toInt(), // 4: Female Wavy Hair Sweater (Blush Pink)
            0xFFFECDD3.toInt()  // 5: Female Bun & Glasses (Coral Rose)
        )
        val hairColors = intArrayOf(
            0xFF1E293B.toInt(),
            0xFF0F172A.toInt(),
            0xFF78350F.toInt(),
            0xFF1E1B4B.toInt(),
            0xFF7C2D12.toInt(),
            0xFF1E293B.toInt()
        )
        val outfitColors = intArrayOf(
            0xFF2563EB.toInt(),
            0xFF065F46.toInt(),
            0xFF1E293B.toInt(),
            0xFFF59E0B.toInt(),
            0xFFF8FAFC.toInt(),
            0xFFFB7185.toInt()
        )
        val skinColors = intArrayOf(
            0xFFFDBA74.toInt(),
            0xFFFED7AA.toInt(),
            0xFFE7C697.toInt(),
            0xFFFED7AA.toInt(),
            0xFFFDE68A.toInt(),
            0xFFFDBA74.toInt()
        )

        val idx = index.coerceIn(0, 5)
        val isFemale = idx >= 3

        // Pastel Circle Backdrop
        paint.color = bgColors[idx]
        canvas.drawCircle(120f, 120f, 116f, paint)

        // Back Hair Layer for Female Characters
        paint.color = hairColors[idx]
        if (idx == 3) {
            canvas.drawRoundRect(62f, 64f, 178f, 152f, 44f, 44f, paint)
        } else if (idx == 4) {
            canvas.drawRoundRect(56f, 62f, 184f, 186f, 52f, 52f, paint)
        } else if (idx == 5) {
            canvas.drawCircle(120f, 44f, 24f, paint) // Top bun
            canvas.drawCircle(120f, 98f, 54f, paint)
        }

        // Torso / Shoulders
        paint.color = outfitColors[idx]
        canvas.drawRoundRect(46f, 166f, 194f, 245f, 54f, 54f, paint)

        // Neck
        paint.color = skinColors[idx]
        canvas.drawRect(104f, 138f, 136f, 174f, paint)

        // Ears
        canvas.drawCircle(76f, 108f, 10f, paint)
        canvas.drawCircle(164f, 108f, 10f, paint)

        // Head Oval
        canvas.drawRoundRect(78f, 64f, 162f, 150f, 42f, 42f, paint)

        // Beard for Character 2 (Liam)
        if (idx == 2) {
            paint.color = hairColors[idx]
            canvas.drawArc(78f, 96f, 162f, 152f, 0f, 180f, true, paint)
        }

        // Front Hair / Styled Fringe
        paint.color = hairColors[idx]
        if (!isFemale) {
            canvas.drawArc(74f, 48f, 166f, 104f, 180f, 180f, true, paint)
            canvas.drawCircle(96f, 64f, 16f, paint)
            canvas.drawCircle(120f, 58f, 18f, paint)
            canvas.drawCircle(144f, 64f, 15f, paint)
        } else {
            canvas.drawArc(74f, 52f, 166f, 112f, 180f, 180f, true, paint)
        }

        // Eyebrows & Eyes
        paint.color = 0xFF0F172A.toInt()
        canvas.drawRoundRect(92f, 92f, 108f, 96f, 3f, 3f, paint)
        canvas.drawRoundRect(132f, 92f, 148f, 96f, 3f, 3f, paint)
        canvas.drawCircle(100f, 106f, 4.5f, paint)
        canvas.drawCircle(140f, 106f, 4.5f, paint)

        // Glasses for Characters 1 (Ryan) and 5 (Nina)
        if (idx == 1 || idx == 5) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            canvas.drawCircle(100f, 106f, 13f, paint)
            canvas.drawCircle(140f, 106f, 13f, paint)
            canvas.drawLine(113f, 106f, 127f, 106f, paint)
            paint.style = Paint.Style.FILL
        }

        // Friendly Smile
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawArc(106f, 118f, 134f, 134f, 0f, 180f, true, paint)

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

    // ==================== THEME SWITCHER ====================

    private fun applyAppTheme(mode: String, savePref: Boolean) {
        currentThemeMode = mode
        if (savePref) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("theme_mode", mode)
                .apply()
        }

        val root = findViewById<RelativeLayout>(R.id.rootMainContainer)
        val bottomNav = findViewById<LinearLayout>(R.id.bottomNavBar)
        val homeTitle = findViewById<TextView>(R.id.homeTitleText)
        val radarTitle = findViewById<TextView>(R.id.radarTitleText)
        val settingsTitle = findViewById<TextView>(R.id.settingsTitleText)
        val nearbyHeading = findViewById<TextView>(R.id.nearbyChatsHeading)

        when (mode) {
            "WHITE" -> {
                root?.setBackgroundColor(0xFFFFFFFF.toInt())
                bottomNav?.setBackgroundColor(0xFFF8FAFC.toInt())
                homeTitle?.setTextColor(0xFF0F172A.toInt())
                radarTitle?.setTextColor(0xFF0F172A.toInt())
                settingsTitle?.setTextColor(0xFF0F172A.toInt())
                nearbyHeading?.setTextColor(0xFF0F172A.toInt())
            }
            "DARK" -> {
                root?.setBackgroundColor(0xFF0F172A.toInt())
                bottomNav?.setBackgroundColor(0xFF1E293B.toInt())
                homeTitle?.setTextColor(0xFFF8FAFC.toInt())
                radarTitle?.setTextColor(0xFFF8FAFC.toInt())
                settingsTitle?.setTextColor(0xFFF8FAFC.toInt())
                nearbyHeading?.setTextColor(0xFFF8FAFC.toInt())
            }
            else -> {
                root?.setBackgroundResource(R.drawable.bg_pastel_mesh)
                bottomNav?.setBackgroundColor(0xF5FFFFFF.toInt())
                homeTitle?.setTextColor(0xFF0F172A.toInt())
                radarTitle?.setTextColor(0xFF0F172A.toInt())
                settingsTitle?.setTextColor(0xFF0F172A.toInt())
                nearbyHeading?.setTextColor(0xFF0F172A.toInt())
            }
        }

        stylePillTriple(
            mode,
            "PASTEL" to findViewById(R.id.themePastelBtn),
            "WHITE" to findViewById(R.id.themeWhiteBtn),
            "DARK" to findViewById(R.id.themeDarkBtn)
        )
    }

    // ==================== SEARCH & BLUETOOTH SCANNING ====================

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
    }

    private fun updatePeerCounts() {
        val count = allChatPeers.size
        connectedCountText?.text = if (count == 0) {
            "●  Ready for Chat Peers"
        } else {
            "●  $count Chat Peer${if (count == 1) "" else "s"} Nearby"
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
        statusSubtitle?.text = "Scanning for nearby chat phones..."
    }

    @SuppressLint("MissingPermission")
    private fun startListeningServer() {
        acceptThread?.cancel()
        acceptThread = AcceptThread()
        acceptThread?.start()
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: BluetoothDevice) {
        bluetoothAdapter?.cancelDiscovery()
        statusSubtitle?.text = "Connecting to ${device.name ?: "Peer"}..."

        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                runOnUiThread {
                    onConnectedSuccess(socket)
                }
            } catch (e: IOException) {
                runOnUiThread {
                    statusSubtitle?.text = "Connection failed. Make sure peer has app open."
                }
            }
        }.start()
    }

    private fun onConnectedSuccess(socket: BluetoothSocket) {
        statusSubtitle?.text = "Opening encrypted Bluetooth chat..."
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
                    runOnUiThread {
                        onConnectedSuccess(it)
                    }
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
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_white_pill)
                setPadding(44, 34, 44, 34)
            }

            val container = row as LinearLayout
            container.removeAllViews()

            val device = devices[position]
            val title = TextView(context).apply {
                text = device.name ?: "Chat Peer"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF0F172A.toInt())
            }
            val subtitle = TextView(context).apply {
                text = "Tap to connect offline  •  ${device.address}"
                textSize = 12f
                setTextColor(0xFF64748B.toInt())
                setPadding(0, 6, 0, 0)
            }

            container.addView(title)
            container.addView(subtitle)
            return container
        }
    }
}

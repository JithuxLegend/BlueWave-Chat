package com.bluewave.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val allDiscoveredDevices = mutableListOf<BluetoothDevice>()
    private val filteredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceListAdapter

    private var connectionBadge: TextView? = null
    private var statusSubtitle: TextView? = null
    private var connectedCountText: TextView? = null
    private var emptyChatsHint: TextView? = null
    private var userProfileAvatar: ImageView? = null
    private var settingsAvatarPreview: ImageView? = null
    private var settingsDisplayNameText: TextView? = null

    private var acceptThread: AcceptThread? = null
    private var isReceiverRegistered = false
    private var currentThemeMode = "PASTEL" // PASTEL, WHITE, DARK

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
            startBluetoothScan()
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
                        if (!it.name.isNullOrEmpty() && allDiscoveredDevices.none { d -> d.address == it.address }) {
                            allDiscoveredDevices.add(it)
                            applySearchFilter("")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    statusSubtitle?.text = if (allDiscoveredDevices.isEmpty()) {
                        "No nearby peers found. Tap Broadcast Signal to retry."
                    } else {
                        "Tap any peer above to start offline chat"
                    }
                    connectionBadge?.text = "READY"
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

            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter

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

            // Profile Avatar & Nickname setup
            userProfileAvatar = findViewById(R.id.userProfileAvatar)
            settingsAvatarPreview = findViewById(R.id.settingsAvatarPreview)
            settingsDisplayNameText = findViewById(R.id.settingsDisplayNameText)

            loadSavedProfile()

            findViewById<View>(R.id.profileAvatarBtn)?.setOnClickListener {
                showProfileAndAvatarDialog()
            }
            findViewById<View>(R.id.settingsProfileCard)?.setOnClickListener {
                showProfileAndAvatarDialog()
            }

            // Live Search Filter
            val searchInput = findViewById<EditText>(R.id.searchPeersInput)
            searchInput?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applySearchFilter(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            // Settings Controls: Theme Switcher + Toggles + Bluetooth Shortcut
            val themePastelBtn = findViewById<TextView>(R.id.themePastelBtn)
            val themeWhiteBtn = findViewById<TextView>(R.id.themeWhiteBtn)
            val themeDarkBtn = findViewById<TextView>(R.id.themeDarkBtn)

            themePastelBtn?.setOnClickListener { applyAppTheme("PASTEL", true) }
            themeWhiteBtn?.setOnClickListener { applyAppTheme("WHITE", true) }
            themeDarkBtn?.setOnClickListener { applyAppTheme("DARK", true) }

            val switchAutoAccept = findViewById<Switch>(R.id.switchAutoAccept)
            val switchVibrate = findViewById<Switch>(R.id.switchVibrate)
            switchAutoAccept?.isChecked = prefs.getBoolean("auto_accept", true)
            switchVibrate?.isChecked = prefs.getBoolean("vibrate_msg", true)

            switchAutoAccept?.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("auto_accept", isChecked).apply()
            }
            switchVibrate?.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("vibrate_msg", isChecked).apply()
            }

            findViewById<View>(R.id.btnSystemBluetooth)?.setOnClickListener {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }

            // Lists & Scan controls
            connectionBadge = findViewById(R.id.connectionBadge)
            statusSubtitle = findViewById(R.id.statusSubtitle)
            connectedCountText = findViewById(R.id.connectedCountText)
            emptyChatsHint = findViewById(R.id.emptyChatsHint)

            val scanButton = findViewById<View>(R.id.scanButton)
            val deviceListView = findViewById<ListView>(R.id.deviceListView)
            val recentChatsListView = findViewById<ListView>(R.id.recentChatsListView)

            deviceAdapter = DeviceListAdapter(this, filteredDevices)
            deviceListView?.adapter = deviceAdapter
            recentChatsListView?.adapter = deviceAdapter

            deviceListView?.setOnItemClickListener { _, _, position, _ ->
                connectToPeer(filteredDevices[position])
            }
            recentChatsListView?.setOnItemClickListener { _, _, position, _ ->
                connectToPeer(filteredDevices[position])
            }

            scanButton?.setOnClickListener {
                requestBluetoothPermissionsAndStart()
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
                text = "Startup error:\n${e.message}\n\n${e.stackTraceToString().take(500)}"
                setPadding(40, 80, 40, 40)
                textSize = 14f
            }
            setContentView(fallback)
        }
    }

    // ==================== PROFILE & CARTOON AVATAR PICKER ====================

    private fun loadSavedProfile() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", "BlueWave Explorer") ?: "BlueWave Explorer"
        settingsDisplayNameText?.text = nickname

        val customPath = prefs.getString("custom_avatar_path", null)
        val avatarIndex = prefs.getInt("cartoon_avatar_index", 0)

        val bmp: Bitmap = if (customPath != null && File(customPath).exists()) {
            val raw = BitmapFactory.decodeFile(customPath)
            if (raw != null) getCircularBitmap(raw) else createCartoonAvatarBitmap(avatarIndex)
        } else {
            createCartoonAvatarBitmap(avatarIndex)
        }

        userProfileAvatar?.setImageBitmap(bmp)
        settingsAvatarPreview?.setImageBitmap(bmp)
    }

    private fun showProfileAndAvatarDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentName = prefs.getString("nickname", "BlueWave Explorer") ?: "BlueWave Explorer"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 32, 52, 20)
        }

        val nameLabel = TextView(this).apply {
            text = "Your Nickname"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF475569.toInt())
        }
        val nameInput = EditText(this).apply {
            setText(currentName)
            hint = "Enter your name"
        }

        val avatarTitle = TextView(this).apply {
            text = "Choose Cartoon Avatar (Male & Female)"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF475569.toInt())
            setPadding(0, 28, 0, 14)
        }

        // Row 1: Male Characters (0 = Leo, 1 = Max, 2 = Kai)
        val maleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val maleNames = listOf("Leo (M)", "Max (M)", "Kai (M)")
        for (i in 0..2) {
            maleRow.addView(createAvatarOptionItem(i, maleNames[i]))
        }

        // Row 2: Female Characters (3 = Maya, 4 = Aria, 5 = Zoe)
        val femaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 0)
        }
        val femaleNames = listOf("Maya (F)", "Aria (F)", "Zoe (F)")
        for (i in 3..5) {
            femaleRow.addView(createAvatarOptionItem(i, femaleNames[i - 3]))
        }

        container.addView(nameLabel)
        container.addView(nameInput)
        container.addView(avatarTitle)
        container.addView(maleRow)
        container.addView(femaleRow)

        AlertDialog.Builder(this)
            .setTitle("Profile & Avatar")
            .setView(container)
            .setPositiveButton("Save Name") { _, _ ->
                val newName = nameInput.text.toString().trim().ifEmpty { "BlueWave Explorer" }
                prefs.edit().putString("nickname", newName).apply()
                loadSavedProfile()
                Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Upload Photo") { _, _ ->
                val newName = nameInput.text.toString().trim().ifEmpty { "BlueWave Explorer" }
                prefs.edit().putString("nickname", newName).apply()
                pickAvatarPhotoLauncher.launch("image/*")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createAvatarOptionItem(index: Int, label: String): View {
        val itemBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 10, 16, 10)
            isClickable = true
            isFocusable = true
        }
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(130, 130)
            setImageBitmap(createCartoonAvatarBitmap(index))
        }
        val txt = TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF0F172A.toInt())
            setPadding(0, 6, 0, 0)
        }
        itemBox.addView(img)
        itemBox.addView(txt)

        itemBox.setOnClickListener {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt("cartoon_avatar_index", index)
                .remove("custom_avatar_path")
                .apply()
            loadSavedProfile()
            Toast.makeText(this, "Selected $label avatar!", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Profile photo updated!", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not load photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createCartoonAvatarBitmap(index: Int): Bitmap {
        val size = 200
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val bgColors = intArrayOf(
            0xFFBAE6FD.toInt(), // 0: Male Leo (Sky Blue)
            0xFFC7D2FE.toInt(), // 1: Male Max (Indigo Pastel)
            0xFFBBF7D0.toInt(), // 2: Male Kai (Mint)
            0xFFFBCFE8.toInt(), // 3: Female Maya (Pink)
            0xFFE9D5FF.toInt(), // 4: Female Aria (Lavender)
            0xFFFDE68A.toInt()  // 5: Female Zoe (Warm Peach)
        )
        val hairColors = intArrayOf(
            0xFF1E293B.toInt(), // Dark Navy
            0xFF78350F.toInt(), // Chestnut Brown
            0xFF0F172A.toInt(), // Jet Black
            0xFF581C87.toInt(), // Plum
            0xFF9A3412.toInt(), // Auburn
            0xFF1E3A8A.toInt()  // Cobalt
        )
        val shirtColors = intArrayOf(
            0xFF2563EB.toInt(),
            0xFF4F46E5.toInt(),
            0xFF059669.toInt(),
            0xFFDB2777.toInt(),
            0xFF7C3AED.toInt(),
            0xFFD97706.toInt()
        )
        val skinColors = intArrayOf(
            0xFFFED7AA.toInt(),
            0xFFFDE68A.toInt(),
            0xFFE7C697.toInt(),
            0xFFFED7AA.toInt(),
            0xFFF5D0A9.toInt(),
            0xFFD6A374.toInt()
        )

        val safeIdx = index.coerceIn(0, 5)
        val isFemale = safeIdx >= 3

        // Circular background
        paint.color = bgColors[safeIdx]
        canvas.drawCircle(100f, 100f, 96f, paint)

        // Long hair back layer for female characters
        if (isFemale) {
            paint.color = hairColors[safeIdx]
            canvas.drawCircle(100f, 98f, 54f, paint)
            canvas.drawRect(46f, 95f, 154f, 155f, paint)
        }

        // Shoulders / Shirt
        paint.color = shirtColors[safeIdx]
        canvas.drawCircle(100f, 185f, 62f, paint)

        // Face
        paint.color = skinColors[safeIdx]
        canvas.drawCircle(100f, 96f, 40f, paint)

        // Top Hair / Bangs
        paint.color = hairColors[safeIdx]
        canvas.drawArc(58f, 52f, 142f, 118f, 180f, 180f, true, paint)

        // Eyes & Happy Smile
        paint.color = 0xFF0F172A.toInt()
        canvas.drawCircle(85f, 96f, 4.5f, paint)
        canvas.drawCircle(115f, 96f, 4.5f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawArc(88f, 102f, 112f, 118f, 15f, 150f, false, paint)

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
            else -> { // PASTEL
                root?.setBackgroundResource(R.drawable.bg_pastel_mesh)
                bottomNav?.setBackgroundColor(0xF5FFFFFF.toInt())
                homeTitle?.setTextColor(0xFF0F172A.toInt())
                radarTitle?.setTextColor(0xFF0F172A.toInt())
                settingsTitle?.setTextColor(0xFF0F172A.toInt())
                nearbyHeading?.setTextColor(0xFF0F172A.toInt())
            }
        }

        updateThemeButtonPills(mode)
    }

    private fun updateThemeButtonPills(activeMode: String) {
        val btnPastel = findViewById<TextView>(R.id.themePastelBtn)
        val btnWhite = findViewById<TextView>(R.id.themeWhiteBtn)
        val btnDark = findViewById<TextView>(R.id.themeDarkBtn)

        val buttons = listOf(
            Pair("PASTEL", btnPastel),
            Pair("WHITE", btnWhite),
            Pair("DARK", btnDark)
        )
        for ((key, btn) in buttons) {
            if (key == activeMode) {
                btn?.setBackgroundResource(R.drawable.bg_black_pill)
                btn?.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn?.setBackgroundResource(R.drawable.bg_white_pill)
                btn?.setTextColor(0xFF0F172A.toInt())
            }
        }
    }

    // ==================== SEARCH & BLUETOOTH SCANNING ====================

    @SuppressLint("MissingPermission")
    private fun applySearchFilter(query: String) {
        val clean = query.trim().lowercase()
        filteredDevices.clear()
        if (clean.isEmpty()) {
            filteredDevices.addAll(allDiscoveredDevices)
        } else {
            filteredDevices.addAll(
                allDiscoveredDevices.filter {
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
        val count = allDiscoveredDevices.size
        connectedCountText?.text = if (count == 0) {
            "●  Ready for Offline Peers"
        } else {
            "●  $count Nearby Peer${if (count == 1) "" else "s"} Found"
        }
        emptyChatsHint?.visibility = if (filteredDevices.isEmpty()) View.VISIBLE else View.GONE
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

        allDiscoveredDevices.clear()
        adapter.bondedDevices?.forEach { device ->
            if (!device.name.isNullOrEmpty()) {
                allDiscoveredDevices.add(device)
            }
        }
        applySearchFilter("")

        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        adapter.startDiscovery()
        statusSubtitle?.text = "Scanning for nearby peers..."
        connectionBadge?.text = "SCANNING"
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
        connectionBadge?.text = "CONNECTING"

        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                runOnUiThread {
                    onConnectedSuccess(socket)
                }
            } catch (e: IOException) {
                runOnUiThread {
                    statusSubtitle?.text = "Connection failed. Make sure peer has BlueWave open."
                    connectionBadge?.text = "READY"
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun onConnectedSuccess(socket: BluetoothSocket) {
        val peerName = socket.remoteDevice.name ?: "BlueWave Peer"
        connectionBadge?.text = "CONNECTED"
        statusSubtitle?.text = "Opening encrypted Bluetooth chat..."

        ChatActivity.activeSocket = socket
        val intent = Intent(this, ChatActivity::class.java)
        startActivity(intent)
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
                text = device.name ?: "Unknown BlueWave Peer"
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

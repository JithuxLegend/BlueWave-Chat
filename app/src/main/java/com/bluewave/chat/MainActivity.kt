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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        const val APP_NAME = "BlueWaveChat"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceListAdapter

    private lateinit var connectionBadge: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var radarPulse: FrameLayout
    private lateinit var radarIconText: TextView

    private var acceptThread: AcceptThread? = null

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
                        if (!it.name.isNullOrEmpty() && discoveredDevices.none { d -> d.address == it.address }) {
                            discoveredDevices.add(it)
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    radarPulse.clearAnimation()
                    radarIconText.text = "SCAN"
                    statusSubtitle.text = "Found ${discoveredDevices.size} device(s). Tap to connect."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        connectionBadge = findViewById(R.id.connectionBadge)
        statusSubtitle = findViewById(R.id.statusSubtitle)
        radarPulse = findViewById(R.id.radarPulse)
        radarIconText = findViewById(R.id.radarIconText)

        val listView = findViewById<ListView>(R.id.deviceListView)
        deviceAdapter = DeviceListAdapter(this, discoveredDevices)
        listView.adapter = deviceAdapter

        listView.setOnItemClickListener { _, _, position, _ ->
            connectToDevice(discoveredDevices[position])
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            checkPermissionsAndRun { startBluetoothScan() }
        }

        radarPulse.setOnClickListener {
            checkPermissionsAndRun { startBluetoothScan() }
        }

        findViewById<Button>(R.id.btnVisible).setOnClickListener {
            checkPermissionsAndRun { makeDeviceDiscoverable() }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)

        checkPermissionsAndRun {
            startListeningServer()
        }
    }

    private fun checkPermissionsAndRun(action: () -> Unit) {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else permissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun makeDeviceDiscoverable() {
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        startActivity(discoverableIntent)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        if (bluetoothAdapter?.isEnabled == false) {
            Toast.makeText(this, "Please turn ON Bluetooth first!", Toast.LENGTH_SHORT).show()
            return
        }
        discoveredDevices.clear()
        bluetoothAdapter?.bondedDevices?.forEach { discoveredDevices.add(it) }
        deviceAdapter.notifyDataSetChanged()

        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        bluetoothAdapter?.startDiscovery()

        radarIconText.text = "LIVE"
        statusSubtitle.text = "Scanning airwaves within 30m..."
        val pulse = AlphaAnimation(1.0f, 0.35f).apply {
            duration = 700
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        radarPulse.startAnimation(pulse)
    }

    @SuppressLint("MissingPermission")
    private fun startListeningServer() {
        if (acceptThread == null) {
            acceptThread = AcceptThread()
            acceptThread?.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothAdapter?.cancelDiscovery()
        radarPulse.clearAnimation()
        statusSubtitle.text = "Connecting to ${device.name}..."

        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                runOnUiThread {
                    onConnectedSuccess(socket)
                }
            } catch (e: IOException) {
                runOnUiThread {
                    statusSubtitle.text = "Connection failed. Make sure BlueWave is open on ${device.name}."
                }
            }
        }.start()
    }

        @SuppressLint("MissingPermission")
    private fun onConnectedSuccess(socket: BluetoothSocket) {
        val peerName = socket.remoteDevice.name ?: "BlueWave Peer"
        connectionBadge.text = "Connected: $peerName"
        connectionBadge.setBackgroundColor(0xFF1E3A8A.toInt())
        statusSubtitle.text = "Opening encrypted Bluetooth chat..."

        ChatActivity.activeSocket = socket
        val intent = Intent(this, ChatActivity::class.java)
        startActivity(intent)
    }
    

    @SuppressLint("MissingPermission")
    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
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
                socket?.also {
                    runOnUiThread { onConnectedSuccess(it) }
                    serverSocket?.close()
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
        unregisterReceiver(receiver)
        acceptThread?.cancel()
    }

    private class DeviceListAdapter(context: Context, private val devices: List<BluetoothDevice>) :
        ArrayAdapter<BluetoothDevice>(context, 0, devices) {

        @SuppressLint("MissingPermission")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_device, parent, false)
            val device = devices[position]
            view.findViewById<TextView>(R.id.deviceName).text = device.name ?: "Unnamed Device"
            view.findViewById<TextView>(R.id.deviceAddress).text = device.address
            return view
        }
    }
}

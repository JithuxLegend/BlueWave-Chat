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
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "BlueWaveChat"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceListAdapter

    private lateinit var connectionBadge: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var connectedCountText: TextView
    private lateinit var emptyChatsHint: TextView

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
                            updatePeerCounts()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    statusSubtitle.text = if (discoveredDevices.isEmpty()) {
                        "No nearby peers found. Tap Broadcast Signal to retry."
                    } else {
                        "Tap any peer above to start offline chat"
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Tab containers (Page A = Chats, Page B = Radar, Page C = Settings)
        val pageChats = findViewById<View>(R.id.pageChatsContainer)
        val pageRadar = findViewById<View>(R.id.pageRadarContainer)
        val pageSettings = findViewById<View>(R.id.pageSettingsContainer)

        val tabRadar = findViewById<TextView>(R.id.tabRadarBtn)
        val tabChats = findViewById<TextView>(R.id.tabChatsBtn)
        val tabSettings = findViewById<TextView>(R.id.tabSettingsBtn)
        val quickScanLink = findViewById<TextView>(R.id.quickScanLink)

        fun showTab(tabIndex: Int) {
            pageRadar.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
            pageChats.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
            pageSettings.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE

            tabRadar.setTextColor(if (tabIndex == 0) 0xFF0F172A.toInt() else 0xFF94A3B8.toInt())
            tabChats.setTextColor(if (tabIndex == 1) 0xFF0F172A.toInt() else 0xFF94A3B8.toInt())
            tabSettings.setTextColor(if (tabIndex == 2) 0xFF0F172A.toInt() else 0xFF94A3B8.toInt())
        }

        // Open Page A (Chats) first
        showTab(1)

        tabRadar.setOnClickListener { showTab(0) }
        tabChats.setOnClickListener { showTab(1) }
        tabSettings.setOnClickListener { showTab(2) }
        quickScanLink.setOnClickListener { showTab(0) }

        // UI bindings
        connectionBadge = findViewById(R.id.connectionBadge)
        statusSubtitle = findViewById(R.id.statusSubtitle)
        connectedCountText = findViewById(R.id.connectedCountText)
        emptyChatsHint = findViewById(R.id.emptyChatsHint)

        val scanButton = findViewById<Button>(R.id.scanButton)
        val deviceListView = findViewById<ListView>(R.id.deviceListView)
        val recentChatsListView = findViewById<ListView>(R.id.recentChatsListView)

        deviceAdapter = DeviceListAdapter(this, discoveredDevices)
        deviceListView.adapter = deviceAdapter
        recentChatsListView.adapter = deviceAdapter

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            connectToPeer(discoveredDevices[position])
        }

        recentChatsListView.setOnItemClickListener { _, _, position, _ ->
            connectToPeer(discoveredDevices[position])
        }

        scanButton.setOnClickListener {
            requestBluetoothPermissionsAndStart()
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)

        requestBluetoothPermissionsAndStart()
    }

    private fun updatePeerCounts() {
        val count = discoveredDevices.size
        connectedCountText.text = if (count == 0) {
            "●  Ready for Offline Peers"
        } else {
            "●  $count Nearby Peer${if (count == 1) "" else "s"} Found"
        }
        emptyChatsHint.visibility = if (count == 0) View.VISIBLE else View.GONE
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
            statusSubtitle.text = "Please turn on Bluetooth"
            return
        }

        discoveredDevices.clear()
        adapter.bondedDevices?.forEach { device ->
            if (!device.name.isNullOrEmpty()) {
                discoveredDevices.add(device)
            }
        }
        deviceAdapter.notifyDataSetChanged()
        updatePeerCounts()

        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        adapter.startDiscovery()
        statusSubtitle.text = "Scanning for peers..."
        connectionBadge.text = "SCANNING"
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
        statusSubtitle.text = "Connecting to ${device.name ?: "Peer"}..."
        connectionBadge.text = "CONNECTING"

        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                runOnUiThread {
                    onConnectedSuccess(socket)
                }
            } catch (e: IOException) {
                runOnUiThread {
                    statusSubtitle.text = "Connection failed. Make sure peer has BlueWave open."
                    connectionBadge.text = "OFFLINE READY"
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
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        acceptThread?.cancel()
    }
}

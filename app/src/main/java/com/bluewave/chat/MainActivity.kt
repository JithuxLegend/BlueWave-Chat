package com.bluewave.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        statusText.text = if (allGranted) {
            "All Bluetooth & Audio Permissions Granted!\nReady for Phase 2 Radar."
        } else {
            "Permissions denied. Please allow Bluetooth & Mic access in Settings."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val btnPermissions = findViewById<Button>(R.id.btnCheckPermissions)

        btnPermissions.setOnClickListener {
            checkAndRequestPermissions()
        }
    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            neededPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            statusText.text = "All Bluetooth & Audio Permissions Granted!\nReady for Phase 2 Radar."
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}

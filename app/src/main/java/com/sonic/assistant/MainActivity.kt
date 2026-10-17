
package com.sonic.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private var enabled by mutableStateOf(false)
    private var status by mutableStateOf("Sonic is ready")

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            requestMicrophonePermission()
        }

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startBackgroundListening()
            } else {
                enabled = false
                status = "Allow microphone access to use Sonic"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = getSharedPreferences(
            "sonic",
            MODE_PRIVATE
        )

        enabled = preferences.getBoolean("enabled", false)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "SONIC",
                            style = MaterialTheme.typography.headlineLarge
                        )

                        Spacer(Modifier.height(20.dp))
                        Text(status)

                        Spacer(Modifier.height(24.dp))

                        Button(
                            onClick = {
                                if (enabled) {
                                    stopBackgroundListening()
                                } else {
                                    requestPermissionsAndStart()
                                }
                            }
                        ) {
                            Text(
                                if (enabled) {
                                    "Disable background Sonic"
                                } else {
                                    "Enable background Sonic"
                                }
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        Text(
                            "Once enabled, switch to another app and say " +
                                "\"Hey Sonic\". Wait for Sonic to say \"Yes?\", " +
                                "then speak your command."
                        )
                    }
                }
            }
        }

        // Restore the service when Sonic is opened again,
        // provided the user previously enabled it.
        if (
            enabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startBackgroundListening()
        } else if (enabled) {
            enabled = false
            preferences.edit().putBoolean("enabled", false).apply()
            status = "Enable Sonic again to grant microphone access"
        }
    }

    private fun requestPermissionsAndStart() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            requestMicrophonePermission()
        }
    }

    private fun requestMicrophonePermission() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startBackgroundListening()
        } else {
            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun startBackgroundListening() {
        val intent = Intent(
            this,
            WakeWordService::class.java
        ).apply {
            action = WakeWordService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            getSharedPreferences("sonic", MODE_PRIVATE)
                .edit()
                .putBoolean("enabled", true)
                .apply()

            enabled = true
            status = "Sonic background mode enabled"
        } catch (e: Exception) {
            enabled = false
            status = "Could not start Sonic: ${e.message}"
        }
    }

    private fun stopBackgroundListening() {
        stopService(
            Intent(this, WakeWordService::class.java)
        )

        getSharedPreferences("sonic", MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", false)
            .apply()

        enabled = false
        status = "Background listening disabled"
    }
}

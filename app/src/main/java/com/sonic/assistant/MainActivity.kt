
package com.sonic.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var status by mutableStateOf("Sonic is ready")
    private var heardText by mutableStateOf("Tap the orb to speak")
    private var listening by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startListening()
            } else {
                status = "Microphone permission required"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.getDefault()
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        status = "Listening to you..."
                    }

                    override fun onBeginningOfSpeech() {
                        status = "I'm hearing you..."
                    }

                    override fun onEndOfSpeech() {
                        status = "Thinking..."
                    }

                    override fun onResults(results: Bundle?) {
                        listening = false

                        val spoken = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()
                            .orEmpty()

                        if (spoken.isBlank()) {
                            status = "I didn't catch that. Try again."
                            return
                        }

                        heardText = spoken

                        val response =
                            if (spoken.lowercase(Locale.ROOT)
                                    .contains("hey sonic")) {
                                "Yes, I'm here. How can I help?"
                            } else {
                                "You said: $spoken"
                            }

                        speak(response)
                    }

                    override fun onError(error: Int) {
                        listening = false
                        status = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "Didn't catch that. Tap the orb again."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "I didn't hear anything."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Microphone permission is required."
                            else ->
                                "Listening stopped. Tap to retry."
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {}
                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )
        } else {
            status = "Speech recognition unavailable"
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF050914)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF050914))
                            .padding(horizontal = 24.dp, vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "SONIC",
                            color = Color(0xFFB9EFFF),
                            fontSize = 34.sp,
                            letterSpacing = 8.sp
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "YOUR PERSONAL AI",
                            color = Color(0xFF6F92B5),
                            fontSize = 11.sp,
                            letterSpacing = 3.sp
                        )

                        Spacer(Modifier.weight(1f))

                        // Large glowing, clickable blue orb
                        Box(
                            modifier = Modifier
                                .size(300.dp)
                                .clip(CircleShape)
                                .clickable {
                                    handleOrbTap()
                                }
                                .semantics {
                                    contentDescription =
                                        "Sonic voice assistant. Tap to speak."
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                val center = Offset(
                                    size.width / 2f,
                                    size.height / 2f
                                )

                                val radius = size.minDimension / 2f

                                // Wide, soft blue outer glow
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0x6650CFFF),
                                            Color(0x333A9EFF),
                                            Color(0x123A9EFF),
                                            Color.Transparent
                                        ),
                                        center = center,
                                        radius = radius
                                    ),
                                    radius = radius,
                                    center = center
                                )

                                // Bright blue halo
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0x005BCBFF),
                                            Color(0x5549CFFF),
                                            Color(0x2249CFFF),
                                            Color.Transparent
                                        ),
                                        center = center,
                                        radius = radius * 0.78f
                                    ),
                                    radius = radius * 0.78f,
                                    center = center
                                )

                                // Main luminous orb
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFE7FCFF),
                                            Color(0xFF75E4FF),
                                            Color(0xFF20A9FF),
                                            Color(0xFF1255D9),
                                            Color(0xFF07132E)
                                        ),
                                        center = Offset(
                                            center.x - radius * 0.18f,
                                            center.y - radius * 0.20f
                                        ),
                                        radius = radius * 0.86f
                                    ),
                                    radius = radius * 0.59f,
                                    center = center
                                )

                                // Glossy highlight
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xCCFFFFFF),
                                            Color(0x66B9F5FF),
                                            Color.Transparent
                                        ),
                                        center = Offset(
                                            center.x - radius * 0.20f,
                                            center.y - radius * 0.29f
                                        ),
                                        radius = radius * 0.27f
                                    ),
                                    radius = radius * 0.27f,
                                    center = Offset(
                                        center.x - radius * 0.20f,
                                        center.y - radius * 0.29f
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(30.dp))

                        Text(
                            text = status,
                            color = Color(0xFFB9EFFF),
                            fontSize = 18.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = heardText,
                            color = Color(0xFF8BA6C6),
                            fontSize = 14.sp
                        )

                        Spacer(Modifier.weight(1f))

                        Text(
                            text = if (listening)
                                "TAP TO STOP LISTENING"
                            else
                                "TAP THE ORB TO SPEAK",
                            color = Color(0xFF6685AA),
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }
        }
    }

    private fun handleOrbTap() {
        if (listening) {
            recognizer?.cancel()
            listening = false
            status = "Sonic paused"
            return
        }

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            permissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun startListening() {
        if (isFinishing || isDestroyed) return

        val engine = recognizer
        if (engine == null) {
            status = "Speech recognition unavailable"
            return
        }

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault()
            )
        }

        try {
            status = "Starting microphone..."
            engine.startListening(intent)
        } catch (e: Exception) {
            listening = false
            status = "Couldn't start. Please retry."
        }
    }

    private fun speak(message: String) {
        status = "Sonic is speaking..."

        if (!ttsReady || tts == null) {
            status = "Speech engine is not ready"
            return
        }

        val result = tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "sonic_reply"
        )

        if (result == TextToSpeech.ERROR) {
            status = "Couldn't speak. Try again."
        } else {
            status = "Sonic replied"
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        super.onDestroy()
    }
}


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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var active = false
    private var awaitingCommand = false

    private var statusCallback: ((String) -> Unit)? = null
    private var textCallback: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    statusCallback?.invoke("Listening… Say Hey Sonic")
                }

                override fun onBeginningOfSpeech() {
                    statusCallback?.invoke("I hear you…")
                }

                override fun onEndOfSpeech() {
                    statusCallback?.invoke("Processing…")
                }

                override fun onResults(results: Bundle?) {
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()

                    textCallback?.invoke(spoken)

                    if (!active) return

                    if (awaitingCommand) {
                        awaitingCommand = false
                        handleCommand(spoken)
                    } else if (spoken.contains("hey sonic", ignoreCase = true) ||
                        spoken.contains("hey, sonic", ignoreCase = true)
                    ) {
                        awaitingCommand = true
                        speak("Yes? I'm listening.") {
                            if (active) startListening()
                        }
                    } else {
                        statusCallback?.invoke(
                            "Didn't hear Hey Sonic. Try again."
                        )
                        startListening()
                    }
                }

                override fun onError(error: Int) {
                    if (active) {
                        statusCallback?.invoke(
                            "Listening again… Say Hey Sonic"
                        )
                        startListening()
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        setContent {
            var status by remember { mutableStateOf("Sonic is ready.") }
            var heard by remember { mutableStateOf("") }
            var listening by remember { mutableStateOf(false) }

            SideEffect {
                statusCallback = { status = it }
                textCallback = { heard = it }
            }

            val permissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        active = true
                        listening = true
                        startListening()
                    } else {
                        status = "Microphone permission is required."
                    }
                }

            val infinite = rememberInfiniteTransition(label = "orb")
            val pulse by infinite.animateFloat(
                initialValue = 0.94f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse"
            )

            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF050714))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "S O N I C",
                            color = Color.White,
                            fontSize = 25.sp
                        )
                        Text("VOICE", color = Color(0xFF42A5FF))
                    }

                    Spacer(Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .size((220 * pulse).dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF54C8FF),
                                        Color(0xFF1764FF),
                                        Color(0xFF091A59),
                                        Color(0xFF0A203A)
                                    )
                                ),
                                shape = CircleShape
                            )
                            .clickable {
                                if (listening) {
                                    active = false
                                    awaitingCommand = false
                                    listening = false
                                    recognizer?.cancel()
                                    status = "Sonic is resting."
                                } else if (
                                    ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    active = true
                                    listening = true
                                    startListening()
                                } else {
                                    permissionLauncher.launch(
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (listening) "●" else "S",
                            color = Color.White,
                            fontSize = 48.sp
                        )
                    }

                    Spacer(Modifier.height(36.dp))

                    Text(
                        if (listening) "I'm listening." else "Your world. One assistant.",
                        color = Color.White,
                        fontSize = 23.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        status,
                        color = Color.LightGray,
                        fontSize = 16.sp
                    )

                    if (heard.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Heard: $heard",
                            color = Color(0xFF69B7FF),
                            fontSize = 15.sp
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Text(
                        if (listening)
                            "Tap the orb to stop listening"
                        else
                            "Tap the orb to activate Sonic",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    private fun startListening() {
        if (!active || recognizer == null) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            recognizer?.startListening(intent)
        } catch (_: Exception) {
            statusCallback?.invoke("Couldn't start listening. Tap the orb to retry.")
        }
    }

    private fun handleCommand(command: String) {
        val answer = when {
            command.contains("hello", true) ||
            command.contains("hi sonic", true) ->
                "Hello! I'm Sonic, your personal assistant."

            command.contains("your name", true) ->
                "My name is Sonic."

            command.contains("time", true) ->
                "The time is ${
                    java.text.SimpleDateFormat(
                        "h:mm a",
                        Locale.getDefault()
                    ).format(java.util.Date())
                }."

            command.contains("stop listening", true) ||
            command.contains("go to sleep", true) -> {
                active = false
                "Okay. I'll stop listening. Tap the orb when you need me."
            }

            else ->
                "I heard you say $command. I don't know how to do that yet, but we'll teach me."
        }

        statusCallback?.invoke(answer)
        speak(answer) {
            if (active) startListening()
        }
    }

    private fun speak(message: String, onDone: () -> Unit = {}) {
        val engine = tts

        if (engine == null || !ttsReady) {
            statusCallback?.invoke(message)
            onDone()
            return
        }

        val utteranceId = "sonic_reply"

        engine.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )

        // Resume listening after speech has had time to finish.
        android.os.Handler(mainLooper).postDelayed({
            onDone()
        }, (message.length * 65L).coerceIn(1000L, 5000L))
    }

    override fun onDestroy() {
        active = false
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}

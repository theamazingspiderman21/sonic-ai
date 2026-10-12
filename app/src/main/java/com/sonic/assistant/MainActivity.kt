
package com.sonic.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    private var listening by mutableStateOf(false)
    private var status by mutableStateOf("Sonic is ready.")
    private var heardText by mutableStateOf("Tap the microphone to begin.")
    private var resumeAfterSpeech = false

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startListening()
            else status = "Microphone permission is required."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(object :
                RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    status = "Listening..."
                }

                override fun onBeginningOfSpeech() {
                    status = "I hear you..."
                }

                override fun onEndOfSpeech() {
                    status = "Processing your voice..."
                }

                override fun onResults(results: Bundle?) {
                    listening = false

                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    val spoken = matches?.firstOrNull().orEmpty()

                    if (spoken.isBlank()) {
                        status = "Didn't catch that. Try again."
                        return
                    }

                    heardText = spoken
                    val phrase = spoken.lowercase(Locale.ROOT)

                    if (phrase.contains("hey sonic")) {
                        status = "Sonic activated!"
                        speak(
                            "Yes, I'm listening. What do you need?",
                            true
                        )
                    } else {
                        status = "I heard you."
                        speak("You said: $spoken", true)
                    }
                }

                override fun onError(error: Int) {
                    listening = false
                    status = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH ->
                            "Didn't catch that. Tap to try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "No speech detected. Tap to try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is missing."
                        else -> "Recognition stopped. Tap to retry."
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
            })
        } else {
            status = "Speech recognition isn't available."
        }

        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (resumeAfterSpeech) {
                            resumeAfterSpeech = false
                            startListening()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    handler.post {
                        resumeAfterSpeech = false
                        status = "Speech output failed."
                    }
                }
            }
        )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement =
                            Arrangement.Center,
                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {
                        Text(
                            "SONIC",
                            style = MaterialTheme.typography.headlineLarge
                        )

                        Spacer(Modifier.height(16.dp))

                        Text(status)

                        Spacer(Modifier.height(24.dp))

                        Text(heardText)

                        Spacer(Modifier.height(32.dp))

                        Button(
                            onClick = {
                                if (listening) {
                                    stopListening()
                                } else if (
                                    checkSelfPermission(
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    startListening()
                                } else {
                                    permissionLauncher.launch(
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                }
                            }
                        ) {
                            Text(
                                if (listening)
                                    "Stop listening"
                                else
                                    "🎙 Start Sonic"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startListening() {
        if (isFinishing || isDestroyed) return

        val speechRecognizer = recognizer ?: run {
            status = "Speech recognition isn't available."
            return
        }

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
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
            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )
        }

        try {
            status = "Starting microphone..."
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            listening = false
            status = "Couldn't start listening. Try again."
        }
    }

    private fun stopListening() {
        listening = false
        resumeAfterSpeech = false
        recognizer?.cancel()
        tts?.stop()
        status = "Sonic paused."
    }

    private fun speak(text: String, listenAgain: Boolean) {
        resumeAfterSpeech = listenAgain

        val engine = tts
        if (engine == null) {
            resumeAfterSpeech = false
            status = "Speech engine isn't ready."
            return
        }

        val result = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "sonic_reply"
        )

        if (result == TextToSpeech.ERROR) {
            resumeAfterSpeech = false
            status = "Couldn't speak. Try again."
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        resumeAfterSpeech = false
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}

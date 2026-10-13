
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity(), RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private var heardText by mutableStateOf("Tap the orb and speak to Sonic.")
    private var statusText by mutableStateOf("Ready when you are")
    private var isListening by mutableStateOf(false)

    private val workerUrl =
        "https://sonic-ai-bridge.kulkarnit511.workers.dev"

    private val requestPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startListening()
            else statusText = "Microphone permission is needed."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textToSpeech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale("en", "IN")
                textToSpeech?.setSpeechRate(0.92f)
                textToSpeech?.setPitch(1.0f)
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(this)
        } else {
            statusText = "Speech recognition isn't available on this phone."
        }

        setContent {
            MaterialTheme {
                SonicScreen(
                    heardText = heardText,
                    statusText = statusText,
                    isListening = isListening,
                    onOrbTap = { checkMicrophoneAndListen() }
                )
            }
        }
    }

    private fun checkMicrophoneAndListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        val recognizer = speechRecognizer ?: run {
            statusText = "Speech recognition isn't available."
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            heardText = "Listening for your command..."
            statusText = "I'm listening"
            isListening = true
            recognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            statusText = "Couldn't start listening. Tap to try again."
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false

        val alternatives = results?.getStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION
        )

        val recognized = alternatives?.firstOrNull()?.trim()

        if (recognized.isNullOrBlank()) {
            heardText = "I didn't catch that."
            statusText = "Tap the orb and try again."
            return
        }

        heardText = "You said: $recognized"
        askSonic(recognized)
    }

    override fun onError(error: Int) {
        isListening = false

        statusText = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH ->
                "I didn't catch those words. Please try again."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "I didn't hear anything. Tap and speak again."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition needs a working network."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Please allow microphone access."
            else -> "Couldn't recognize speech. Tap to try again."
        }
    }

    private fun askSonic(prompt: String) {
        statusText = "Thinking..."

        Thread {
            var connection: HttpURLConnection? = null

            try {
                connection = (URL(workerUrl).openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 90000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                val payload = JSONObject().apply {
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject().apply {
                                put(
                                    "role",
                                    "system"
                                )
                                put(
                                    "content",
                                    "You are Sonic, a friendly, concise Android voice assistant. " +
                                        "Answer naturally in clear spoken English. " +
                                        "Avoid markdown, tables, emojis, and long lists when replying aloud. " +
                                        "Keep ordinary answers brief unless the user asks for detail."
                                )
                            }
                        ).put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", prompt)
                            }
                        )
                    )
                }

                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val response = stream?.bufferedReader()?.use {
                    it.readText()
                }.orEmpty()

                if (code !in 200..299) {
                    val errorJson = try {
                        JSONObject(response).optString(
                            "details",
                            JSONObject(response).optString("error", response)
                        )
                    } catch (_: Exception) {
                        response
                    }
                    throw Exception("HTTP $code: $errorJson")
                }

                val answer = JSONObject(response)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                runOnUiThread {
                    heardText = answer
                    statusText = "Sonic is speaking"
                    textToSpeech?.speak(
                        answer,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "sonic_answer"
                    )
                    if (textToSpeech == null) {
                        statusText = "Answer received, but speech isn't ready."
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    heardText = e.message ?: "Unknown connection error"
                    statusText = "Couldn't reach Sonic's AI brain."
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        statusText = "Listening..."
    }

    override fun onBeginningOfSpeech() {
        statusText = "Hearing you..."
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        isListening = false
        statusText = "Processing your words..."
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}

@Composable
private fun SonicScreen(
    heardText: String,
    statusText: String,
    isListening: Boolean,
    onOrbTap: () -> Unit
) {
    val background = Color(0xFF070B16)
    val blue = Color(0xFF28A9FF)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "SONIC",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "YOUR PERSONAL AI ASSISTANT",
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    color = Color(0xFF8295B5)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .shadow(
                            elevation = 28.dp,
                            shape = CircleShape,
                            ambientColor = blue,
                            spotColor = blue
                        )
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFB8F1FF),
                                    Color(0xFF28A9FF),
                                    Color(0xFF1244B5),
                                    background
                                )
                            ),
                            CircleShape
                        )
                        .clickable { onOrbTap() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFB6F5FF),
                                        Color(0xFF42B7FF),
                                        Color(0xFF1452D0)
                                    )
                                ),
                                CircleShape
                            )
                    )
                }

                Spacer(Modifier.height(30.dp))

                Text(
                    statusText,
                    fontSize = 17.sp,
                    color = if (isListening) blue else Color(0xFFDCE7FA),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    heardText,
                    fontSize = 15.sp,
                    color = Color(0xFF9EAFCA),
                    textAlign = TextAlign.Center,
                    lineHeight = 23.sp
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onOrbTap,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1266D6)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isListening) "LISTENING..." else "TALK TO SONIC",
                        modifier = Modifier.padding(vertical = 8.dp),
                        letterSpacing = 2.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Tap the orb or button to speak",
                    fontSize = 12.sp,
                    color = Color(0xFF687B9C)
                )
            }
        }
    }
}

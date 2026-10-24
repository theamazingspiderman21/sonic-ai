
package com.sonic.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

import org.json.JSONObject

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class WakeWordService : Service() {

    companion object {
        const val ACTION_START = "com.sonic.assistant.START"
        const val ACTION_STOP = "com.sonic.assistant.STOP"

        private const val CHANNEL_ID = "sonic_background"
        private const val NOTIFICATION_ID = 101

        private const val AI_URL =
            "https://sonic-ai-bridge.kulkarnit511.workers.dev"
    }

    private val sampleRate = 16000
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var serviceActive = false

    @Volatile
    private var detectorActive = false

    @Volatile
    private var stopRequested = false

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var recognizer: SpeechRecognizer? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var afterSpeech: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val engine = tts

                engine?.language = Locale("en", "IN")
                engine?.setSpeechRate(0.92f)
                engine?.setPitch(1.0f)

                engine?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}

                        override fun onDone(id: String?) {
                            mainHandler.post {
                                val next = afterSpeech
                                afterSpeech = null
                                next?.invoke()
                            }
                        }

                        override fun onError(id: String?) {
                            mainHandler.post {
                                val next = afterSpeech
                                afterSpeech = null
                                next?.invoke()
                            }
                        }
                    }
                )

                ttsReady = true

                pendingSpeech?.let { text ->
                    pendingSpeech = null
                    speakNow(text)
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("sonic", MODE_PRIVATE)
                .edit()
                .putBoolean("enabled", false)
                .apply()

            stopSelf()
            return START_NOT_STICKY
        }

        if (serviceActive) return START_STICKY

        stopRequested = false
        serviceActive = true

        try {
            val notification = buildNotification(
                "Starting background listening..."
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            startWakeWordLoop()
        } catch (e: Exception) {
            serviceActive = false
            stopSelf()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sonic background listening",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description =
                "Shows when Sonic is listening for its wake word"

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sonic is active")
            .setContentText(message)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Sonic",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(message)
        )
    }

    private fun startWakeWordLoop() {
        if (!serviceActive || detectorActive) return

        detectorActive = true

        worker = thread(
            start = true,
            name = "SonicWakeWord"
        ) {
            var localSpotter: KeywordSpotter? = null
            var localStream: OnlineStream? = null
            var localRecorder: AudioRecord? = null
            var wakeDetected = false
            var failure: String? = null

            try {
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder =
                            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        decoder =
                            "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        joiner =
                            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                    ),
                    tokens = "tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "zipformer2",
                    modelingUnit = "bpe",
                    bpeVocab = "bpe.model"
                )

                val kwsConfig = KeywordSpotterConfig(
                    featConfig = FeatureConfig(
                        sampleRate = sampleRate,
                        featureDim = 80
                    ),
                    modelConfig = modelConfig,
                    keywordsFile = "keywords.txt",
                    keywordsScore = 1.5f,
                    keywordsThreshold = 0.15f,
                    numTrailingBlanks = 2
                )

                val spotter = KeywordSpotter(
                    assetManager = assets,
                    config = kwsConfig
                )
                localSpotter = spotter

                if (!serviceActive) return@thread

                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBufferSize <= 0) {
                    throw IllegalStateException(
                        "Unsupported microphone format: $minBufferSize"
                    )
                }

                val bufferSize = maxOf(
                    minBufferSize * 2,
                    sampleRate * 2
                )

                val sources = mutableListOf<Pair<Int, String>>()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    sources.add(
                        Pair(
                            MediaRecorder.AudioSource.UNPROCESSED,
                            "UNPROCESSED"
                        )
                    )
                }

                sources.add(
                    Pair(MediaRecorder.AudioSource.MIC, "MIC")
                )

                for ((source, _) in sources) {
                    if (!serviceActive) return@thread

                    var candidate: AudioRecord? = null

                    try {
                        candidate = AudioRecord(
                            source,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        )

                        if (
                            candidate.state ==
                            AudioRecord.STATE_INITIALIZED
                        ) {
                            localRecorder = candidate
                            break
                        }
                    } catch (_: Exception) {
                    }

                    try {
                        candidate?.release()
                    } catch (_: Exception) {
                    }
                }

                val activeRecorder = localRecorder
                    ?: throw IllegalStateException(
                        "Could not initialize microphone"
                    )

                recorder = activeRecorder

                val stream = spotter.createStream()
                localStream = stream

                if (!serviceActive) return@thread

                activeRecorder.startRecording()

                if (
                    activeRecorder.recordingState !=
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    throw IllegalStateException(
                        "Microphone did not start recording"
                    )
                }

                updateNotification("Listening for Hey Sonic")

                val audioBuffer = ShortArray(
                    maxOf(bufferSize / 2, 2048)
                )

                // Microphone diagnostic timer.
                var lastAudioDiagnosticAt = 0L

                while (
                    serviceActive &&
                    !Thread.currentThread().isInterrupted
                ) {
                    val count = activeRecorder.read(
                        audioBuffer,
                        0,
                        audioBuffer.size,
                        AudioRecord.READ_BLOCKING
                    )

                    if (count < 0) {
                        throw IllegalStateException(
                            "Microphone read failed: $count"
                        )
                    }

                    if (count == 0) continue

                    val samples = FloatArray(count) { index ->
                        audioBuffer[index] / 32768.0f
                    }

                    // Measure the microphone signal.
                    var peak = 0f
                    var sumSquares = 0.0

                    for (sample in samples) {
                        val magnitude = kotlin.math.abs(sample)

                        if (magnitude > peak) {
                            peak = magnitude
                        }

                        sumSquares +=
                            sample.toDouble() * sample.toDouble()
                    }

                    val rms = kotlin.math.sqrt(
                        sumSquares / samples.size.coerceAtLeast(1)
                    )

                    val now = System.currentTimeMillis()

                    if (now - lastAudioDiagnosticAt >= 2000L) {
                        lastAudioDiagnosticAt = now

                        val diagnostic = String.format(
                            Locale.US,
                            "Mic test: peak %.3f | RMS %.4f",
                            peak,
                            rms
                        )

                        mainHandler.post {
                            if (serviceActive && detectorActive) {
                                updateNotification(diagnostic)
                            }
                        }
                    }

                    // Send the captured audio to Sherpa-ONNX.
                    stream.acceptWaveform(samples, sampleRate)

                    while (
                        serviceActive &&
                        spotter.isReady(stream)
                    ) {
                        spotter.decode(stream)

                        val keyword =
                            spotter.getResult(stream).keyword

                        if (keyword.isNotBlank()) {
                            spotter.reset(stream)
                            wakeDetected = true
                            break
                        }
                    }

                    if (wakeDetected) break
                }
            } catch (e: Exception) {
                if (serviceActive && !stopRequested) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            } finally {
                try {
                    if (
                        localRecorder?.recordingState ==
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        localRecorder.stop()
                    }
                } catch (_: Exception) {
                }

                try {
                    localRecorder?.release()
                } catch (_: Exception) {
                }

                try {
                    localStream?.release()
                } catch (_: Exception) {
                }

                try {
                    localSpotter?.release()
                } catch (_: Exception) {
                }

                if (recorder === localRecorder) {
                    recorder = null
                }

                detectorActive = false
                worker = null

                if (serviceActive && wakeDetected) {
                    mainHandler.post {
                        if (serviceActive) {
                            updateNotification(
                                "Wake word detected; waiting for command"
                            )
                            speakText("Yes?", ::beginCommandListening)
                        }
                    }
                } else if (serviceActive && failure != null) {
                    val message = failure!!

                    mainHandler.post {
                        updateNotification("Wake-word error: $message")
                    }
                }
            }
        }
    }

    private fun beginCommandListening() {
        if (!serviceActive) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakText(
                "Speech recognition is not available on this phone.",
                ::startWakeWordLoop
            )
            return
        }

        try {
            destroyRecognizer()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(
                        params: android.os.Bundle?
                    ) {
                        updateNotification("Listening for your command")
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        updateNotification("Processing your command")
                    }

                    override fun onError(error: Int) {
                        destroyRecognizer()

                        if (serviceActive) {
                            speakText(
                                "I didn't catch that. Say Hey Sonic again.",
                                ::startWakeWordLoop
                            )
                        }
                    }

                    override fun onResults(
                        results: android.os.Bundle?
                    ) {
                        val command = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()
                            .orEmpty()

                        destroyRecognizer()

                        if (command.isBlank()) {
                            speakText(
                                "I didn't catch that. Say Hey Sonic again.",
                                ::startWakeWordLoop
                            )
                        } else {
                            handleCommand(command)
                        }
                    }

                    override fun onPartialResults(
                        partialResults: android.os.Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: android.os.Bundle?
                    ) {}
                }
            )

            val speechIntent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "en-IN"
                )
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    false
                )
            }

            recognizer?.startListening(speechIntent)
        } catch (_: Exception) {
            destroyRecognizer()

            speakText(
                "I couldn't start speech recognition.",
                ::startWakeWordLoop
            )
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }

        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }

        recognizer = null
    }

    private fun handleCommand(command: String) {
        if (!serviceActive) return

        updateNotification("Thinking...")

        thread(
            start = true,
            name = "SonicAIRequest"
        ) {
            var connection: HttpURLConnection? = null

            try {
                connection = (URL(AI_URL).openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                    )
                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )
                }

                val payload = JSONObject().apply {
                    put(
                        "messages",
                        org.json.JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", command)
                            }
                        )
                    )
                }

                connection.outputStream.use { output ->
                    output.write(
                        payload.toString().toByteArray(Charsets.UTF_8)
                    )
                }

                val status = connection.responseCode

                val responseStream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val responseBody = responseStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()

                if (status !in 200..299) {
                    throw IllegalStateException(
                        "AI server returned HTTP $status"
                    )
                }

                val json = JSONObject(responseBody)

                val answer = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    .orEmpty()

                if (answer.isBlank()) {
                    throw IllegalStateException(
                        "The AI returned an empty response"
                    )
                }

                mainHandler.post {
                    if (serviceActive) {
                        speakText(answer, ::startWakeWordLoop)
                    }
                }
            } catch (e: Exception) {
                val error = e.message ?: "Unknown network error"

                mainHandler.post {
                    if (serviceActive) {
                        updateNotification("AI error: $error")
                        speakText(
                            "Sorry, I couldn't get an answer right now.",
                            ::startWakeWordLoop
                        )
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun speakText(
        text: String,
        after: (() -> Unit)? = null
    ) {
        if (!serviceActive) return

        afterSpeech = after

        if (!ttsReady || tts == null) {
            pendingSpeech = text
            return
        }

        speakNow(text)
    }

    private fun speakNow(text: String) {
        if (!ttsReady || tts == null) {
            pendingSpeech = text
            return
        }

        val result = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "sonic-${System.currentTimeMillis()}"
        )

        if (result == TextToSpeech.ERROR) {
            val next = afterSpeech
            afterSpeech = null
            next?.invoke()
        }
    }

    override fun onDestroy() {
        stopRequested = true
        serviceActive = false
        detectorActive = false

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recorder = null

        worker?.interrupt()
        worker = null

        destroyRecognizer()

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }

        tts = null
        ttsReady = false

        super.onDestroy()
    }
}(

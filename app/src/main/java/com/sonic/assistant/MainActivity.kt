
package com.sonic.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

import kotlin.concurrent.thread
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private val sampleRate = 16000

    @Volatile
    private var listening = false

    @Volatile
    private var stopRequested = false

    private var recorder: AudioRecord? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var worker: Thread? = null

    private var status by mutableStateOf("Sonic is ready")
    private var detected by mutableStateOf("Say: Hey Sonic")
    private var diagnostics by mutableStateOf(
        "Mic: not started\nReads: 0 | Decodes: 0"
    )

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startListening()
            } else {
                status = "Microphone permission denied"
                diagnostics = "Allow microphone access in app settings"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                            text = "SONIC",
                            style = MaterialTheme.typography.headlineLarge
                        )

                        Spacer(Modifier.height(24.dp))
                        Text(status)

                        Spacer(Modifier.height(12.dp))
                        Text(detected)

                        Spacer(Modifier.height(16.dp))
                        Text(diagnostics)

                        Spacer(Modifier.height(24.dp))

                        Button(
                            onClick = {
                                if (listening) {
                                    stopListening()
                                } else {
                                    requestAndStart()
                                }
                            }
                        ) {
                            Text(
                                if (listening) {
                                    "Stop listening"
                                } else {
                                    "Start listening"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestAndStart() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun startListening() {
        if (listening) return

        stopRequested = false
        listening = true

        status = "Loading wake-word model..."
        detected = "Say: Hey Sonic"
        diagnostics = "Initializing microphone..."

        worker = thread(
            start = true,
            name = "SonicWakeWord"
        ) {
            var localSpotter: KeywordSpotter? = null
            var localStream: OnlineStream? = null
            var localRecorder: AudioRecord? = null

            try {
                // Load the existing Sherpa-ONNX wake-word model.
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
                    keywordsThreshold = 0.25f,
                    numTrailingBlanks = 2
                )

                localSpotter = KeywordSpotter(
                    assetManager = assets,
                    config = kwsConfig
                )
                spotter = localSpotter

                if (!listening) return@thread

                // Ask Android for the minimum audio buffer size.
                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBufferSize <= 0) {
                    throw IllegalStateException(
                        "Unsupported audio format. Buffer result: $minBufferSize"
                    )
                }

                val bufferSize = maxOf(
                    minBufferSize * 2,
                    sampleRate * 2
                )

                // Try unprocessed audio first when available.
                // Fall back to the standard microphone source if
                // the first recorder cannot initialize.
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
                    Pair(
                        MediaRecorder.AudioSource.MIC,
                        "MIC"
                    )
                )

                var selectedSource = "Unknown"

                for ((source, sourceName) in sources) {
                    if (!listening) return@thread

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
                            selectedSource = sourceName
                            break
                        }
                    } catch (_: Exception) {
                        // Try the next supported audio source.
                    }

                    try {
                        candidate?.release()
                    } catch (_: Exception) {
                    }
                }

                if (localRecorder == null) {
                    throw IllegalStateException(
                        "Could not initialize UNPROCESSED or MIC audio"
                    )
                }

                recorder = localRecorder

                localStream = localSpotter.createStream()
                stream = localStream

                if (!listening) return@thread

                localRecorder.startRecording()

                if (
                    localRecorder.recordingState !=
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    throw IllegalStateException(
                        "Microphone did not enter recording state"
                    )
                }

                runOnUiThread {
                    status = "Listening for Hey Sonic..."
                    diagnostics = "Source: $selectedSource\nCollecting audio..."
                }

                val audioBuffer = ShortArray(
                    maxOf(bufferSize / 2, 2048)
                )

                var lastDiagnosticUpdate = System.currentTimeMillis()

                var readCount = 0L
                var decodedFrames = 0L
                var totalSamples = 0L
                var sumSquares = 0.0
                var peak = 0

                while (
                    listening &&
                    !Thread.currentThread().isInterrupted
                ) {
                    val count = localRecorder.read(
                        audioBuffer,
                        0,
                        audioBuffer.size,
                        AudioRecord.READ_BLOCKING
                    )

                    if (count < 0) {
                        throw IllegalStateException(
                            "AudioRecord.read failed: $count"
                        )
                    }

                    if (count == 0) continue

                    readCount++

                    val samples = FloatArray(count) { index ->
                        audioBuffer[index] / 32768.0f
                    }

                    // Accumulate audio measurements over the
                    // diagnostic interval rather than sampling
                    // only one possibly silent buffer.
                    for (index in 0 until count) {
                        val sample = samples[index].toDouble()
                        sumSquares += sample * sample

                        val absoluteValue =
                            kotlin.math.abs(audioBuffer[index].toInt())

                        if (absoluteValue > peak) {
                            peak = absoluteValue
                        }
                    }

                    totalSamples += count

                    // Feed captured audio to the wake-word detector.
                    localStream.acceptWaveform(
                        samples,
                        sampleRate
                    )

                    while (
                        listening &&
                        localSpotter.isReady(localStream)
                    ) {
                        localSpotter.decode(localStream)
                        decodedFrames++

                        val keyword =
                            localSpotter.getResult(localStream).keyword

                        if (keyword.isNotBlank()) {
                            localSpotter.reset(localStream)

                            runOnUiThread {
                                detected = "Detected: $keyword"
                                status = "Wake word detected!"
                            }
                        }
                    }

                    val now = System.currentTimeMillis()

                    if (now - lastDiagnosticUpdate >= 1000L) {
                        val rms = if (totalSamples > 0) {
                            sqrt(sumSquares / totalSamples)
                        } else {
                            0.0
                        }

                        val rmsMilli = (rms * 1000).toInt()
                        val peakValue = peak

                        val readsSnapshot = readCount
                        val decodesSnapshot = decodedFrames
                        val samplesSnapshot = totalSamples

                        runOnUiThread {
                            diagnostics =
                                "Source: $selectedSource\n" +
                                "RMS: $rmsMilli / 1000\n" +
                                "Peak: $peakValue / 32768\n" +
                                "Reads: $readsSnapshot\n" +
                                "Samples: $samplesSnapshot\n" +
                                "Decodes: $decodesSnapshot"
                        }

                        lastDiagnosticUpdate = now
                        sumSquares = 0.0
                        peak = 0
                        totalSamples = 0L
                    }
                }

            } catch (e: Exception) {
                if (listening && !stopRequested) {
                    val errorMessage =
                        e.message ?: e.javaClass.simpleName

                    runOnUiThread {
                        status = "Error: $errorMessage"
                        diagnostics = "Detector failed to start or continue"
                    }
                }
            } finally {
                listening = false

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

                recorder = null
                stream = null
                spotter = null
                worker = null

                runOnUiThread {
                    if (stopRequested) {
                        status = "Stopped"
                        diagnostics = "Microphone released"
                    } else if (!status.startsWith("Error:")) {
                        status = "Listening stopped"
                    }
                }
            }
        }
    }

    private fun stopListening() {
        stopRequested = true
        listening = false

        // Stop a blocking microphone read so the worker can exit.
        try {
            recorder?.let { audio ->
                if (
                    audio.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    audio.stop()
                }
            }
        } catch (_: Exception) {
        }

        status = "Stopping..."
        detected = "Tap Start listening to try again"
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }
}

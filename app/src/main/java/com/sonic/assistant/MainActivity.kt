
package com.sonic.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

    private var recorder: AudioRecord? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var worker: Thread? = null

    private var status by mutableStateOf("Sonic is ready")
    private var detected by mutableStateOf("Say: Hey Sonic")
    private var diagnostics by mutableStateOf("Mic: not started | Decodes: 0")

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startListening()
            } else {
                status = "Microphone permission is required"
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

                        Spacer(Modifier.height(12.dp))
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

        listening = true
        status = "Loading wake-word model..."
        detected = "Say: Hey Sonic"
        diagnostics = "Mic: starting | Decodes: 0"

        worker = thread(
            start = true,
            name = "SonicWakeWord"
        ) {
            var localSpotter: KeywordSpotter? = null
            var localStream: OnlineStream? = null
            var localRecorder: AudioRecord? = null

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
                    keywordsThreshold = 0.25f,
                    numTrailingBlanks = 2
                )

                localSpotter = KeywordSpotter(
                    assetManager = assets,
                    config = kwsConfig
                )
                spotter = localSpotter

                if (!listening) return@thread

                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBufferSize <= 0) {
                    throw IllegalStateException(
                        "Unsupported microphone audio format: $minBufferSize"
                    )
                }

                localRecorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2
                )
                recorder = localRecorder

                if (
                    localRecorder.state !=
                    AudioRecord.STATE_INITIALIZED
                ) {
                    throw IllegalStateException(
                        "Could not initialize microphone"
                    )
                }

                localStream = localSpotter.createStream()
                stream = localStream

                if (!listening) return@thread

                localRecorder.startRecording()

                runOnUiThread {
                    status = "Listening for Hey Sonic..."
                }

                val audioBuffer = ShortArray(
                    (minBufferSize / 2).coerceAtLeast(1024)
                )

                var lastDiagnosticUpdate = 0L
                var decodedFrames = 0L

                while (
                    listening &&
                    !Thread.currentThread().isInterrupted
                ) {
                    val count = localRecorder.read(
                        audioBuffer,
                        0,
                        audioBuffer.size
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

                    var sumSquares = 0.0

                    for (sample in samples) {
                        sumSquares += sample * sample
                    }

                    val audioLevel = sqrt(sumSquares / count)
                    val now = System.currentTimeMillis()

                    if (now - lastDiagnosticUpdate >= 1000L) {
                        lastDiagnosticUpdate = now

                        val level = (audioLevel * 1000).toInt()

                        runOnUiThread {
                            diagnostics =
                                "Mic: $level | Decodes: $decodedFrames"
                        }
                    }

                    localStream.acceptWaveform(samples, sampleRate)

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
                                status = "Hey Sonic detected!"
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                if (listening) {
                    runOnUiThread {
                        status = "Error: ${
                            e.message ?: "Wake-word detection failed"
                        }"
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
                    if (status.startsWith("Error:")) {
                        diagnostics = "Detector stopped"
                    } else if (status != "Stopped") {
                        status = "Listening stopped"
                        diagnostics = "Mic: stopped"
                    }
                }
            }
        }
    }

    private fun stopListening() {
        listening = false

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

        status = "Stopped"
        detected = "Tap Start listening to try again"
        diagnostics = "Mic: stopped"
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }
}

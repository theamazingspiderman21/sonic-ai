
package com.sonic.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val sampleRate = 16000

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var listening = false

    private var status by mutableStateOf("Sonic is ready")
    private var detected by mutableStateOf("Say: Hey Sonic")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("SONIC", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(24.dp))
                        Text(status)
                        Spacer(Modifier.height(12.dp))
                        Text(detected)
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
                            Text(if (listening) "Stop listening" else "Start listening")
                        }
                    }
                }
            }
        }
    }

    private fun requestAndStart() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                101
            )
            return
        }

        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 101 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else if (requestCode == 101) {
            status = "Microphone permission is required"
        }
    }

    private fun startListening() {
        if (listening) return

        status = "Loading wake-word model..."
        detected = "Say: Hey Sonic"

        worker = thread(start = true, name = "SonicWakeWord") {
            try {
                val model = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        decoder = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        joiner = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                    ),
                    tokens = "tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "zipformer2",
                    modelingUnit = "bpe",
                    bpeVocab = "bpe.model"
                )

                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(
                        samplingRate = sampleRate,
                        featureDim = 80
                    ),
                    modelConfig = model,
                    keywordsFile = "keywords.txt",
                    keywordsScore = 1.5f,
                    keywordsThreshold = 0.25f,
                    numTrailingBlanks = 2
                )

                val kws = KeywordSpotter(
                    assetManager = assets,
                    config = config
                )
                spotter = kws

                val audioBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (audioBufferSize <= 0) {
                    throw IllegalStateException(
                        "This device does not support the requested microphone format"
                    )
                }

                val audio = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    audioBufferSize * 2
                )
                recorder = audio

                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microphone could not be initialized")
                }

                val kwsStream = kws.createStream()
                stream = kwsStream

                audio.startRecording()
                listening = true

                runOnUiThread {
                    status = "Listening for Hey Sonic..."
                }

                val shortBuffer = ShortArray( audioBufferSize / 2 )

                while (listening && !Thread.currentThread().isInterrupted) {
                    val count = audio.read(
                        shortBuffer, 0, shortBuffer.size
                    )

                    if (count <= 0) continue

                    val samples = FloatArray(count) {
                        shortBuffer[it] / 32768.0f
                    }

                    kwsStream.acceptWaveform(
                        samples,
                        sampleRate = sampleRate
                    )

                    while (kws.isReady(kwsStream)) {
                        kws.decode(kwsStream)

                        val keyword = kws.getResult(kwsStream).keyword

                        if (keyword.isNotBlank()) {
                            kws.reset(kwsStream)

                            runOnUiThread {
                                detected = "Detected: $keyword"
                                status = "Hey Sonic detected!"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status = "Error: ${e.message ?: "Could not start wake-word detection"}"
                }
            } finally {
                listening = false

                try {
                    recorder?.let {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            it.stop()
                        }
                        it.release()
                    }
                } catch (_: Exception) {
                }

                recorder = null

                try {
                    stream?.release()
                } catch (_: Exception) {
                }

                stream = null

                try {
                    spotter?.release()
                } catch (_: Exception) {
                }

                spotter = null
            }
        }
    }

    private fun stopListening() {
        listening = false

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        status = "Stopped"
        detected = "Tap Start listening to try again"
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }
}

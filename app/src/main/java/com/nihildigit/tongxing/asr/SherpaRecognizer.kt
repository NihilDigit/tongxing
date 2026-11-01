package com.nihildigit.tongxing.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sherpa-ONNX Paraformer 离线语音识别封装
 * 替换 VoskRecognizer，提供更好的中文识别性能
 */
class SherpaRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "SherpaRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx-paraformer-zh-2024-03-09"
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"

        // === VAD 配置 ===
        // 说明：目前没有接入 Sherpa/WebRTC 内置 VAD，先用能量阈值做极简静音检测。
        // 真机调试 DiDi/MCP 前后可按需要替换为更可靠的 VAD。
        private const val VAD_SILENCE_TIMEOUT_MS = 1500L
        private const val VAD_START_THRESHOLD = 0.01f
        private const val VAD_END_THRESHOLD = 0.006f
    }

    interface Listener {
        fun onReady()
        fun onPartialResult(text: String)
        fun onResult(text: String)
        fun onError(error: String)
    }

    private var recognizer: OfflineRecognizer? = null
    private var stream: OfflineStream? = null
    private var audioRecord: AudioRecord? = null
    private var listener: Listener? = null

    private val isListening = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isFinalized = AtomicBoolean(false)

    /**
     * 初始化模型（异步）
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Initializing Sherpa-ONNX Paraformer model...")

            // 配置模型参数
            val modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = "$MODEL_DIR/$MODEL_FILE"
                ),
                tokens = "$MODEL_DIR/$TOKENS_FILE",
                numThreads = 2,  // 使用2线程以获得平衡性能和资源消耗
                provider = "cpu"  // 使用CPU推理，确保兼容性
            )

            val recognizerConfig = OfflineRecognizerConfig(modelConfig = modelConfig)

            // 创建识别器
            recognizer = OfflineRecognizer(
                assetManager = context.assets,
                config = recognizerConfig
            )

            Log.i(TAG, "Sherpa-ONNX model initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model initialization failed", e)
            listener?.onError("模型初始化失败: ${e.message}")
            false
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * 开始识别
     */
    fun startListening() {
        startListeningInternal(vadEnabled = false)
    }

    /**
     * 开始识别（带 VAD 自动断句）。
     *
     * WHEN 触发 ASR 后用户说话
     * AND 连续静音超过 1.5s
     * THEN 自动结束识别并回调最终结果。
     */
    fun startListeningWithVad() {
        startListeningInternal(vadEnabled = true)
    }

    private fun startListeningInternal(vadEnabled: Boolean) {
        if (isListening.getAndSet(true)) {
            Log.w(TAG, "Already listening")
            return
        }
        isFinalized.set(false)

        val currentRecognizer = recognizer
        if (currentRecognizer == null) {
            listener?.onError("识别器未初始化")
            isListening.set(false)
            return
        }

        try {
            // 创建新的识别流
            stream = currentRecognizer.createStream()

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val preferredSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            val fallbackSource = MediaRecorder.AudioSource.MIC
            audioRecord = AudioRecord(
                preferredSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "VOICE_RECOGNITION source init failed, falling back to MIC")
                audioRecord?.release()
                audioRecord = AudioRecord(
                    fallbackSource,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
            } else {
                Log.i(TAG, "Using VOICE_RECOGNITION audio source")
            }

            audioRecord?.startRecording()
            listener?.onReady()

            recordingJob = scope.launch {
                val buffer = ShortArray(bufferSize)
                val floatBuffer = FloatArray(bufferSize)
                var frameCount = 0
                val decodeInterval = 16  // 每16帧（约1秒）decode一次
                var hadSpeech = false
                var lastSpeechMs = System.currentTimeMillis()
                var lastVadLogMs = 0L
                val vadLogIntervalMs = 1000L

                try {
                    while (isListening.get() && isActive) {
                        val currentStream = stream ?: break
                        val currentAudioRecord = audioRecord ?: break
                        val currentOfflineRecognizer = recognizer ?: break

                        val read = currentAudioRecord.read(buffer, 0, buffer.size)
                        if (read > 0 && isActive && isListening.get()) {
                            // 将 Short 数组转换为 Float 数组（Sherpa-ONNX 需要 Float 输入）
                            for (i in 0 until read) {
                                floatBuffer[i] = buffer[i] / 32768.0f  // 归一化到 [-1, 1]
                            }

                            // 接受音频数据 - 只传入实际读取的采样数
                            val samplesSlice = floatBuffer.copyOfRange(0, read)
                            currentStream.acceptWaveform(samplesSlice, SAMPLE_RATE)

                            if (vadEnabled) {
                                val energy = rmsEnergy(samplesSlice)
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastVadLogMs >= vadLogIntervalMs) {
                                    lastVadLogMs = nowMs
                                    Log.d(
                                        TAG,
                                        "VAD energy=${"%.4f".format(energy)} start=${VAD_START_THRESHOLD} end=${VAD_END_THRESHOLD} hadSpeech=$hadSpeech silenceMs=${nowMs - lastSpeechMs}"
                                    )
                                }
                                if (energy > VAD_START_THRESHOLD) {
                                    if (!hadSpeech) {
                                        Log.d(TAG, "VAD speech started energy=${"%.4f".format(energy)}")
                                    }
                                    hadSpeech = true
                                    lastSpeechMs = nowMs
                                } else if (hadSpeech && energy < VAD_END_THRESHOLD && nowMs - lastSpeechMs >= VAD_SILENCE_TIMEOUT_MS) {
                                    Log.i(TAG, "VAD silence detected, auto-stopping")
                                    isListening.set(false)
                                    break
                                }
                            }

                            // 每隔约1秒decode一次，提供部分反馈（避免频繁decode导致性能问题）
                            frameCount++
                            if (frameCount >= decodeInterval) {
                                frameCount = 0
                                try {
                                    currentOfflineRecognizer.decode(currentStream)
                                    val partialResult = currentOfflineRecognizer.getResult(currentStream)
                                    val partialText = partialResult.text.trim()
                                    if (partialText.isNotEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            listener?.onPartialResult(partialText)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Partial decode failed: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 正常取消，不做处理
                } catch (e: Exception) {
                    Log.e(TAG, "Recording loop error", e)
                } finally {
                    // 只有在非 cancel 的自然退出（VAD 或外部 stop）时回调结果
                    if (!isFinalized.get()) {
                        finalizeResult()
                    }
                }
            }

            Log.i(TAG, "Listening started")
        } catch (e: Exception) {
            Log.e(TAG, "Start listening failed", e)
            listener?.onError(e.message ?: "未知错误")
            isListening.set(false)
        }
    }

    /**
     * 停止识别
     */
    fun stopListening() {
        if (!isListening.getAndSet(false)) {
            return
        }

        Log.i(TAG, "Stopping listening")

        // 先取消协程，等待它完成
        recordingJob?.cancel()
        runBlocking {
            recordingJob?.join()
        }
        recordingJob = null

        finalizeResult()
    }

    fun isListening(): Boolean = isListening.get()

    /**
     * 释放资源
     */
    fun release() {
        stopListening()
        scope.cancel()
        try {
            recognizer = null  // Sherpa-ONNX 的 Recognizer 不需要手动 close
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up recognizer", e)
        }
        recognizer = null
        Log.i(TAG, "Released")
    }

    /**
     * 解析 Sherpa-ONNX 识别结果
     */
    private fun parseSherpaResult(text: String?): String {
        return text?.trim() ?: ""
    }

    private fun rmsEnergy(samples: FloatArray): Float {
        var sum = 0f
        for (s in samples) {
            sum += s * s
        }
        return kotlin.math.sqrt(sum / samples.size)
    }

    private fun finalizeResult() {
        if (!isFinalized.compareAndSet(false, true)) return

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        audioRecord = null

        val finalText = try {
            val currentRecognizer = recognizer
            val currentStream = stream
            if (currentRecognizer != null && currentStream != null) {
                currentRecognizer.getResult(currentStream).text
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting final result", e)
            ""
        }

        stream = null

        if (finalText.isNotEmpty()) {
            scope.launch(Dispatchers.Main) {
                listener?.onResult(finalText)
            }
        }

        Log.i(TAG, "Listening stopped")
    }
}

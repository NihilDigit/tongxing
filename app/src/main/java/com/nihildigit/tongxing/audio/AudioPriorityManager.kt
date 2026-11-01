package com.nihildigit.tongxing.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.nihildigit.tongxing.settings.AppPreferences
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频优先级管理器
 * 实现 TTS 优先级队列和震动反馈
 *
 * 优先级（从高到低）：
 * 1. SAFETY - 安全告警（最高，可中断其他）
 * 2. SYSTEM - 系统/导航提示
 * 3. ASSISTANT - 助手回复
 */
class AudioPriorityManager(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "AudioPriorityManager"
    }

    /**
     * TTS 优先级
     */
    enum class Priority(val value: Int) {
        ASSISTANT(0),   // 助手回复
        SYSTEM(1),      // 系统提示
        SAFETY(2)       // 安全告警（最高）
    }

    enum class QueueMode {
        FLUSH,
        ADD
    }

    /**
     * TTS 队列项
     */
    data class TtsItem(
        val text: String,
        val priority: Priority,
        val queueMode: QueueMode = QueueMode.FLUSH,
        val utteranceId: String = UUID.randomUUID().toString()
    )

    // TTS 引擎
    private var tts: TextToSpeech? = null
    private val isTtsReady = AtomicBoolean(false)

    private val prefs = AppPreferences(context.applicationContext)

    @Volatile
    private var speechRate: Float = prefs.ttsSpeechRate

    @Volatile
    private var pitch: Float = prefs.ttsPitch

    @Volatile
    private var vibrationEnabled: Boolean = prefs.vibrationEnabled

    // 震动器
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // 优先级队列
    private val ttsQueue = ConcurrentLinkedQueue<TtsItem>()

    // 当前播放状态
    private val isSpeaking = AtomicBoolean(false)
    private var currentItem: TtsItem? = null

    // 等待播报完成的 deferred 映射
    private val awaitMap = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 回调
    private var onSpeakingStarted: ((TtsItem) -> Unit)? = null
    private var onSpeakingFinished: ((TtsItem) -> Unit)? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    engine.language = Locale.CHINESE
                    engine.setSpeechRate(speechRate)
                    engine.setPitch(pitch)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(TAG, "TTS started: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "TTS done: $utteranceId")
                            onUtteranceComplete()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "TTS error: $utteranceId")
                            onUtteranceComplete()
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.e(TAG, "TTS error: $utteranceId, code=$errorCode")
                            onUtteranceComplete()
                        }
                    })
                    isTtsReady.set(true)
                    Log.i(TAG, "TTS initialized")
                    processQueue()
                }
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    /**
     * 添加语音到队列
     */
    fun speak(text: String, priority: Priority) {
        if (text.isBlank()) return

        // 移除TTS不友好的元数据标签（如poi_map），只保留用户友好的内容
        val ttsText = text.replace(Regex("<poi_map>.*?</poi_map>", RegexOption.DOT_MATCHES_ALL), "").trim()

        val item = TtsItem(ttsText, priority, QueueMode.FLUSH)
        ttsQueue.add(item)
        Log.d(TAG, "Queued TTS: ${priority.name} - ${ttsText.take(30)}...")

        // 如果是安全告警且当前正在播放低优先级，则中断
        if (priority == Priority.SAFETY && isSpeaking.get()) {
            currentItem?.let {
                if (it.priority.value < priority.value) {
                    interruptCurrent()
                }
            }
        }

        processQueue()
    }

    /**
     * 播报文本并挂起直到播报完成。
     */
    suspend fun speakAndAwait(text: String, priority: Priority) {
        if (text.isBlank()) return
        val item = TtsItem(text, priority, QueueMode.FLUSH)
        val deferred = CompletableDeferred<Unit>()
        awaitMap[item.utteranceId] = deferred
        ttsQueue.add(item)
        processQueue()
        deferred.await()
    }

    /**
     * 添加流式语音片段到队列（按顺序播放，不打断当前播报）。
     */
    fun speakStreamingChunk(text: String, priority: Priority) {
        if (text.isBlank()) return

        val item = TtsItem(text, priority, QueueMode.ADD)
        ttsQueue.add(item)
        Log.d(TAG, "Queued streaming TTS: ${priority.name} - ${text.take(30)}...")

        processQueue()
    }


    /**
     * 为安全告警中断当前播放
     */
    fun interruptForSafety() {
        if (isSpeaking.get()) {
            interruptCurrent()
        }
        // 清除所有非安全优先级的队列项
        ttsQueue.removeIf { it.priority != Priority.SAFETY }
    }

    /**
     * 中断当前播放
     */
    private fun interruptCurrent() {
        tts?.stop()
        isSpeaking.set(false)
        currentItem?.let {
            Log.d(TAG, "Interrupted: ${it.utteranceId}")
        }
        currentItem = null
    }

    /**
     * 停止播放并清空队列（例如用户准备开始 ASR 时）。
     */
    fun stopAndClearQueue() {
        interruptCurrent()
        ttsQueue.clear()
        Log.d(TAG, "Cleared TTS queue")
    }

    /**
     * 处理队列
     */
    private fun processQueue() {
        if (!isTtsReady.get() || isSpeaking.get()) return

        // 按优先级选择队列中最先入队的最高优先级项
        val nextItem = pollNextItem()

        nextItem?.let { item ->
            currentItem = item
            isSpeaking.set(true)

            onSpeakingStarted?.invoke(item)

            tts?.speak(
                item.text,
                if (item.queueMode == QueueMode.ADD) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                null,
                item.utteranceId
            )
        }
    }

    private fun pollNextItem(): TtsItem? {
        var best: TtsItem? = null
        for (item in ttsQueue) {
            if (best == null || item.priority.value > best.priority.value) {
                best = item
            }
        }
        if (best != null) {
            ttsQueue.remove(best)
        }
        return best
    }

    /**
     * 语音播放完成回调
     */
    private fun onUtteranceComplete() {
        currentItem?.let {
            onSpeakingFinished?.invoke(it)
            awaitMap.remove(it.utteranceId)?.complete(Unit)
        }
        currentItem = null
        isSpeaking.set(false)
        processQueue()
    }

    /**
     * 震动反馈
     */
    fun vibrate(pattern: VibrationPattern) {
        if (!vibrationEnabled) return
        if (!vibrator.hasVibrator()) return

        val effect = when (pattern) {
            VibrationPattern.CRITICAL -> {
                // 双脉冲强震动
                VibrationEffect.createWaveform(
                    longArrayOf(0, 300, 100, 300),
                    intArrayOf(0, 255, 0, 255),
                    -1
                )
            }
            VibrationPattern.HIGH -> {
                // 单脉冲强震动
                VibrationEffect.createOneShot(200, 200)
            }
            VibrationPattern.MEDIUM -> {
                // 中等震动
                VibrationEffect.createOneShot(150, 150)
            }
            VibrationPattern.LIGHT -> {
                // 轻触反馈
                VibrationEffect.createOneShot(50, 100)
            }
            VibrationPattern.CONFIRM -> {
                // 确认反馈
                VibrationEffect.createOneShot(30, 80)
            }
        }

        vibrator.vibrate(effect)
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.0f)
        speechRate = clamped
        prefs.ttsSpeechRate = clamped
        tts?.setSpeechRate(clamped)
    }

    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        this.pitch = clamped
        prefs.ttsPitch = clamped
        tts?.setPitch(clamped)
    }

    fun setVibrationEnabled(enabled: Boolean) {
        vibrationEnabled = enabled
        prefs.vibrationEnabled = enabled
    }

    fun getSpeechRate(): Float = speechRate

    fun getPitch(): Float = pitch

    fun isVibrationEnabled(): Boolean = vibrationEnabled

    /**
     * 震动模式
     */
    enum class VibrationPattern {
        CRITICAL,   // 危急告警
        HIGH,       // 高威胁
        MEDIUM,     // 中等提示
        LIGHT,      // 轻触反馈
        CONFIRM     // 确认
    }

    /**
     * 停止所有音频
     */
    fun stopAll() {
        tts?.stop()
        ttsQueue.clear()
        isSpeaking.set(false)
        currentItem = null
    }

    /**
     * 设置回调
     */
    fun setCallbacks(
        onStarted: ((TtsItem) -> Unit)? = null,
        onFinished: ((TtsItem) -> Unit)? = null
    ) {
        onSpeakingStarted = onStarted
        onSpeakingFinished = onFinished
    }

    /**
     * 检查是否正在播放
     */
    fun isSpeaking(): Boolean = isSpeaking.get()

    override fun close() {
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

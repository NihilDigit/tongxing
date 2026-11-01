package com.nihildigit.tongxing.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.MediaMetadata
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.nihildigit.tongxing.overlay.coordinator.CameraCoordinator
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.nihildigit.agentcore.AgentEvent
import com.nihildigit.agentcore.Router
import com.nihildigit.agentcore.adapter.CloudChatAdapter
import com.nihildigit.agentcore.hub.ComboAction
import com.nihildigit.agentcore.hub.AndroidContextProvider
import com.nihildigit.agentcore.hub.ComboActionHandler
import com.nihildigit.agentcore.hub.ComboConfigResult
import com.nihildigit.agentcore.hub.Hub
import com.nihildigit.agentcore.hub.HubFactory
import com.nihildigit.agentcore.hub.HubConfig
import com.nihildigit.agentcore.logging.AgentLogger
import com.nihildigit.agentcore.model.InferenceMetrics
import com.nihildigit.lightwayllama.llm.LlmEngine
import com.nihildigit.tongxing.asr.SherpaRecognizer
import com.nihildigit.tongxing.audio.AudioPriorityManager
import com.nihildigit.tongxing.bridge.BridgeManager
import com.nihildigit.tongxing.camera.CameraTap
import com.nihildigit.tongxing.init.InitializationManager
import com.nihildigit.tongxing.init.InitializationManager.InitResult
import com.nihildigit.tongxing.mcp.MCPServiceConfig
import com.nihildigit.tongxing.model.toUiCombo
import com.nihildigit.tongxing.model.toUiSnapshot
import com.nihildigit.tongxing.model.toUiStatus
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.tongxing.state.AppState
import com.nihildigit.tongxing.benchmark.AgentCoreStats
import com.nihildigit.tongxing.overlay.coordinator.StatsCoordinator
import com.nihildigit.tongxing.overlay.coordinator.SafetyCoreCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Foreground Overlay Service.
 *
 * 常驻运行：持有 AgentCore + SafetyCore + ASR + 悬浮球 UI。
 * 注意：模式切换功能已废弃，当前固定为 NAVIGATION 模式。
 */
class OverlayService : LifecycleService() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "tongxing_assistant"
        private const val NOTIF_ID = 1001
        private const val ENABLE_CAMERA_HEARTBEAT_LOG = false
        private const val PREVIEW_UPDATE_INTERVAL_MS = 200L

        const val ACTION_START = "com.nihildigit.tongxing.overlay.START"
        const val ACTION_STOP = "com.nihildigit.tongxing.overlay.STOP"
        const val ACTION_SET_SAFETY_CORE_ENABLED = "com.nihildigit.tongxing.overlay.SET_SAFETY_CORE_ENABLED"
        const val EXTRA_SAFETY_CORE_ENABLED = "extra_safety_core_enabled"

        @Volatile
        private var instance: OverlayService? = null
        @Volatile
        private var suppressExternalActionsForBenchmark: Boolean = false

        /**
         * Get Hub instance for benchmark testing.
         * Returns null if OverlayService is not running or Hub not initialized.
         */
        fun getHubForBenchmark(): Hub? = instance?.hub

        /**
         * Get Router instance for benchmark testing.
         * Returns null if OverlayService is not running or Router not initialized.
         */
        fun getRouterForBenchmark(): Router? = instance?.router

        /**
         * 获取最新一条推理指标（用于 Benchmark）。
         */
        fun getLatestInferenceMetricsForBenchmark(): InferenceMetrics? =
            instance?.statsCoordinator?.agentCoreStats?.getLatestInferenceMetrics()

        /**
         * 获取当前 LLM 配置路径（用于 Benchmark 报告）。
         */
        fun getLlmConfigPathForBenchmark(): String? = instance?.preferences?.llmConfigPath

        /**
         * 获取 AgentCore 统计数据快照（用于 Benchmark Stem 数据）。
         */
        fun getAgentCoreStatsForBenchmark(): AgentCoreStats? =
            instance?.statsCoordinator?.agentCoreStats?.getStats()

        /**
         * 基准测试期间屏蔽外部跳转（如导航打开高德）。
         */
        fun setBenchmarkSuppressExternalActions(enabled: Boolean) {
            suppressExternalActionsForBenchmark = enabled
        }

    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 在当前编译配置下，Kotlin 对 LifecycleService 的 Context 继承识别不稳定，
    // 因此显式通过 cast 获取 Context / LifecycleOwner。
    private val ctx: Context get() = this as Context
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner get() = this as androidx.lifecycle.LifecycleOwner

    private lateinit var preferences: AppPreferences
    private lateinit var audioManager: AudioPriorityManager
    private lateinit var bridgeManager: BridgeManager
    private lateinit var cameraTap: CameraTap

    private var sherpaRecognizer: SherpaRecognizer? = null
    private var hub: Hub? = null
    private var router: Router? = null
    private var mainHandler = Handler(Looper.getMainLooper())

    private var comboConfigJob: Job? = null

    // 性能统计协调器
    private val statsCoordinator = StatsCoordinator(serviceScope)

    private val modeManager = ModeManager(initial = AssistantMode.NAVIGATION)
    private val overlayController by lazy {
        OverlayController(context = ctx)
    }

    // Camera coordinator
    private lateinit var cameraCoordinator: CameraCoordinator

    // Media session coordinator
    private var mediaSessionManager: com.nihildigit.tongxing.overlay.coordinator.MediaSessionManager? = null
    // SafetyCore + IMU coordinator
    private var safetyCoreCoordinator: SafetyCoreCoordinator? = null
    private var assistantAutoClearJob: Job? = null
    private val pendingConfirmAutoListen = java.util.concurrent.atomic.AtomicBoolean(false)
    private val requestSequence = java.util.concurrent.atomic.AtomicLong(0)

    private var lastPreviewUpdateMs = 0L
    private val lifecycleLogHandler = Handler(Looper.getMainLooper())
    private val lifecycleLogRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Status Heartbeat: Lifecycle=${lifecycle.currentState} | Mode=${modeManager.currentMode.value} | Frames=${cameraCoordinator.frameCount} | FP=${if (safetyCoreCoordinator?.isActive == true) "SET" else "NULL"}")
            lifecycleLogHandler.postDelayed(this, 5000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Lifecycle: onStartCommand entry - State=${lifecycle.currentState}")
        
        // Force startForeground immediately to satisfy Android OS requirements
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SET_SAFETY_CORE_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_SAFETY_CORE_ENABLED, true)
                applySafetyCoreEnabled(enabled, fromUserToggle = true)
            }
            ACTION_START, null -> {
                if (!isInitializing.getAndSet(true)) {
                    startBackgroundTaskFlow()
                }
            }
        }
        return START_STICKY
    }

    private val isInitializing = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun startBackgroundTaskFlow() {
        Log.i(TAG, "Initialization: Starting SERIALIZED background flow")

        cameraCoordinator.start()

        serviceScope.launch {
            try {
                Log.i(TAG, "Initialization: Waiting for camera frames...")
                var waitCount = 0
                while (cameraCoordinator.frameCount == 0 && waitCount < 50) {
                    kotlinx.coroutines.delay(100)
                    waitCount++
                }

                if (cameraCoordinator.frameCount > 0) {
                    Log.i(TAG, "Initialization: Camera flow confirmed at count=${cameraCoordinator.frameCount}. Moving to AI load.")
                } else {
                    Log.w(TAG, "Initialization: Camera flow timeout, starting AI load anyway.")
                }

                if (preferences.safetyCoreDisabled) {
                    Log.i(TAG, "Initialization Stage 1: SafetyCore skipped (disabled in developer options)")
                    AppState.setSafetyCoreRunning(false)
                } else {
                    Log.i(TAG, "Initialization Stage 1: SafetyCore...")
                    val safetyCoreResult = InitializationManager.withTimeout(
                        component = "SafetyCore",
                        timeoutMs = InitializationManager.TIMEOUT_SAFETY_CORE_MS
                    ) {
                        initSafetyCore()
                    }
                    when (safetyCoreResult) {
                        is InitResult.Timeout -> {
                            Log.e(TAG, "Initialization: SafetyCore timed out")
                            AppState.setError("安全引擎初始化超时")
                        }
                        is InitResult.Error -> {
                            Log.e(TAG, "Initialization: SafetyCore error", safetyCoreResult.throwable)
                            AppState.setError("安全引擎初始化失败：${safetyCoreResult.throwable.message}")
                        }
                        is InitResult.Success -> {}
                    }
                }

                Log.i(TAG, "Initialization Stage 2: ASR...")
                val asrResult = InitializationManager.withTimeout(
                    component = "ASR",
                    timeoutMs = InitializationManager.TIMEOUT_ASR_MS
                ) {
                    initAsr()
                }
                when (asrResult) {
                    is InitResult.Timeout -> {
                        Log.e(TAG, "Initialization: ASR timed out")
                    }
                    is InitResult.Error -> {
                        Log.e(TAG, "Initialization: ASR error", asrResult.throwable)
                    }
                    is InitResult.Success -> {}
                }

                Log.i(TAG, "Initialization Stage 3: AgentCore...")
                val agentCoreResult = InitializationManager.withTimeout(
                    component = "AgentCore",
                    timeoutMs = InitializationManager.TIMEOUT_AGENT_CORE_MS
                ) {
                    initAgentCore()
                }
                when (agentCoreResult) {
                    is InitResult.Timeout -> {
                        Log.e(TAG, "Initialization: AgentCore timed out")
                    }
                    is InitResult.Error -> {
                        Log.e(TAG, "Initialization: AgentCore error", agentCoreResult.throwable)
                    }
                    is InitResult.Success -> {}
                }

                val issues = InitializationManager.validateSystemHealth()
                if (issues.isEmpty()) {
                    Log.i(TAG, "Initialization: COMPLETE - All systems ready")
                } else {
                    Log.w(TAG, "Initialization: COMPLETE with issues: ${issues.joinToString()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization: Critical error", e)
                withContext(Dispatchers.Main) {
                    AppState.setError("系统初始化失败：${e.message}")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Lifecycle: Service onCreate entry - State=${lifecycle.currentState}")
        
        // Setup infrastructure but NO heavy model loading here
        preferences = AppPreferences(ctx.applicationContext)
        audioManager = AudioPriorityManager(ctx.applicationContext)
        audioManager.setCallbacks(
            onStarted = { item ->
                if (item.priority == AudioPriorityManager.Priority.ASSISTANT) {
                    AppState.setAssistantState(AppState.AssistantState.SPEAKING)
                }
            },
            onFinished = { item ->
                if (item.priority == AudioPriorityManager.Priority.ASSISTANT) {
                    val shouldAutoListen = pendingConfirmAutoListen.getAndSet(false)
                    val recognizer = sherpaRecognizer
                    if (shouldAutoListen && recognizer != null && !recognizer.isListening()) {
                        AppState.setAssistantState(AppState.AssistantState.LISTENING)
                        AppState.clearAssistantResponse()
                        AppState.clearAsrTranscription()
                        cameraTap.lockFrame()
                        recognizer.startListeningWithVad()
                    } else {
                        val stillListening = recognizer?.isListening() == true
                        AppState.setAssistantState(
                            if (stillListening) AppState.AssistantState.LISTENING else AppState.AssistantState.IDLE
                        )
                    }
                }
            }
        )
        bridgeManager = BridgeManager(ctx.applicationContext, audioManager)
        cameraTap = CameraTap()
        cameraCoordinator = CameraCoordinator(
            context = ctx,
            targetResolution = android.util.Size(640, 480),
            targetFpsRange = android.util.Range(30, 30)
        ) { bitmap, timestampNanos ->
            if (modeManager.currentMode.value == AssistantMode.NAVIGATION) {
                onCameraFrame(bitmap, timestampNanos)
            }
        }

        createNotificationChannel()
        // We will call startForeground in onStartCommand as well, but first one must be fast
        startForeground(NOTIF_ID, buildNotification())

        overlayController.show()
        mediaSessionManager = com.nihildigit.tongxing.overlay.coordinator.MediaSessionManager(
            context = ctx,
            onMediaButtonClick = { toggleAsrClearingTts() }
        ).also { it.setup() }
        HeadsetButtonReceiver.service = this

        modeManager.switchMode(AssistantMode.NAVIGATION)
        lifecycleLogHandler.postDelayed(lifecycleLogRunnable, 2000)
        AppState.setOverlayRunning(true)
        AppState.setSafetyCoreRunning(!preferences.safetyCoreDisabled)

        Log.i(TAG, "Lifecycle: Service onCreate exit - State=${lifecycle.currentState}")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        AppState.setOverlayRunning(false)
        AppState.setSafetyCoreRunning(false)
        overlayController.hide()
        lifecycleLogHandler.removeCallbacks(lifecycleLogRunnable)
        assistantAutoClearJob?.cancel()
        assistantAutoClearJob = null
        mediaSessionManager?.release()
        mediaSessionManager = null
        HeadsetButtonReceiver.service = null

        cameraCoordinator.shutdown()
        safetyCoreCoordinator?.release()
        safetyCoreCoordinator = null
        statsCoordinator.stop()

        sherpaRecognizer?.release()
        sherpaRecognizer = null

        val hubReleased = runBlocking {
            withTimeoutOrNull(3000) {
                hub?.release()
                true
            } ?: false
        }
        if (!hubReleased) {
            Log.w(TAG, "Hub release timed out after 3000ms")
        }
        router?.release()
        router = null
        hub = null

        // Reset runtime-facing UI state so dashboard reflects service teardown immediately.
        AppState.setLatestFrameResult(null)
        AppState.setPreviewFrame(null)
        AppState.setSafetyLevel(AppState.SafetyLevel.SAFE)
        AppState.setAssistantState(AppState.AssistantState.IDLE)
        AppState.setCameraHealth(AppState.ComponentHealth.UNKNOWN)
        AppState.setSafetyCoreHealth(AppState.ComponentHealth.UNKNOWN)
        AppState.setAsrHealth(AppState.ComponentHealth.UNKNOWN)
        AppState.setAgentCoreHealth(AppState.ComponentHealth.UNKNOWN)
        AppState.setMcpConnectionStatuses(emptyList())
        AppState.setAvailableCombos(emptyList())

        serviceScope.coroutineContext[Job]?.cancel()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    fun toggleAsrClearingTts() {
        // 用户按键触发：停止播报并清空队列，然后 toggle ASR
        pendingConfirmAutoListen.set(false)
        audioManager.stopAndClearQueue()
        Router.cancelGeneration()
        val recognizer = sherpaRecognizer ?: run {
            AppState.resetToIdle()
            return
        }
        if (recognizer.isListening()) {
            recognizer.stopListening()
            AppState.resetToIdle()
        } else {
            AppState.setAssistantState(AppState.AssistantState.LISTENING)
            AppState.clearAssistantResponse()
            AppState.clearAsrTranscription()
            cameraTap.lockFrame()
            recognizer.startListeningWithVad()
        }
    }

    private fun setupBridgeCallbacks() {
        bridgeManager.setAsrStopCallback {
            sherpaRecognizer?.stopListening()
        }
        bridgeManager.setAgentCancelCallback {
            Router.cancelGeneration()
        }
    }

    private fun initSafetyCore() {
        safetyCoreCoordinator = SafetyCoreCoordinator(
            context = ctx.applicationContext,
            onFrameResultCallback = { result ->
                bridgeManager.onSafetyCoreResult(result)
                AppState.setLatestFrameResult(result.toUiSnapshot())
                result.telemetry?.let { statsCoordinator.safetyCoreStats.record(it) }
            }
        ).also {
            it.init()
            statsCoordinator.start()
            AppState.setSafetyCoreRunning(true)
        }
    }

    private fun applySafetyCoreEnabled(enabled: Boolean, fromUserToggle: Boolean) {
        val disable = !enabled
        if (preferences.safetyCoreDisabled == disable && !fromUserToggle) return
        preferences.safetyCoreDisabled = disable

        if (enabled) {
            if (safetyCoreCoordinator == null) {
                initSafetyCore()
            } else {
                safetyCoreCoordinator?.start()
                AppState.setSafetyCoreRunning(true)
            }
            Log.i(TAG, "SafetyCore enabled")
        } else {
            safetyCoreCoordinator?.release()
            safetyCoreCoordinator = null
            AppState.setSafetyCoreRunning(false)
            Log.i(TAG, "SafetyCore disabled")
        }
    }



    private suspend fun initAgentCore() {
        AppState.setAgentCoreHealth(AppState.ComponentHealth.INITIALIZING)
        val configPath = preferences.llmConfigPath
        val apiKey = preferences.cloudApiKey

        if (configPath.isEmpty()) {
            Log.w(TAG, "No LLM config path, AgentCore unavailable")
            AppState.setAgentCoreHealth(AppState.ComponentHealth.DEGRADED)
            return
        }

        val modelFile = File(configPath)
        if (!modelFile.exists() || !modelFile.canRead()) {
            Log.w(TAG, "Model file not accessible: $configPath")
            AppState.setAgentCoreHealth(AppState.ComponentHealth.DEGRADED)
            return
        }

        val hasCloudKey = apiKey.isNotEmpty()
        val visionAdapter = if (hasCloudKey) {
            CloudChatAdapter(
                apiKey = apiKey,
                model = "qwen3-vl-flash",
                baseSystemPrompt = CloudChatAdapter.VISION_TTS_PROMPT
            )
        } else null

        // 初始化 Hub
        try {
            val serviceDefinitions = HubFactory.convertServiceDefinitions(
                MCPServiceConfig.toHubDefinitions(preferences.mcpServiceUrls)
            )
            val savedEnabledCombos = normalizeEnabledCombos(preferences.enabledCombos)

            // 创建带相机/截图能力的 ContextProvider
            val appContextProvider = object : AndroidContextProvider(ctx.applicationContext) {
                override fun getCameraBase64(): String? = cameraTap.getLatestBase64()
                override suspend fun getScreenshotBase64(): String? {
                    val screenshot = AccessibilityBridge.takeScreenshot() ?: return null
                    val jpegStream = java.io.ByteArrayOutputStream()
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, jpegStream)
                    return android.util.Base64.encodeToString(jpegStream.toByteArray(), android.util.Base64.NO_WRAP)
                }
            }

            hub = HubFactory.create(
                context = ctx.applicationContext,
                scope = serviceScope,
                serviceDefinitions = serviceDefinitions,
                hubConfig = HubConfig(
                    cloudCheatsheetApiKey = apiKey.takeIf { hasCloudKey },
                    cloudApiKey = apiKey.takeIf { hasCloudKey } ?: ""
                ),
                visionAdapterProvider = { visionAdapter },
                contextProviderOverride = appContextProvider
            ).also {
                val initialEnabled = if (!preferences.hasEnabledCombosConfigured()) {
                    val allCombos = it.getRegisteredCombos().map { combo -> combo.name }.toSet()
                    normalizeEnabledCombos(allCombos)
                } else {
                    savedEnabledCombos
                }
                if (initialEnabled != preferences.enabledCombos) {
                    preferences.enabledCombos = initialEnabled
                }
                AppState.setEnabledCombos(initialEnabled)
                it.initEnabledCombos(initialEnabled)
                it.setActionHandler(createComboActionHandler())
                it.setStatusBroadcastCallback { message ->
                    serviceScope.launch {
                        handleAgentEvent(AgentEvent.Status(message))
                    }
                }

                // 同步 Hub 状态到 AppState (供 UI 使用)
                syncHubStateToAppState()
                startComboConfigSync()

                // 触发服务启用与连接
                serviceScope.launch {
                    it.applyComboConfig(initialEnabled)
                    it.getConnectionManager().initializeAsync(connectAll = true)
                    syncHubStateToAppState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hub init failed", e)
        }

        // 初始化 Router
        val systemPrompt = """
            |你是视障人士的生活助手，回复简洁口语化。
            |用户想去某地时，必须先调find_nearby搜索获取location_id，
            |收到结果后询问用户去哪个，
            |用户确认后调navigate_to或call_taxi。
            |navigate_to和call_taxi的location_id只能来自find_nearby的返回结果，
            |没有调过find_nearby时不能调navigate_to或call_taxi。
            |搜索到多个结果时必须先询问用户选择，不要自动决定。
            |用户说打车/叫车→call_taxi，说导航/步行/带我去→navigate_to。
            |用户说看/前面/手里→describe_camera，说读屏/屏幕→describe_screen。
            |所有操作必须通过工具调用完成，不要用文字描述操作。
            |
            |/no_think
        """.trimMargin()

        val routerConfig = Router.Config(
            llmConfig = LlmEngine.Config(
                modelPath = modelFile.absolutePath,
                nGpuLayers = 99  // Offload all layers to GPU (OpenCL)
            ),
            systemPrompt = systemPrompt,
            maxLoops = 10,
            toolTimeoutMs = 30_000,
            injectDeferredActionResults = false
        )

        router = Router(ctx.applicationContext, routerConfig, hub)

        // 应用用户的采样参数设置
        LlmEngine.updateSamplingParams(LlmEngine.SamplingParams(
            temperature = preferences.llmTemperature,
            topP = preferences.llmTopP,
            topK = preferences.llmTopK,
            minP = preferences.llmMinP
        ))

        hub?.setOnToolsChangedListener { router?.refreshTools() }

        statsCoordinator.agentCoreStats.setBackend("llama.cpp")
        AppState.setAgentCoreHealth(AppState.ComponentHealth.READY)

        Log.i(TAG, "AgentCore initialized with Router")
    }

    private fun createComboActionHandler(): ComboActionHandler {
        return object : ComboActionHandler {
            override fun handleAction(action: ComboAction): Boolean {
                return when (action) {
                    is ComboAction.LaunchUrl -> {
                        if (suppressExternalActionsForBenchmark) {
                            Log.w(TAG, "Benchmark suppresses external action: ${action.url}")
                            return true
                        }
                        try {
                            val targetPackage = action.packageName
                            val packageManager = ctx.packageManager
                            val primaryIntent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).apply {
                                addCategory(Intent.CATEGORY_DEFAULT)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (targetPackage != null) setPackage(targetPackage)
                            }
                            val fallbackIntent = action.fallbackUrl?.let { fb ->
                                Intent(Intent.ACTION_VIEW, Uri.parse(fb)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            }

                            val launchIntent = when {
                                targetPackage != null && isAppInstalled(targetPackage) -> {
                                    primaryIntent.takeIf { it.resolveActivity(packageManager) != null }
                                        ?: fallbackIntent?.takeIf { it.resolveActivity(packageManager) != null }
                                        ?: return false
                                }
                                targetPackage != null -> {
                                    fallbackIntent?.takeIf { it.resolveActivity(packageManager) != null }
                                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$targetPackage")).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }.takeIf { it.resolveActivity(packageManager) != null }
                                        ?: return false
                                }
                                else -> {
                                    primaryIntent.takeIf { it.resolveActivity(packageManager) != null }
                                        ?: fallbackIntent?.takeIf { it.resolveActivity(packageManager) != null }
                                        ?: return false
                                }
                            }

                            mainHandler.post {
                                runCatching {
                                    ctx.startActivity(launchIntent)
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to launch URL: ${action.url}", e)
                                }
                            }
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to handle combo action", e)
                            false
                        }
                    }
                    is ComboAction.StopNavigation -> {
                        AccessibilityBridge.exitAmapNavigation()
                        true
                    }
                }
            }
        }
    }

    private fun normalizeEnabledCombos(saved: Set<String>): Set<String> {
        var result = saved + com.nihildigit.agentcore.mcp.BuiltInTools.TOOL_NAMES
        if ("navigate_to" in result && "stop_navigation" !in result) {
            result = result + "stop_navigation"
        }
        return result
    }

    /**
     * 同步 Hub 状态到 AppState (供 UI 使用)
     * 包括 MCP 连接状态和可用 Combo 列表
     */
    private fun syncHubStateToAppState() {
        try {
            hub?.let { h ->
                val connectionManager = h.getConnectionManager()
                val statuses = connectionManager.getServiceStatuses()
                val combos = h.getRegisteredCombos()
                val enabled = h.enabledCombos.value
                AppState.setMcpConnectionStatuses(statuses.map { it.toUiStatus() })
                AppState.setAvailableCombos(combos.map { it.toUiCombo() })
                AppState.setEnabledCombos(enabled)

                connectionManager.setStateListener(object : com.nihildigit.agentcore.hub.McpConnectionStateListener {
                    override fun onConnectionStateChanged(serviceId: String, state: com.nihildigit.agentcore.hub.McpConnectionState) {
                        val updated = connectionManager.getServiceStatuses()
                        AppState.setMcpConnectionStatuses(updated.map { it.toUiStatus() })
                    }
                })

                AgentLogger.normal("AgentCore", "Sync", "AppState updated",
                    "mcpServices" to statuses.size,
                    "combos" to combos.size
                )
                val failedStatuses = statuses.filter { it.enabled && it.state == com.nihildigit.agentcore.hub.McpConnectionState.FAILED }
                if (failedStatuses.isNotEmpty()) {
                    val summary = failedStatuses.joinToString(" | ") { st ->
                        "${st.serviceId}: ${st.errorMessage ?: "unknown"}"
                    }
                    Log.w(TAG, "Hub enabled services failed: $summary")
                }
                Log.i(TAG, "Hub state synced to AppState: ${statuses.size} MCP services, ${combos.size} combos")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Hub state to AppState", e)
        }
    }

    private fun startComboConfigSync() {
        comboConfigJob?.cancel()
        val currentHub = hub ?: return
        comboConfigJob = serviceScope.launch {
            AppState.enabledCombos
                .collectLatest { enabled ->
                    val normalized = normalizeEnabledCombos(enabled)
                    if (normalized != enabled) {
                        AppState.setEnabledCombos(normalized)
                        return@collectLatest
                    }
                    if (currentHub.enabledCombos.value == normalized) return@collectLatest
                    preferences.enabledCombos = normalized
                    when (val result = currentHub.applyComboConfig(normalized)) {
                        is ComboConfigResult.Failure -> Log.w(TAG, "Apply combo config failed: ${result.error}")
                        is ComboConfigResult.PartialSuccess -> Log.w(
                            TAG,
                            "Apply combo config partial: ${result.failedServices.joinToString()}"
                        )
                        else -> Unit
                    }
                    syncHubStateToAppState()
                }
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    private suspend fun initAsr() {
        AppState.setAsrHealth(AppState.ComponentHealth.INITIALIZING)
        sherpaRecognizer = SherpaRecognizer(ctx.applicationContext).apply {
            setListener(object : SherpaRecognizer.Listener {
                override fun onReady() {
                    AppState.setAsrHealth(AppState.ComponentHealth.READY)
                }

                override fun onPartialResult(text: String) {
                    AppState.updateAsrTranscription(text)
                }

                override fun onResult(text: String) {
                    AppState.clearAsrTranscription()
                    Log.i(TAG, "ASR final result: \"$text\"")
                    if (text.isNotBlank()) {
                        processUserMessage(text, inputSource = "VOICE")
                    }
                }

                override fun onError(error: String) {
                    AppState.setAsrHealth(AppState.ComponentHealth.FAILED)
                    AppState.setError(error)
                }
            })
            val initialized = initialize()
            AppState.setAsrHealth(
                if (initialized) AppState.ComponentHealth.READY else AppState.ComponentHealth.FAILED
            )
            Log.i(TAG, "ASR initialization completed: ready=$initialized")
        }
    }

    private suspend fun handleAgentEvent(event: AgentEvent, reqId: String = "") {
        when (event) {
            is AgentEvent.ToolStart -> {
                Log.d(TAG, "REQ[$reqId] ToolStart: ${event.toolName}")
                AppState.setActiveToolCall(event.toolName)
                AppState.clearAssistantResponse()
            }
            is AgentEvent.Announce -> {
                Log.i(TAG, "REQ[$reqId] Announce len=${event.text.length}")
                AppState.setActiveToolCall(null)
                AppState.updateAssistantResponse(event.text)
                AppState.setAssistantState(AppState.AssistantState.SPEAKING)
                audioManager.speakAndAwait(event.text, AudioPriorityManager.Priority.ASSISTANT)
                AppState.setAssistantState(AppState.AssistantState.THINKING)
            }
            is AgentEvent.ToolDone -> {
                Log.d(TAG, "REQ[$reqId] ToolDone: ${event.toolName}")
                AppState.setActiveToolCall(null)
            }
            is AgentEvent.Response -> {
                Log.i(TAG, "REQ[$reqId] Response len=${event.text.length} preview=\"${event.text.replace('\n', ' ').take(180)}\"")
                AppState.setActiveToolCall(null)
                AppState.updateAssistantResponse(event.text)
                AppState.setAssistantState(AppState.AssistantState.SPEAKING)
                audioManager.speak(event.text, AudioPriorityManager.Priority.ASSISTANT)
            }
            is AgentEvent.Status -> {
                Log.i(TAG, "REQ[$reqId] Status: ${event.text}")
                AppState.updateAssistantResponse(event.text)
                audioManager.speak(event.text, AudioPriorityManager.Priority.ASSISTANT)
            }
            is AgentEvent.Error -> {
                Log.e(TAG, "REQ[$reqId] Error: ${event.message}")
                AppState.setError(event.message)
                AppState.setAssistantState(AppState.AssistantState.IDLE)
            }
        }
    }

    private fun processUserMessage(text: String, inputSource: String) {
        val reqId = UUID.randomUUID().toString().take(8)
        val reqSeq = requestSequence.incrementAndGet()
        Log.i(TAG, "REQ[$reqId] process message source=$inputSource len=${text.length} text=\"$text\"")
        AppState.setAssistantState(AppState.AssistantState.THINKING)

        serviceScope.launch {
            try {
                assistantAutoClearJob?.cancel()
                val enabledCombos = AppState.enabledCombos.value
                Log.i(
                    TAG,
                    "REQ[$reqId] enabledCombos(${enabledCombos.size})=${enabledCombos.sorted().joinToString(",")}"
                )
                val result = router?.process(
                    userMessage = text,
                    onEvent = { event -> handleAgentEvent(event, reqId) }
                )

                Log.i(
                    TAG,
                    "REQ[$reqId] router result tools=${result?.toolsUsed?.joinToString(",") ?: ""} " +
                        "error=${result?.error ?: ""} respLen=${result?.response?.length ?: 0}"
                )

                // Error 已通过事件处理，这里只处理 result 级别的 error（如 router 为 null）
                if (result == null) {
                    AppState.setError("AgentCore 未初始化")
                    AppState.setAssistantState(AppState.AssistantState.IDLE)
                }

                // 会话超时自动重置：防止上下文膨胀影响推理效率
                scheduleSessionTimeout(result?.sessionTimeoutSeconds ?: -1, reqId, reqSeq)
            } catch (e: Exception) {
                Log.e(TAG, "REQ[$reqId] processUserMessage failed", e)
                AppState.setError("处理消息失败：${e.message}")
                AppState.setAssistantState(AppState.AssistantState.IDLE)
            } finally {
                cameraTap.unlockFrame()
            }
        }
    }

    private fun scheduleSessionTimeout(timeoutSeconds: Int, reqId: String, reqSeq: Long) {
        if (reqSeq != requestSequence.get()) return  // 已有更新的请求，忽略
        val effectiveTimeout = if (timeoutSeconds > 0) timeoutSeconds else 60
        assistantAutoClearJob?.cancel()
        assistantAutoClearJob = serviceScope.launch {
            kotlinx.coroutines.delay(effectiveTimeout * 1000L)
            if (reqSeq != requestSequence.get()) return@launch  // 超时期间有新请求
            Log.i(TAG, "REQ[$reqId] session timeout after ${effectiveTimeout}s, clearing history")
            router?.clearHistory()
        }
    }

    fun startSafetyCore() {
        preferences.safetyCoreDisabled = false
        val coordinator = safetyCoreCoordinator
        if (coordinator == null) {
            initSafetyCore()
        } else {
            coordinator.start()
            AppState.setSafetyCoreRunning(true)
        }
    }

    fun onTextInput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        Log.i(TAG, "Text input received: length=${trimmed.length}")
        processUserMessage(trimmed, inputSource = "TEXT")
    }

    fun stopSafetyCore() {
        preferences.safetyCoreDisabled = true
        safetyCoreCoordinator?.stop()
        AppState.setSafetyCoreRunning(false)
    }

    fun startCamera() {
        cameraCoordinator.start()
    }

    fun stopCamera() {
        cameraCoordinator.stop()
    }

    private fun onCameraFrame(bitmap: android.graphics.Bitmap, timestampNanos: Long) {
        val frameCount = cameraCoordinator.frameCount

        if (ENABLE_CAMERA_HEARTBEAT_LOG && frameCount % 60 == 0) {
            val fpStatus = if (safetyCoreCoordinator?.isActive == true) "ACTIVE" else "NULL"
            Log.d(TAG, "Camera: Heartbeat frame #$frameCount, mode: ${modeManager.currentMode.value}, fp: $fpStatus")
        }

        // Process every 2nd frame → ~15Hz at 30fps camera
        if (frameCount % 2 == 0) {
            safetyCoreCoordinator?.onFrame(bitmap, timestampNanos)
        }
        cameraTap.updateFrame(bitmap, timestampNanos)

        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastPreviewUpdateMs >= PREVIEW_UPDATE_INTERVAL_MS) {
            lastPreviewUpdateMs = nowMs
            val snapshot = cameraTap.getLatestFrame()
            if (snapshot != null) {
                AppState.setPreviewFrame(
                    AppState.PreviewFrame(
                        bitmap = snapshot.bitmap,
                        width = snapshot.width,
                        height = snapshot.height,
                        timestampMs = snapshot.timestamp / 1_000_000L
                    )
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "同行助手",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = ctx.applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(ctx, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            ctx, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(ctx, com.nihildigit.tongxing.MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            ctx, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeText = "导航助手模式 🟢"

        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("同行助手")
            .setContentText(modeText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止",
                    stopPending
                ).build()
            )
            .build()
    }

    fun updateNotification() {
        val nm = ctx.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }
}

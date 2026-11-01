package com.nihildigit.tongxing

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.Settings.Secure
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Shield
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nihildigit.tongxing.model.UiComboGroup
import com.nihildigit.tongxing.model.UiComboInfo
import com.nihildigit.tongxing.model.UiMcpConnectionState
import com.nihildigit.tongxing.model.UiMcpConnectionStatus
import com.nihildigit.tongxing.overlay.TongXingAccessibilityService
import com.nihildigit.tongxing.overlay.OverlayService
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.tongxing.util.ModelFileImporter
import com.nihildigit.tongxing.util.ModelImportManager
import com.nihildigit.tongxing.state.AppState
import com.nihildigit.tongxing.ui.components.AssistantStatus
import com.nihildigit.tongxing.ui.components.StatusItemData
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Routes {
    const val Welcome = "welcome"; const val Permissions = "permissions"; const val Config = "config"
    const val Tutorial = "tutorial"; const val Complete = "complete"; const val Dashboard = "dashboard"
    const val ToolHub = "tool_hub"; const val VoiceGuide = "voice_guide"; const val SafetyDemo = "safety_demo"
    const val Settings = "settings"; const val LlmSettings = "llm_settings"
    const val ServiceConnections = "service_connections"
}

private const val STARTUP_WARMING_TIMEOUT_MS = 30_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TongXingTheme {
                val context = LocalContext.current
                val navController = rememberNavController()
                val prefs = remember { AppPreferences(context) }
                val scope = rememberCoroutineScope()

                val enterExitSpec = tween<IntOffset>(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                val fadeSpec = tween<Float>(durationMillis = 200)

                var cameraGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
                var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
                var locationGranted by remember {
                    mutableStateOf(
                        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                            hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
                var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
                var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
                var storageGranted by remember { mutableStateOf(isStoragePermissionGranted(context)) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    cameraGranted = result[Manifest.permission.CAMERA] == true ||
                        hasPermission(context, Manifest.permission.CAMERA)
                    micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                        hasPermission(context, Manifest.permission.RECORD_AUDIO)
                    val fineLocationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    val coarseLocationGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    locationGranted = fineLocationGranted || coarseLocationGranted
                }

                val filePickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                        // ignore
                    }

                    ModelImportManager.start(scope,
                        { commit -> ModelFileImporter.importGgufFromUri(context, uri, commit) }
                    ) { imported ->
                        if (imported != null && imported.exists() && imported.canRead()) {
                            prefs.llmConfigPath = imported.absolutePath
                        }
                    }
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            overlayGranted = Settings.canDrawOverlays(context)
                            accessibilityEnabled = isAccessibilityServiceEnabled(context)
                            storageGranted = isStoragePermissionGranted(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val overlayRunning by AppState.overlayRunning.collectAsState()
                val safetyLevel by AppState.safetyLevel.collectAsState()
                val agentCoreHealth by AppState.agentCoreHealth.collectAsState()
                val safetyCoreHealth by AppState.safetyCoreHealth.collectAsState()
                val mcpStatuses by AppState.mcpConnectionStatuses.collectAsState()
                val assistantMode by AppState.assistantMode.collectAsState()
                val availableCombos by AppState.availableCombos.collectAsState()
                val enabledCombos by AppState.enabledCombos.collectAsState()

                var isStarting by remember { mutableStateOf(false) }
                val allGreenReady = remember(agentCoreHealth, safetyCoreHealth, mcpStatuses) {
                    agentCoreHealth == AppState.ComponentHealth.READY &&
                        safetyCoreHealth == AppState.ComponentHealth.READY &&
                        areMcpServicesReady(mcpStatuses)
                }
                LaunchedEffect(overlayRunning, allGreenReady) {
                    if (!overlayRunning || allGreenReady) isStarting = false
                }
                LaunchedEffect(overlayRunning, isStarting, allGreenReady) {
                    if (overlayRunning && isStarting && !allGreenReady) {
                        delay(STARTUP_WARMING_TIMEOUT_MS)
                        if (overlayRunning && !allGreenReady) {
                            isStarting = false
                        }
                    }
                }
                val assistantStatus = when {
                    overlayRunning && isStarting && !allGreenReady -> AssistantStatus.WARMING_UP
                    overlayRunning -> AssistantStatus.RUNNING
                    isStarting -> AssistantStatus.WARMING_UP
                    else -> AssistantStatus.IDLE
                }

                val statusItems = remember(agentCoreHealth, safetyCoreHealth, mcpStatuses, assistantMode) {
                    listOf(
                        componentStatusItem(context, context.getString(R.string.ai_core), agentCoreHealth, Icons.Filled.Memory),
                        componentStatusItem(context, context.getString(R.string.safety_engine), safetyCoreHealth, Icons.Filled.Shield),
                        mcpStatusItem(context, mcpStatuses, Icons.Filled.Hub),
                        modeStatusItem(context, assistantMode, Icons.Filled.NearMe)
                    )
                }

                val toolHubItems = remember(availableCombos, enabledCombos) {
                    buildToolHubItems(context, availableCombos, enabledCombos)
                }

                fun startOverlay() {
                    val intent = Intent(context, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_START
                    }
                    ContextCompat.startForegroundService(context, intent)
                }

                fun stopOverlay() {
                    val intent = Intent(context, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_STOP
                    }
                    context.startService(intent)
                }

                val startDestination = if (prefs.onboardingCompleted) Routes.Dashboard else Routes.Welcome

                androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = TongXingColors.Surface) {
                    NavHost(
                        navController = navController,
                        startDestination = startDestination,
                        enterTransition = { fadeIn(fadeSpec) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, enterExitSpec) },
                        exitTransition = { fadeOut(fadeSpec) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, enterExitSpec) },
                        popEnterTransition = { fadeIn(fadeSpec) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, enterExitSpec) },
                        popExitTransition = { fadeOut(fadeSpec) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, enterExitSpec) }
                    ) {
                        composable(Routes.Welcome) {
                            WelcomeScreenPreview { navController.navigate(Routes.Permissions) }
                        }
                        composable(Routes.Permissions) {
                            val fromDashboard = navController.previousBackStackEntry?.destination?.route == Routes.Dashboard
                            PermissionsScreenPreview(
                                onNext = {
                                    if (fromDashboard) {
                                        navController.popBackStack()
                                    } else {
                                        navController.navigate(Routes.Config)
                                    }
                                },
                                onBack = { navController.popBackStack() },
                                cameraGranted = cameraGranted,
                                micGranted = micGranted,
                                locationGranted = locationGranted,
                                overlayGranted = overlayGranted,
                                accessibilityEnabled = accessibilityEnabled,
                                storageGranted = storageGranted,
                                onRequestCamera = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
                                onRequestMic = { permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
                                onRequestLocation = {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                onRequestOverlay = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                },
                                onRequestAccessibility = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    context.startActivity(intent)
                                },
                                onRequestStorage = {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        context.startActivity(intent)
                                    } else {
                                        permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                                    }
                                }
                            )
                        }
                        composable(Routes.Config) {
                            ConfigScreenPreview(
                                onNext = { navController.navigate(Routes.Tutorial) },
                                onBack = { navController.popBackStack() },
                                onDownloadModel = {
                                    com.nihildigit.tongxing.download.ModelDownloadWorker.enqueue(
                                        context = context,
                                        modelId = "qwen3-1.7b-q4_0",
                                        modelUrl = "https://modelscope.cn/models/nihildigit/Qwen3-1.7B-GGUF-Q4_0-Pure/resolve/master/Qwen3-1.7B-Q4_0-pure.gguf"
                                    )
                                },
                                onSelectModel = { filePickerLauncher.launch(arrayOf("*/*")) },
                                onModelDownloaded = { path -> prefs.llmConfigPath = path }
                            )
                        }
                        composable(Routes.Tutorial) {
                            TutorialScreenPreview({ navController.navigate(Routes.Complete) }, { navController.popBackStack() })
                        }
                        composable(Routes.Complete) {
                            CompleteScreenPreview {
                                prefs.onboardingCompleted = true
                                prefs.onboardingStepRoute = ""
                                navController.navigate(Routes.Dashboard) { popUpTo(Routes.Welcome) { inclusive = true } }
                            }
                        }
                        composable(Routes.Dashboard) {
                            val allPermissionsGranted = cameraGranted && micGranted && locationGranted &&
                                overlayGranted && accessibilityEnabled && storageGranted
                            DashboardScreenPreview(
                                onOpenPermissions = { navController.navigate(Routes.Permissions) },
                                onOpenTools = { navController.navigate(Routes.ToolHub) },
                                onOpenHistory = { navController.navigate(Routes.VoiceGuide) },
                                onOpenSafety = { navController.navigate(Routes.SafetyDemo) },
                                onOpenSettings = { navController.navigate(Routes.Settings) },
                                assistantStatus = assistantStatus,
                                statusItems = statusItems,
                                allPermissionsGranted = allPermissionsGranted,
                                onToggleAssistant = {
                                    if (overlayRunning) {
                                        stopOverlay()
                                    } else {
                                        isStarting = true
                                        startOverlay()
                                    }
                                }
                            )
                        }
                        composable(Routes.ToolHub) {
                            ToolHubScreenPreview(
                                onBack = { navController.popBackStack() },
                                items = toolHubItems,
                                onToggle = { item, enabled ->
                                    val updated = enabledCombos.toMutableSet()
                                    // 获取该提供商下的所有工具
                                    val toolsInProvider = availableCombos.filter { it.providerId == item.providerId }
                                    toolsInProvider.forEach { tool ->
                                        if (enabled) {
                                            updated.add(tool.name)
                                        } else {
                                            updated.remove(tool.name)
                                        }
                                    }
                                    prefs.enabledCombos = updated
                                    AppState.setEnabledCombos(updated)
                                }
                            )
                        }
                        composable(Routes.VoiceGuide) { VoiceGuideScreenPreview { navController.popBackStack() } }
                        composable(Routes.SafetyDemo) {
                            SafetyDemoScreenPreviewV2(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Routes.Settings) {
                            SettingsScreenPreview(
                                onBack = { navController.popBackStack() },
                                speechRate = prefs.ttsSpeechRate,
                                pitch = prefs.ttsPitch,
                                vibrationEnabled = prefs.vibrationEnabled,
                                safetyCoreEnabled = !prefs.safetyCoreDisabled,
                                onSpeechRateChange = { prefs.ttsSpeechRate = it },
                                onPitchChange = { prefs.ttsPitch = it },
                                onVibrationEnabledChange = { prefs.vibrationEnabled = it },
                                onSafetyCoreEnabledChange = { enabled ->
                                    prefs.safetyCoreDisabled = !enabled
                                    if (overlayRunning) {
                                        val intent = Intent(context, OverlayService::class.java).apply {
                                            action = OverlayService.ACTION_SET_SAFETY_CORE_ENABLED
                                            putExtra(OverlayService.EXTRA_SAFETY_CORE_ENABLED, enabled)
                                        }
                                        context.startService(intent)
                                    }
                                },
                                onOpenLlmSettings = { navController.navigate(Routes.LlmSettings) },
                                onOpenServiceConnections = { navController.navigate(Routes.ServiceConnections) }
                            )
                        }
                        composable(Routes.LlmSettings) {
                            com.nihildigit.tongxing.ui.screen.LlmSettingsScreen(
                                preferences = prefs,
                                onBack = { navController.popBackStack() },
                                onConfigChanged = { /* 可以在这里触发重新加载模型 */ }
                            )
                        }
                        composable(Routes.ServiceConnections) {
                            com.nihildigit.tongxing.ui.screen.ServiceConnectionsScreen(
                                preferences = prefs,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun hasPermission(context: android.content.Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val enabled = Secure.getString(
        context.contentResolver,
        Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val packageName = context.packageName
    val full = "$packageName/${TongXingAccessibilityService::class.java.name}"
    val short = "$packageName/.overlay.TongXingAccessibilityService"
    return enabled.split(':').any { item ->
        item.equals(full, ignoreCase = true) || item.equals(short, ignoreCase = true)
    }
}

private fun isStoragePermissionGranted(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

private fun componentStatusItem(
    context: android.content.Context,
    label: String,
    health: AppState.ComponentHealth,
    icon: androidx.compose.ui.graphics.vector.ImageVector
): StatusItemData {
    val (value, color, failed) = when (health) {
        AppState.ComponentHealth.READY -> Triple(context.getString(R.string.ready), TongXingColors.Safe, false)
        AppState.ComponentHealth.INITIALIZING -> Triple(context.getString(R.string.initializing), TongXingColors.Warning, false)
        AppState.ComponentHealth.DEGRADED -> Triple(context.getString(R.string.degraded), TongXingColors.Caution, false)
        AppState.ComponentHealth.FAILED -> Triple(context.getString(R.string.failed), TongXingColors.Critical, true)
        AppState.ComponentHealth.UNKNOWN -> Triple(context.getString(R.string.unknown), TongXingColors.Outline, false)
    }
    return StatusItemData(label, value, icon, color, isFailed = failed)
}

private fun mcpStatusItem(
    context: android.content.Context,
    statuses: List<UiMcpConnectionStatus>,
    icon: androidx.compose.ui.graphics.vector.ImageVector
): StatusItemData {
    if (statuses.isEmpty()) {
        return StatusItemData(context.getString(R.string.mcp_services), context.getString(R.string.unknown), icon, TongXingColors.Outline)
    }
    val enabled = statuses.filter { it.enabled }
    if (enabled.isEmpty()) {
        return StatusItemData(context.getString(R.string.mcp_services), context.getString(R.string.disabled), icon, TongXingColors.Outline)
    }
    val hasFailed = enabled.any { it.state == UiMcpConnectionState.FAILED }
    val hasConnecting = enabled.any { it.state == UiMcpConnectionState.CONNECTING }
    val allConnected = enabled.all { it.state == UiMcpConnectionState.CONNECTED }
    return when {
        hasFailed -> StatusItemData(context.getString(R.string.mcp_services), context.getString(R.string.failed), icon, TongXingColors.Critical, isFailed = true)
        hasConnecting -> StatusItemData(context.getString(R.string.mcp_services), context.getString(R.string.connecting), icon, TongXingColors.Caution)
        allConnected -> StatusItemData(context.getString(R.string.mcp_services), context.getString(R.string.connected), icon, TongXingColors.Safe)
        else -> StatusItemData(context.getString(R.string.mcp_services), context.getString(R.string.disconnected), icon, TongXingColors.Warning)
    }
}

private fun areMcpServicesReady(statuses: List<UiMcpConnectionStatus>): Boolean {
    if (statuses.isEmpty()) return false
    val enabled = statuses.filter { it.enabled }
    if (enabled.isEmpty()) return false
    return enabled.all { it.state == UiMcpConnectionState.CONNECTED }
}

private fun modeStatusItem(
    context: android.content.Context,
    mode: com.nihildigit.tongxing.overlay.AssistantMode,
    icon: androidx.compose.ui.graphics.vector.ImageVector
): StatusItemData {
    // 仅保留导航模式
    return StatusItemData(context.getString(R.string.mode), context.getString(R.string.navigation), icon, TongXingColors.Secondary)
}

private fun buildToolHubItems(
    context: android.content.Context,
    combos: List<UiComboInfo>,
    enabledCombos: Set<String>
): List<ToolHubItem> {
    // 过滤掉没有提供商的工具（这类工具如 stop_navigation 将始终开启）
    // 按提供商 ID 分组
    val providerGroups = combos
        .filter { it.providerId != null }
        .groupBy { it.providerId!! }

    return providerGroups.map { (providerId, tools) ->
        val firstTool = tools.first()
        // 获取提供商的友好名称
        val providerName = when (providerId) {
            "amap-maps" -> context.getString(R.string.provider_amap)
            "12306-mcp" -> context.getString(R.string.provider_train)
            "variflight-mcp" -> "飞常准航班"
            "didi-sandbox" -> context.getString(R.string.provider_taxi)
            else -> providerId
        }

        val providerDesc = when (providerId) {
            "amap-maps" -> context.getString(R.string.amap_help)
            "12306-mcp" -> context.getString(R.string.train_help)
            "variflight-mcp" -> context.getString(R.string.flights_help)
            "didi-sandbox" -> context.getString(R.string.taxi_help)
            else -> tools.joinToString("、") { it.displayName }
        }

        ToolHubItem(
            id = providerId, // 使用提供商 ID 作为标识
            providerId = providerId,
            name = providerName,
            description = providerDesc,
            icon = comboIcon(firstTool.group),
            // 如果该提供商下有任一工具已启用，则视为该提供商已启用
            enabled = tools.any { enabledCombos.contains(it.name) || it.isEnabled }
        )
    }.sortedBy { it.name }
}

private fun comboIcon(group: UiComboGroup): androidx.compose.ui.graphics.vector.ImageVector {
    return when (group) {
        UiComboGroup.TRAVEL -> Icons.Filled.Map
        UiComboGroup.TAXI -> Icons.Filled.LocalTaxi
        UiComboGroup.LIFE -> Icons.Filled.AutoAwesome
        UiComboGroup.INFO -> Icons.Filled.Newspaper
        UiComboGroup.OTHER -> Icons.Filled.Cloud
    }
}

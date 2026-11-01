package com.nihildigit.tongxing.ui.screen.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nihildigit.tongxing.overlay.TongXingAccessibilityService
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.tongxing.ui.components.GlassSurface
import com.nihildigit.tongxing.ui.components.StatusBadge
import com.nihildigit.tongxing.ui.theme.TongXingColors
import kotlinx.coroutines.launch

@Composable
fun GuidedOnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context.applicationContext) }
    val initialStep = remember { OnboardingStep.fromRoute(prefs.onboardingStepRoute) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentStep = OnboardingStep.fromRoute(backStackEntry?.destination?.route)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentStep) {
        prefs.onboardingStepRoute = currentStep.route
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = initialStep.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            composable(OnboardingStep.Welcome.route) {
                OnboardingScaffold(
                    step = OnboardingStep.Welcome,
                    canGoBack = false,
                    canGoNext = true,
                    nextLabel = "开始",
                    onBack = { navController.popBackStack() },
                    onNext = { navController.navigate(OnboardingStep.Permissions.route) }
                ) {
                    WelcomePage()
                }
            }

            composable(OnboardingStep.Permissions.route) {
                val permissionsState = rememberPermissionsState(context)
                OnboardingScaffold(
                    step = OnboardingStep.Permissions,
                    canGoBack = true,
                    canGoNext = permissionsState.requiredReady,
                    nextLabel = "下一步",
                    onBack = { navController.popBackStack() },
                    onNext = { navController.navigate(OnboardingStep.Tutorial.route) }
                ) {
                    PermissionsPage(
                        state = permissionsState,
                        snackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                    )
                }
            }

            composable(OnboardingStep.Tutorial.route) {
                OnboardingScaffold(
                    step = OnboardingStep.Tutorial,
                    canGoBack = true,
                    canGoNext = true,
                    nextLabel = "我已了解",
                    onBack = { navController.popBackStack() },
                    onNext = { navController.navigate(OnboardingStep.Complete.route) }
                ) {
                    TutorialPage()
                }
            }

            composable(OnboardingStep.Complete.route) {
                val permissionsState = rememberPermissionsState(context)
                OnboardingScaffold(
                    step = OnboardingStep.Complete,
                    canGoBack = true,
                    canGoNext = permissionsState.requiredReady,
                    nextLabel = "开始使用",
                    onBack = { navController.popBackStack() },
                    onNext = {
                        prefs.onboardingStepRoute = ""
                        onComplete()
                    }
                ) {
                    CompletePage(permissionsState = permissionsState)
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "welcome_alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.96f,
        animationSpec = tween(durationMillis = 450),
        label = "welcome_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OnboardingTitle("欢迎使用「同行」")
        Text(
            text = "与你同行，安心向前",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .alpha(alpha),
            containerColor = TongXingColors.Digital.Glow
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureRow(icon = Icons.Filled.Shield, title = "实时避障", desc = "本地化实时避障引擎，优先保障安全")
                FeatureRow(icon = Icons.Filled.Mic, title = "语音助手", desc = "免手语音交互，支持工具调用")
                FeatureRow(icon = Icons.Filled.Settings, title = "悬浮球交互", desc = "常驻状态提示，随时呼出")
            }
        }
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics { contentDescription = "$title：$desc" }
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = TongXingColors.Primary, modifier = Modifier.size(28.dp))
        Column {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(text = desc, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class PermissionsState(
    val requiredReady: Boolean,
    val cameraGranted: Boolean,
    val overlayGranted: Boolean,
    val micGranted: Boolean,
    val locationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val requestRuntimePermissions: () -> Unit,
    val requestOverlayPermission: () -> Unit,
    val openAccessibilitySettings: () -> Unit,
    val requestBackgroundLocation: () -> Unit
)

@Composable
private fun rememberPermissionsState(context: Context): PermissionsState {
    val activity = context as? Activity

    var cameraGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var locationGranted by remember {
        mutableStateOf(
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }
    var backgroundLocationGranted by remember {
        mutableStateOf(hasBackgroundLocationPermission(context))
    }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        cameraGranted = results[Manifest.permission.CAMERA] == true || hasPermission(context, Manifest.permission.CAMERA)
        micGranted = results[Manifest.permission.RECORD_AUDIO] == true || hasPermission(context, Manifest.permission.RECORD_AUDIO)
        val fine = results[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        locationGranted = fine || coarse
        backgroundLocationGranted = hasBackgroundLocationPermission(context)
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        backgroundLocationGranted = hasBackgroundLocationPermission(context)
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
        accessibilityEnabled = isAccessibilityServiceEnabled(context)
        backgroundLocationGranted = hasBackgroundLocationPermission(context)
    }

    val requestRuntimePermissions = {
        runtimeLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    val requestOverlayPermission = {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        if (activity != null) settingsLauncher.launch(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val openAppSettings = {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        if (activity != null) settingsLauncher.launch(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val openAccessibilitySettings = {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (activity != null) settingsLauncher.launch(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val requiredReady = cameraGranted &&
        micGranted &&
        overlayGranted &&
        locationGranted &&
        backgroundLocationGranted

    val requestBackgroundLocation = {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            backgroundLocationGranted = true
        } else if (!locationGranted) {
            requestRuntimePermissions()
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ typically requires user to grant in Settings
            openAppSettings()
        } else {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    return PermissionsState(
        requiredReady = requiredReady,
        cameraGranted = cameraGranted,
        overlayGranted = overlayGranted,
        micGranted = micGranted,
        locationGranted = locationGranted,
        backgroundLocationGranted = backgroundLocationGranted,
        accessibilityEnabled = accessibilityEnabled,
        requestRuntimePermissions = requestRuntimePermissions,
        requestOverlayPermission = requestOverlayPermission,
        openAccessibilitySettings = openAccessibilitySettings,
        requestBackgroundLocation = requestBackgroundLocation
    )
}

@Composable
private fun PermissionsPage(
    state: PermissionsState,
    snackbar: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OnboardingTitle("权限设置")
        Text("我们会在这里集中完成权限说明与授权。必需权限未授权将无法继续。悬浮/后台能力需要前台服务常驻。")

        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionRow("相机（必需）", "用于环境感知与避障（需前台服务常驻）", state.cameraGranted)
                PermissionRow("麦克风（必需）", "用于语音输入（需前台服务常驻）", state.micGranted)
                PermissionRow("位置（必需）", "用于导航和天气服务", state.locationGranted)
                PermissionRow("位置（后台必需）", "悬浮/后台定位与周边服务", state.backgroundLocationGranted)
                PermissionRow("悬浮窗（必需）", "用于显示悬浮球助手", state.overlayGranted)
                PermissionRow("无障碍服务（推荐）", "用于屏幕描述和自动操作", state.accessibilityEnabled)
            }
        }

        Button(
            onClick = {
                state.requestRuntimePermissions()
                state.requestBackgroundLocation()
                state.requestOverlayPermission()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("授权全部") }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = state.requestRuntimePermissions, modifier = Modifier.weight(1f)) { Text("授权基础权限") }
            OutlinedButton(onClick = state.requestOverlayPermission, modifier = Modifier.weight(1f)) { Text("授权悬浮窗") }
        }
        OutlinedButton(onClick = state.requestBackgroundLocation, modifier = Modifier.fillMaxWidth()) { Text("授权后台位置") }
        OutlinedButton(onClick = state.openAccessibilitySettings, modifier = Modifier.fillMaxWidth()) { Text("打开无障碍设置") }

        if (!state.requiredReady) {
            StatusBadge("需要完成必需权限", Icons.Filled.Warning, TongXingColors.Warning, contentDescription = "必需权限未完成，无法继续")
        } else {
            StatusBadge("必需权限已就绪", Icons.Filled.CheckCircle, TongXingColors.Safe)
        }

        LaunchedEffect(state.requiredReady) {
            if (!state.requiredReady) snackbar("必需权限：相机/麦克风/位置/后台位置/悬浮窗")
        }
    }
}

@Composable
private fun PermissionRow(title: String, desc: String, granted: Boolean) {
    val color = if (granted) TongXingColors.Safe else MaterialTheme.colorScheme.outline
    val icon = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Info
    val statusText = if (granted) "已授权" else "未授权"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics { contentDescription = "$title，状态：$statusText，$desc" }
    ) {
        Icon(imageVector = icon, contentDescription = statusText, tint = color, modifier = Modifier.size(24.dp))
        Column {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(text = desc, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TutorialPage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OnboardingTitle("快速教学")
        Text("核心交互方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureRow(icon = Icons.Filled.Mic, title = "双击耳机按键", desc = "开始/停止语音输入")
            }
        }
        Text(
            text = "试用提示：你可以对着耳机说“你好”测试语音助手；也可以稍后在悬浮球中开始。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompletePage(
    permissionsState: PermissionsState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OnboardingTitle("设置完成")
        Text("太好了！现在可以开始使用「同行」。", style = MaterialTheme.typography.titleLarge)

        Text("权限摘要", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionRow("相机", "环境感知与避障", permissionsState.cameraGranted)
                PermissionRow("悬浮窗", "悬浮球助手显示", permissionsState.overlayGranted)
                PermissionRow("麦克风", "语音输入", permissionsState.micGranted)
                PermissionRow("位置", "导航与天气", permissionsState.locationGranted)
                PermissionRow("无障碍服务", "屏幕描述与自动操作", permissionsState.accessibilityEnabled)
            }
        }

        if (!permissionsState.requiredReady) {
            StatusBadge("必需权限未完成：相机/麦克风/位置/后台位置/悬浮窗", Icons.Filled.Warning, TongXingColors.Critical)
        } else {
            StatusBadge("权限已就绪", Icons.Filled.CheckCircle, TongXingColors.Safe)
        }

        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("模型配置提示", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = "你可以稍后在「设置 → LLM 配置」中下载或选择本地模型文件。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        permission
    ) == PackageManager.PERMISSION_GRANTED
}

private fun hasBackgroundLocationPermission(context: Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return true
    return hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val packageName = context.packageName
    val full = "$packageName/${TongXingAccessibilityService::class.java.name}"
    val short = "$packageName/.overlay.TongXingAccessibilityService"
    return enabled.split(':').any { item ->
        item.equals(full, ignoreCase = true) || item.equals(short, ignoreCase = true)
    }
}

package com.nihildigit.tongxing.ui.screen.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.tongxing.util.ModelFileImporter
import com.nihildigit.tongxing.util.ModelImportManager
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingDimens
import com.nihildigit.tongxing.ui.theme.TongXingTheme
import kotlinx.coroutines.launch

@Composable
fun QuickSetupScreen(
    onComplete: (configPath: String, apiKey: String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context.applicationContext) }

    var currentStep by remember { mutableIntStateOf(0) }
    var configPath by remember { mutableStateOf(prefs.llmConfigPath) }
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    val steps = listOf(
        QuickSetupStep(
            title = "欢迎使用同行",
            subtitle = "AI 视觉助手，为视障用户保驾护航",
            icon = Icons.Filled.PlayArrow
        ),
        QuickSetupStep(
            title = "权限设置",
            subtitle = "需要相机、麦克风和位置权限",
            icon = Icons.Filled.Security
        ),
        QuickSetupStep(
            title = "模型配置",
            subtitle = "选择本地模型或云端 API Key",
            icon = Icons.Filled.Mic
        ),
        QuickSetupStep(
            title = "准备就绪",
            subtitle = "开始使用同行",
            icon = Icons.Filled.Check
        )
    )

    TongXingTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(TongXingDimens.SpaceXl)
            ) {
                StepProgressIndicator(
                    currentStep = currentStep,
                    totalSteps = steps.size,
                    onStepClick = { if (it < currentStep) currentStep = it }
                )

                Spacer(modifier = Modifier.height(TongXingDimens.SpaceXl))

                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + scaleIn(initialScale = 0.95f),
                    exit = fadeOut() + scaleOut()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        StepContent(
                            step = steps[currentStep],
                            stepIndex = currentStep,
                            configPath = configPath,
                            apiKey = apiKey,
                            showApiKey = showApiKey,
                            onConfigPathChange = { configPath = it },
                            onApiKeyChange = { apiKey = it },
                            onShowApiKeyChange = { showApiKey = it },
                            onRequestPermissions = { requestAllPermissions(context) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(TongXingDimens.SpaceL))

                NavigationButtons(
                    currentStep = currentStep,
                    totalSteps = steps.size,
                    canGoNext = canProceed(currentStep, configPath, apiKey, context),
                    onBack = { if (currentStep > 0) currentStep-- },
                    onNext = {
                        if (currentStep < steps.size - 1) {
                            currentStep++
                        } else {
                            prefs.llmConfigPath = configPath
                            if (apiKey.isNotBlank()) {
                                prefs.cloudApiKey = apiKey
                            }
                            onComplete(configPath, apiKey)
                        }
                    }
                )
            }
        }
    }
}

private data class QuickSetupStep(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

@Composable
private fun StepProgressIndicator(
    currentStep: Int,
    totalSteps: Int,
    onStepClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            val isCompleted = index < currentStep
            val isCurrent = index == currentStep

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .semantics { contentDescription = "步骤 ${index + 1}: ${if (isCompleted) "已完成" else if (isCurrent) "当前步骤" else "未完成"}" }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                isCompleted -> MaterialTheme.colorScheme.primary
                                isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            }
                        )
                        .clickable(enabled = index < currentStep) { onStepClick(index) }
                )
            }
        }
    }
}

@Composable
private fun StepContent(
    step: QuickSetupStep,
    stepIndex: Int,
    configPath: String,
    apiKey: String,
    showApiKey: Boolean,
    onConfigPathChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onShowApiKeyChange: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = step.icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(TongXingDimens.SpaceL))

        Text(
            text = step.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(TongXingDimens.SpaceS))

        Text(
            text = step.subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(TongXingDimens.SpaceXl))

        when (stepIndex) {
            0 -> WelcomeContent()
            1 -> PermissionsContent(onRequestPermissions = onRequestPermissions)
            2 -> ConfigContent(
                configPath = configPath,
                apiKey = apiKey,
                showApiKey = showApiKey,
                onConfigPathChange = onConfigPathChange,
                onApiKeyChange = onApiKeyChange,
                onShowApiKeyChange = onShowApiKeyChange
            )
            3 -> ReadyContent(configPath = configPath, apiKey = apiKey)
        }
    }
}

@Composable
private fun WelcomeContent() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TongXingColors.Digital.Glow)
    ) {
        Column(
            modifier = Modifier.padding(TongXingDimens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceM)
        ) {
            FeatureItem("实时避障", "本地化安全引擎，保障出行安全")
            FeatureItem("语音交互", "免手操作，通过语音指令完成任务")
            FeatureItem("智能助手", "AI 问答、信息查询、工具调用")
        }
    }
}

@Composable
private fun FeatureItem(title: String, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceM)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Column {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = desc,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun PermissionsContent(onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val permissions = listOf(
        Triple(Manifest.permission.CAMERA, "相机", "用于环境感知与避障"),
        Triple(Manifest.permission.RECORD_AUDIO, "麦克风", "用于语音输入"),
        Triple(Manifest.permission.ACCESS_FINE_LOCATION, "位置", "用于导航和天气服务")
    )

    Column(verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceM)) {
        permissions.forEach { (permission, title, desc) ->
            PermissionCard(
                title = title,
                desc = desc,
                granted = hasPermission(context, permission)
            )
        }

        Spacer(modifier = Modifier.height(TongXingDimens.SpaceM))

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("授予权限")
        }

        Text(
            text = "部分权限可能需要在系统设置中手动开启",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    desc: String,
    granted: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                TongXingColors.Safe.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TongXingDimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (granted) TongXingColors.Safe.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (granted) TongXingColors.Safe else MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.width(TongXingDimens.SpaceM))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = desc,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun ConfigContent(
    configPath: String,
    apiKey: String,
    showApiKey: Boolean,
    onConfigPathChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onShowApiKeyChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
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
                onConfigPathChange(imported.absolutePath)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceM)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TongXingColors.Navigation.Glow)
        ) {
            Column(
                modifier = Modifier.padding(TongXingDimens.SpaceM),
                verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceS)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(TongXingDimens.SpaceS))
                    Text(
                        text = "云端 API Key（推荐）",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("输入 API Key") },
                    leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { onShowApiKeyChange(!showApiKey) }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Key,
                                contentDescription = if (showApiKey) "隐藏" else "显示"
                            )
                        }
                    },
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    text = "配置 Key 后可启用云端视觉能力。无 Key 也能使用基础功能。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(TongXingDimens.SpaceM),
                verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceS)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(TongXingDimens.SpaceS))
                    Text(
                        text = "本地模型路径",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = if (configPath.isNotBlank()) {
                        "已配置: ${configPath.takeLast(50)}"
                    } else {
                        "暂未配置本地模型"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (configPath.isNotBlank()) {
                        TongXingColors.Safe
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                OutlinedButton(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (configPath.isNotBlank()) "重新选择" else "选择 GGUF 模型文件")
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(configPath: String, apiKey: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceM)
    ) {
        Surface(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .border(3.dp, TongXingColors.Safe, CircleShape),
            color = TongXingColors.Safe.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = TongXingColors.Safe
                )
            }
        }

        Spacer(modifier = Modifier.height(TongXingDimens.SpaceM))

        Text(
            text = "配置完成！",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(TongXingDimens.SpaceM),
                verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceS)
            ) {
                RowItem("云端 API Key", apiKey.isNotBlank())
                RowItem("本地模型", configPath.isNotBlank())
            }
        }

        Text(
            text = "点击「开始使用」即可启动同行",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RowItem(label: String, configured: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label)
        Icon(
            imageVector = if (configured) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (configured) TongXingColors.Safe else MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun NavigationButtons(
    currentStep: Int,
    totalSteps: Int,
    canGoNext: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (currentStep > 0) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text("上一步")
            }

            Spacer(modifier = Modifier.width(TongXingDimens.SpaceM))
        }

        Button(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.weight(if (currentStep > 0) 1f else 1f)
        ) {
            Text(if (currentStep == totalSteps - 1) "开始使用" else "下一步")
            Spacer(modifier = Modifier.width(TongXingDimens.SpaceS))
            Icon(
                imageVector = if (currentStep == totalSteps - 1) Icons.Filled.PlayArrow else Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null
            )
        }
    }
}

private fun canProceed(step: Int, configPath: String, apiKey: String, context: Context): Boolean {
    return when (step) {
        0 -> true
        1 -> hasPermission(context, Manifest.permission.CAMERA) &&
                hasPermission(context, Manifest.permission.RECORD_AUDIO) &&
                hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        2 -> configPath.isNotBlank() || apiKey.isNotBlank()
        3 -> true
        else -> false
    }
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        permission
    ) == PackageManager.PERMISSION_GRANTED
}

private fun requestAllPermissions(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

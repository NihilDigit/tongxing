package com.nihildigit.tongxing

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nihildigit.tongxing.download.ModelDownloadWorker
import com.nihildigit.tongxing.ui.components.AssistantOrb
import com.nihildigit.tongxing.ui.components.GlassSurface
import com.nihildigit.tongxing.ui.components.OnboardingPageScaffold
import com.nihildigit.tongxing.ui.components.OrbState
import com.nihildigit.tongxing.ui.components.PermissionEntry
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingDimens
import kotlinx.coroutines.delay

@Composable
fun WelcomeScreenPreview(onNext: () -> Unit) {
    OnboardingPageScaffold(
        title = stringResource(R.string.welcome_title),
        subtitle = stringResource(R.string.welcome_subtitle),
        currentStep = 0,
        onNext = onNext,
        onBack = {}
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            AssistantOrb(OrbState.IDLE)
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.core_features), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Column(modifier = Modifier.padding(start = 8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.dp)) {
                FeatureItem(Icons.Filled.Shield, stringResource(R.string.real_time_sensing), stringResource(R.string.local_sensing))
                FeatureItem(Icons.Filled.Mic, stringResource(R.string.voice_assistant), stringResource(R.string.voice_tools))
                FeatureItem(Icons.Filled.Settings, stringResource(R.string.floating_assistant), stringResource(R.string.visible_status))
            }
        }
    }
}

@Composable
fun FeatureItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {}
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(TongXingColors.Primary.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = TongXingColors.Primary, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PermissionsScreenPreview(
    onNext: () -> Unit,
    onBack: () -> Unit,
    cameraGranted: Boolean = false,
    micGranted: Boolean = false,
    locationGranted: Boolean = false,
    overlayGranted: Boolean = false,
    accessibilityEnabled: Boolean = false,
    storageGranted: Boolean = false,
    onRequestCamera: () -> Unit = {},
    onRequestMic: () -> Unit = {},
    onRequestLocation: () -> Unit = {},
    onRequestOverlay: () -> Unit = {},
    onRequestAccessibility: () -> Unit = {},
    onRequestStorage: () -> Unit = {}
) {
    OnboardingPageScaffold(
        title = stringResource(R.string.permissions_title),
        subtitle = stringResource(R.string.permissions_subtitle),
        currentStep = 1,
        onNext = onNext,
        onBack = onBack
    ) {
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            PermissionEntry(stringResource(R.string.camera_permission), stringResource(R.string.camera_permission_help), cameraGranted) { onRequestCamera() }
            PermissionEntry(stringResource(R.string.microphone_permission), stringResource(R.string.microphone_permission_help), micGranted) { onRequestMic() }
            PermissionEntry(stringResource(R.string.location_permission), stringResource(R.string.location_permission_help), locationGranted) { onRequestLocation() }
            PermissionEntry(stringResource(R.string.overlay_permission), stringResource(R.string.overlay_permission_help), overlayGranted) { onRequestOverlay() }
            PermissionEntry(stringResource(R.string.accessibility_permission), stringResource(R.string.accessibility_permission_help), accessibilityEnabled) { onRequestAccessibility() }
            PermissionEntry(stringResource(R.string.storage_permission), stringResource(R.string.storage_permission_help), storageGranted) { onRequestStorage() }
        }
    }
}

@Composable
fun ConfigScreenPreview(
    onNext: () -> Unit,
    onBack: () -> Unit,
    onDownloadModel: () -> Unit = {},
    onSelectModel: () -> Unit = {},
    onModelDownloaded: (path: String) -> Unit = {}
) {
    val context = LocalContext.current
    val modelId = "qwen3-1.7b-q4_0"
    val workManager = remember { WorkManager.getInstance(context) }

    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadProgress by remember { mutableIntStateOf(0) }

    LaunchedEffect(downloadState) {
        if (downloadState !is DownloadState.Downloading) return@LaunchedEffect
        while (downloadState is DownloadState.Downloading) {
            try {
                val workInfos = workManager.getWorkInfosForUniqueWork("model_download_$modelId").get()
                when (workInfos?.firstOrNull()?.state) {
                    WorkInfo.State.RUNNING -> {
                        val p = workInfos.first().progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                        downloadProgress = p
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val outputPath = workInfos.first().outputData.getString(ModelDownloadWorker.KEY_OUTPUT_PATH)
                        if (outputPath != null) onModelDownloaded(outputPath)
                        downloadState = DownloadState.Done
                        break
                    }
                    WorkInfo.State.FAILED -> {
                        val err = workInfos.first().outputData.getString(ModelDownloadWorker.KEY_ERROR)
                        downloadState = DownloadState.Error(err ?: context.getString(R.string.download_failed))
                        break
                    }
                    WorkInfo.State.CANCELLED -> {
                        downloadState = DownloadState.Idle
                        break
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                downloadState = DownloadState.Error(e.message ?: "监听失败")
                break
            }
            delay(500)
        }
    }

    OnboardingPageScaffold(
        title = stringResource(R.string.setup_model),
        subtitle = stringResource(R.string.setup_model_help),
        currentStep = 2,
        onNext = onNext,
        onBack = onBack
    ) {
        val downloadAnnouncement = when (val state = downloadState) {
            is DownloadState.Idle -> stringResource(R.string.model_download_hint)
            is DownloadState.Downloading ->
                if (downloadProgress > 0) stringResource(R.string.download_progress, downloadProgress) else stringResource(R.string.preparing_download)
            is DownloadState.Done -> stringResource(R.string.model_download_complete)
            is DownloadState.Error -> stringResource(R.string.download_error_detail, state.message)
        }

        GlassSurface(containerColor = TongXingColors.Digital.Glow, alpha = 0.9f) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .semantics {
                        contentDescription = downloadAnnouncement
                        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                    },
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.modelscope_download), fontWeight = FontWeight.Bold)

                when (val state = downloadState) {
                    is DownloadState.Idle -> {
                        Button(
                            onClick = {
                                downloadState = DownloadState.Downloading
                                downloadProgress = 0
                                onDownloadModel()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.download_qwen)) }
                    }
                    is DownloadState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (downloadProgress > 0) stringResource(R.string.download_progress, downloadProgress) else stringResource(R.string.preparing_download),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    ModelDownloadWorker.cancel(context, modelId)
                                    downloadState = DownloadState.Idle
                                }
                            ) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                    is DownloadState.Done -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = TongXingColors.Safe, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.download_complete), color = TongXingColors.Safe, fontWeight = FontWeight.Bold)
                        }
                    }
                    is DownloadState.Error -> {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = {
                                downloadState = DownloadState.Downloading
                                downloadProgress = 0
                                onDownloadModel()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
        GlassSurface(containerColor = TongXingColors.SurfaceVariant, alpha = 0.6f) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = context.getString(R.string.import_model_hint)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) { Text(stringResource(R.string.import_model), fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onSelectModel) { Text(stringResource(R.string.select)) }
            }
        }
    }
}

private sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloading : DownloadState()
    data object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

@Composable
fun TutorialScreenPreview(onNext: () -> Unit, onBack: () -> Unit) {
    val assistantState by com.nihildigit.tongxing.state.AppState.assistantState.collectAsState()
    
    val orbState = when (assistantState) {
        com.nihildigit.tongxing.state.AppState.AssistantState.LISTENING -> OrbState.LISTENING
        com.nihildigit.tongxing.state.AppState.AssistantState.THINKING -> OrbState.THINKING
        com.nihildigit.tongxing.state.AppState.AssistantState.SPEAKING -> OrbState.SPEAKING
        else -> OrbState.IDLE
    }

    val announcement = when (assistantState) {
        com.nihildigit.tongxing.state.AppState.AssistantState.LISTENING -> stringResource(R.string.listening)
        com.nihildigit.tongxing.state.AppState.AssistantState.SPEAKING -> stringResource(R.string.speaking)
        else -> stringResource(R.string.try_headset)
    }

    OnboardingPageScaffold(
        title = stringResource(R.string.tutorial),
        subtitle = stringResource(R.string.tutorial_help),
        currentStep = 3,
        onNext = onNext,
        onBack = onBack
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(TongXingDimens.RadiusL))
                .background(TongXingColors.SurfaceVariant.copy(alpha = 0.5f))
                .semantics {
                    contentDescription = announcement
                    liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
                AssistantOrb(orbState)
                
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.headset_buttons),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.headset_double_click),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 24.sp
                    )
                }

                if (assistantState != com.nihildigit.tongxing.state.AppState.AssistantState.IDLE) {
                    Text(
                        text = stringResource(R.string.activated),
                        color = TongXingColors.Safe,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
    }
}

@Composable
fun CompleteScreenPreview(onNext: () -> Unit) {
    OnboardingPageScaffold(
        title = stringResource(R.string.setup_done),
        subtitle = stringResource(R.string.setup_done_help),
        currentStep = 4,
        onNext = onNext,
        onBack = {}
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AssistantOrb(OrbState.SPEAKING)
                Spacer(modifier = Modifier.height(24.dp))
                Text(stringResource(R.string.setup_congratulations), style = MaterialTheme.typography.headlineSmall, color = TongXingColors.Safe, fontWeight = FontWeight.Bold)
            }
        }
    }
}

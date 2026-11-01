package com.nihildigit.tongxing.ui.screen

import com.nihildigit.tongxing.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nihildigit.tongxing.download.ModelDownloadWorker
import kotlinx.coroutines.delay
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.tongxing.util.ModelFileImporter
import com.nihildigit.tongxing.util.ModelImportManager
import com.nihildigit.lightwayllama.gguf.GgufMetadataReader
import java.io.File
import kotlinx.coroutines.launch

/**
 * 预设模型配置
 */
data class PresetModel(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val sizeBytes: Long
)

val PRESET_MODELS = listOf(
    PresetModel(
        id = "qwen3-1.7b-q4_0",
        name = "Qwen3-1.7B Q4_0（推荐）",
        description = "纯 Q4_0 量化，适合大多数设备",
        url = "https://modelscope.cn/models/nihildigit/Qwen3-1.7B-GGUF-Q4_0-Pure/resolve/master/Qwen3-1.7B-Q4_0-pure.gguf",
        sizeBytes = 1_100_000_000L
    )
)

/**
 * LLM 配置设置界面
 * 
 * 允许用户选择 LLM 模型文件（GGUF）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsScreen(
    preferences: AppPreferences,
    onBack: () -> Unit,
    onConfigChanged: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    
    var currentConfigPath by remember { mutableStateOf(preferences.llmConfigPath) }
    var configStatus by remember { mutableStateOf<ConfigStatus>(ConfigStatus.Checking) }

    // 下载状态
    var downloadingModelId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    // 监听下载状态 - 使用协程轮询
    val workManager = remember { WorkManager.getInstance(context) }

    LaunchedEffect(downloadingModelId) {
        val modelId = downloadingModelId ?: return@LaunchedEffect
        while (downloadingModelId != null) {
            try {
                val workInfos = workManager.getWorkInfosForUniqueWork("model_download_$modelId").get()
                val workInfo = workInfos?.firstOrNull()
                when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                        downloadProgress = progress
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val outputPath = workInfo.outputData.getString(ModelDownloadWorker.KEY_OUTPUT_PATH)
                        if (outputPath != null) {
                            currentConfigPath = outputPath
                            preferences.llmConfigPath = outputPath
                            configStatus = checkConfigStatus(context, outputPath)
                        }
                        downloadingModelId = null
                        downloadProgress = 0
                        downloadError = null
                        break
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                        downloadError = error ?: "下载失败"
                        downloadingModelId = null
                        downloadProgress = 0
                        break
                    }
                    WorkInfo.State.CANCELLED -> {
                        downloadingModelId = null
                        downloadProgress = 0
                        break
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                downloadError = e.message ?: "监听下载状态失败"
                downloadingModelId = null
                break
            }
            delay(500) // 每 500ms 检查一次
        }
    }
    
    // 检查是否有存储管理权限
    var hasStoragePermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        )
    }
    
    // 检查配置状态
    LaunchedEffect(currentConfigPath) {
        configStatus = checkConfigStatus(context, currentConfigPath)
    }
    
    // 文件选择器（GGUF）
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // 获取持久化权限
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 可能已有权限，忽略
            }

            ModelImportManager.start(scope,
                { commit -> ModelFileImporter.importGgufFromUri(context, it, commit) }
            ) { imported ->
                if (imported != null && imported.exists() && imported.canRead()) {
                    currentConfigPath = imported.absolutePath
                    preferences.llmConfigPath = imported.absolutePath
                    onConfigChanged()
                } else {
                    configStatus = ConfigStatus.NotFound(context.getString(R.string.model_import_failed))
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.llm_settings),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 本地 LLM 配置
            Text(
                text = stringResource(R.string.local_model),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 当前配置路径
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (configStatus) {
                                is ConfigStatus.Valid -> Icons.Filled.CheckCircle
                                is ConfigStatus.NotFound -> Icons.Filled.Error
                                ConfigStatus.Checking -> Icons.Filled.HourglassEmpty
                                ConfigStatus.Empty -> Icons.Filled.FolderOpen
                            },
                            contentDescription = null,
                            tint = when (configStatus) {
                                is ConfigStatus.Valid -> MaterialTheme.colorScheme.primary
                                is ConfigStatus.NotFound -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.model_path),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (currentConfigPath.isEmpty()) "未配置" else currentConfigPath,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    // 状态提示
                    when (val status = configStatus) {
                        is ConfigStatus.Valid -> {
                            Text(
                                text = "✓ 配置有效: ${status.modelName}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is ConfigStatus.NotFound -> {
                            Text(
                                text = "✗ ${status.reason}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        ConfigStatus.Empty -> {
                            Text(
                                text = stringResource(R.string.choose_gguf),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ConfigStatus.Checking -> {
                            Text(
                                text = stringResource(R.string.checking),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // 存储权限提示（Android 11+）
                    if (!hasStoragePermission) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "需要存储管理权限",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Text(
                                    text = "请点击下方按钮，在设置中允许「所有文件访问」权限",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = "设置",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.open_settings))
                                }
                            }
                        }
                    }
                    
                    // 选择模型文件按钮
                    Button(
                        onClick = {
                            // 刷新权限状态
                            hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Environment.isExternalStorageManager()
                            } else {
                                true
                            }
                            if (hasStoragePermission) {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasStoragePermission
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.select_gguf))
                    }
                    
                    // 清除配置
                    if (currentConfigPath.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                currentConfigPath = ""
                                preferences.llmConfigPath = ""
                                configStatus = ConfigStatus.Empty
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.clear_config))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 下载模型
            Text(
                text = stringResource(R.string.download_model),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 推荐模型
                    Text(
                        text = stringResource(R.string.recommended_model),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    PRESET_MODELS.forEach { model ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SmartToy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.name,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = model.description,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        downloadError = null
                                        downloadingModelId = model.id
                                        ModelDownloadWorker.enqueue(
                                            context = context,
                                            modelId = model.id,
                                            modelUrl = model.url
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = downloadingModelId == null
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.download))
                                }
                            }
                        }
                    }


                    // 下载进度
                    if (downloadingModelId != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (downloadProgress > 0) "下载中... $downloadProgress%" else "准备下载...",
                                        fontSize = 14.sp
                                    )
                                }

                                if (downloadProgress > 0) {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        downloadingModelId?.let { modelId ->
                                            ModelDownloadWorker.cancel(context, modelId)
                                        }
                                        downloadingModelId = null
                                        downloadProgress = 0
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Cancel,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.cancel_download))
                                }
                            }
                        }
                    }

                    // 下载错误
                    downloadError?.let { error ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Error,
                                    contentDescription = "错误",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = error,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

        }
    }
}

/**
 * 配置状态
 */
sealed class ConfigStatus {
    object Empty : ConfigStatus()
    object Checking : ConfigStatus()
    data class Valid(val modelName: String) : ConfigStatus()
    data class NotFound(val reason: String) : ConfigStatus()
}

/**
 * 检查配置状态
 */
private suspend fun checkConfigStatus(context: android.content.Context, path: String): ConfigStatus {
    if (path.isEmpty()) return ConfigStatus.Empty
    
    return try {
        val file = File(path)
        if (!file.exists()) {
            ConfigStatus.NotFound(context.getString(R.string.file_missing))
        } else if (!file.canRead()) {
            ConfigStatus.NotFound(context.getString(R.string.file_unreadable))
        } else if (!file.name.lowercase().endsWith(".gguf")) {
            ConfigStatus.NotFound(context.getString(R.string.choose_gguf))
        } else {
            val modelName = extractModelName(file) ?: file.nameWithoutExtension
            ConfigStatus.Valid(modelName)
        }
    } catch (e: Exception) {
        ConfigStatus.NotFound(e.message ?: context.getString(R.string.unknown_error))
    }
}

/**
 * 从 GGUF 元数据提取模型名称
 */
private suspend fun extractModelName(file: File): String? {
    return try {
        file.inputStream().use { stream ->
            val metadata = GgufMetadataReader.create().readStructuredMetadata(stream)
            metadata.basic.name ?: metadata.basic.nameLabel
        }
    } catch (e: Exception) {
        null
    }
}

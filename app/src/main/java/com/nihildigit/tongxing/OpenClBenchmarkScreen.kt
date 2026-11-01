package com.nihildigit.tongxing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * OpenCL Benchmark Screen
 * 
 * UI for testing different backend configurations (CPU vs OpenCL)
 * and thread counts to compare inference performance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenClBenchmarkScreen(
    viewModel: OpenClBenchmarkViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val benchmarkResults by viewModel.benchmarkResults.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val currentStatus by viewModel.currentStatus.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val selectedBackends by viewModel.selectedBackends.collectAsState()
    val selectedGpuConfigs by viewModel.selectedGpuConfigs.collectAsState()
    val selectedCpuThreads by viewModel.selectedCpuThreads.collectAsState()
    val selectedPromptType by viewModel.selectedPromptType.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("OpenCL Benchmark") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(
                        onClick = { viewModel.clearResults() },
                        enabled = !isRunning && benchmarkResults.isNotEmpty()
                    ) {
                        Icon(Icons.Default.ClearAll, contentDescription = "清空结果")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Error banner
            errorMessage?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = "错误",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Configuration Panel
            item {
                ConfigurationPanel(
                    selectedBackends = selectedBackends,
                    selectedGpuConfigs = selectedGpuConfigs,
                    selectedCpuThreads = selectedCpuThreads,
                    selectedPromptType = selectedPromptType,
                    onToggleBackend = { viewModel.toggleBackend(it) },
                    onToggleGpuConfig = { viewModel.toggleGpuConfig(it) },
                    onToggleCpuThread = { viewModel.toggleCpuThread(it) },
                    onSelectPromptType = { viewModel.setPromptType(it) },
                    isEnabled = !isRunning
                )
            }

            // Control buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.startBenchmark() },
                        enabled = !isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("开始测试")
                    }

                    OutlinedButton(
                        onClick = { viewModel.stopBenchmark() },
                        enabled = isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("停止")
                    }
                }
            }

            // Progress indicator
            if (isRunning) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = currentStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Results section
            if (benchmarkResults.isNotEmpty()) {
                item {
                    Text(
                        text = "测试结果 (${benchmarkResults.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(benchmarkResults) { result ->
                    BenchmarkResultCard(result = result)
                }
            } else if (!isRunning) {
                // Empty state
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "点击「开始测试」运行 Benchmark",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${selectedPromptType.label}: ${selectedPromptType.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Configuration panel for selecting backends and thread counts
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigurationPanel(
    selectedBackends: Set<String>,
    selectedGpuConfigs: Set<Int>,
    selectedCpuThreads: Set<Int>,
    selectedPromptType: OpenClBenchmarkViewModel.Companion.PromptType,
    onToggleBackend: (String) -> Unit,
    onToggleGpuConfig: (Int) -> Unit,
    onToggleCpuThread: (Int) -> Unit,
    onSelectPromptType: (OpenClBenchmarkViewModel.Companion.PromptType) -> Unit,
    isEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "测试配置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // Backend selection
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "后端:",
                    style = MaterialTheme.typography.bodyMedium
                )

                FilterChip(
                    selected = selectedBackends.contains("cpu"),
                    onClick = { onToggleBackend("cpu") },
                    label = { Text("CPU") },
                    enabled = isEnabled,
                    leadingIcon = if (selectedBackends.contains("cpu")) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null
                )

                FilterChip(
                    selected = selectedBackends.contains("opencl"),
                    onClick = { onToggleBackend("opencl") },
                    label = { Text("OpenCL") },
                    enabled = isEnabled,
                    leadingIcon = if (selectedBackends.contains("opencl")) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null
                )
            }
            
            // Prompt type selection
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Prompt:",
                    style = MaterialTheme.typography.bodyMedium
                )
                OpenClBenchmarkViewModel.Companion.PromptType.entries.forEach { type ->
                    FilterChip(
                        selected = selectedPromptType == type,
                        onClick = { onSelectPromptType(type) },
                        label = { Text(type.label) },
                        enabled = isEnabled,
                        leadingIcon = if (selectedPromptType == type) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
            
            // Show prompt description
            Text(
                text = selectedPromptType.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // OpenCL GPU configs (only show if OpenCL is selected)
            if (selectedBackends.contains("opencl")) {
                HorizontalDivider()
                Text(
                    text = "OpenCL 配置 (numThread mask)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OpenClBenchmarkViewModel.OPENCL_CONFIGS.forEach { config ->
                        FilterChip(
                            selected = selectedGpuConfigs.contains(config.mask),
                            onClick = { onToggleGpuConfig(config.mask) },
                            label = { 
                                Text(
                                    "${config.mask}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            enabled = isEnabled,
                            leadingIcon = if (selectedGpuConfigs.contains(config.mask)) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                    }
                }
                
                // Show selected config descriptions
                if (selectedGpuConfigs.isNotEmpty()) {
                    val descriptions = selectedGpuConfigs.sorted().mapNotNull { mask ->
                        OpenClBenchmarkViewModel.OPENCL_CONFIGS.find { it.mask == mask }?.let { "${it.mask}: ${it.description}" }
                    }
                    Text(
                        text = descriptions.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // CPU thread counts (only show if CPU is selected)
            if (selectedBackends.contains("cpu")) {
                HorizontalDivider()
                Text(
                    text = "CPU 线程数",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OpenClBenchmarkViewModel.CPU_THREAD_COUNTS.forEach { count ->
                        FilterChip(
                            selected = selectedCpuThreads.contains(count),
                            onClick = { onToggleCpuThread(count) },
                            label = { Text("$count") },
                            enabled = isEnabled,
                            leadingIcon = if (selectedCpuThreads.contains(count)) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                    }
                }
            }

            // Test count summary
            val openclCount = if (selectedBackends.contains("opencl")) selectedGpuConfigs.size.coerceAtLeast(1) else 0
            val cpuCount = if (selectedBackends.contains("cpu")) selectedCpuThreads.size.coerceAtLeast(1) else 0
            val totalTests = openclCount + cpuCount
            
            Text(
                text = "将测试 $totalTests 组配置" + 
                    if (openclCount > 0) " (OpenCL: $openclCount)" else "" +
                    if (cpuCount > 0) " (CPU: $cpuCount)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Card displaying a single benchmark result
 */
@Composable
private fun BenchmarkResultCard(result: BenchmarkResult) {
    val backgroundColor = when {
        result.error != null -> MaterialTheme.colorScheme.errorContainer
        result.backendType == "opencl" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (result.backendType == "opencl") Icons.Default.Memory else Icons.Default.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = result.backendType.uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = result.configDescription.ifEmpty { "thread=${result.threadCount}" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (result.isSuccess) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "成功",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = "失败",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (result.isSuccess) {
                // Speed metrics row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricItem(
                        label = "Prefill",
                        value = result.prefillSpeedFormatted,
                        subValue = result.prefillTimeFormatted
                    )
                    MetricItem(
                        label = "Decode",
                        value = result.decodeSpeedFormatted,
                        subValue = result.decodeTimeFormatted
                    )
                }
                
                // Timing breakdown row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricItem(
                        label = "Load",
                        value = "${result.loadTimeMs}ms",
                        subValue = null
                    )
                    MetricItem(
                        label = "首Token",
                        value = "${result.firstTokenTimeMs}ms",
                        subValue = if (result.warmupOverheadMs > 0) "开销+${result.warmupOverheadMs}ms" else null
                    )
                    MetricItem(
                        label = "总生成",
                        value = "${result.totalGenerateTimeMs}ms",
                        subValue = null
                    )
                }

                // Token info
                Text(
                    text = "Prompt: ${result.promptLen} tok → Decode: ${result.decodeLen} tok",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Response preview
                if (result.responsePreview.isNotEmpty()) {
                    Text(
                        text = "回复: ${result.responsePreview}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            } else {
                // Error message
                Text(
                    text = "错误: ${result.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Single metric display item
 */
@Composable
private fun MetricItem(
    label: String,
    value: String,
    subValue: String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        subValue?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

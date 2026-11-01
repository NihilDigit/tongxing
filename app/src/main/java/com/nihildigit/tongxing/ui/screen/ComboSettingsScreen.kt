package com.nihildigit.tongxing.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nihildigit.tongxing.model.UiComboConfigResult
import com.nihildigit.tongxing.model.UiComboInfo
import com.nihildigit.tongxing.model.UiMcpConnectionState
import com.nihildigit.tongxing.model.UiMcpConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 快捷工具设置界面
 * 
 * 功能：
 * - 列出所有已注册的 Combo 工具
 * - Checkbox 控制启用/禁用
 * - 保存按钮批量应用配置
 * - 底部显示 MCP 服务状态（只读）
 * 
 * 注意：这是开发者调试界面，不需要视障友好设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComboSettingsScreen(
    combos: List<UiComboInfo>,
    mcpStatuses: List<UiMcpConnectionStatus>,
    onApplyConfig: suspend (Set<String>) -> UiComboConfigResult,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    // 本地编辑状态（未保存前的临时状态）
    var editingEnabled by remember(combos) {
        mutableStateOf(combos.filter { it.isEnabled }.map { it.name }.toSet())
    }
    
    // 是否有未保存的更改
    val hasChanges = remember(editingEnabled, combos) {
        val currentEnabled = combos.filter { it.isEnabled }.map { it.name }.toSet()
        editingEnabled != currentEnabled
    }
    
    // 保存状态
    var isSaving by remember { mutableStateOf(false) }
    var saveResult by remember { mutableStateOf<UiComboConfigResult?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "快捷工具",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 保存按钮
                    TextButton(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                saveResult = null
                                // 在 IO 线程执行网络操作，避免阻塞主线程
                                val result = withContext(Dispatchers.IO) {
                                    onApplyConfig(editingEnabled)
                                }
                                saveResult = result
                                isSaving = false
                            }
                        },
                        enabled = hasChanges && !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "保存",
                                fontWeight = if (hasChanges) FontWeight.Bold else FontWeight.Normal,
                                color = if (hasChanges) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 提示信息
            item {
                if (hasChanges) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "有未保存的更改，点击保存生效",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // 保存结果提示
                saveResult?.let { result ->
                    val (message, isError) = when (result) {
                        is UiComboConfigResult.Success ->
                            "已启用 ${result.enabledCount} 个快捷工具" to false
                        is UiComboConfigResult.PartialSuccess ->
                            "已启用 ${result.enabledCount} 个工具，但部分服务连接失败：${result.failedServices.joinToString()}" to true
                        is UiComboConfigResult.Failure ->
                            "保存失败：${result.error}" to true
                    }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isError) 
                                MaterialTheme.colorScheme.errorContainer 
                            else 
                                MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = if (isError) 
                                    MaterialTheme.colorScheme.onErrorContainer 
                                else 
                                    MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = message,
                                fontSize = 14.sp,
                                color = if (isError) 
                                    MaterialTheme.colorScheme.onErrorContainer 
                                else 
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            
            // Combo 列表（按分组显示）
            val groupedCombos = combos.groupBy { it.group }.toSortedMap(compareBy { it.order })
            
            if (combos.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "暂无可用的快捷工具",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                groupedCombos.forEach { (group, groupCombos) ->
                    // 分组标题
                    item(key = "group_${group.name}") {
                        Text(
                            text = group.displayName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    
                    // 分组内的 Combo 列表
                    items(groupCombos, key = { it.name }) { combo ->
                        ComboSettingItem(
                            combo = combo,
                            isChecked = combo.name in editingEnabled,
                            onCheckedChange = { checked ->
                                editingEnabled = if (checked) {
                                    editingEnabled + combo.name
                                } else {
                                    editingEnabled - combo.name
                                }
                            }
                        )
                    }
                    
                    // 分组间隔
                    item(key = "spacer_${group.name}") {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            
            // MCP 服务状态
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "服务状态",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            if (mcpStatuses.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "暂无 MCP 服务",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(mcpStatuses, key = { it.serviceId }) { status ->
                    McpStatusItem(status = status)
                }
            }
            
            // 底部说明
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "启用快捷工具后，点击保存生效。系统将自动连接所需服务并更新 AI 能力。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Combo 设置项
 */
@Composable
private fun ComboSettingItem(
    combo: UiComboInfo,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.padding(end = 8.dp)
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = combo.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    
                    // 可用性指示
                    if (!combo.isAvailable) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "服务未就绪",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = combo.description,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (combo.requiredServices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "依赖: ${combo.requiredServices.joinToString(", ")}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/**
 * MCP 服务状态项
 */
@Composable
private fun McpStatusItem(status: UiMcpConnectionStatus) {
    val (icon, color, stateText) = when (status.state) {
        UiMcpConnectionState.CONNECTED -> Triple(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "已连接"
        )
        UiMcpConnectionState.CONNECTING -> Triple(
            Icons.Filled.Sync,
            MaterialTheme.colorScheme.tertiary,
            "连接中..."
        )
        UiMcpConnectionState.FAILED -> Triple(
            Icons.Filled.Error,
            MaterialTheme.colorScheme.error,
            status.errorMessage ?: "连接失败"
        )
        UiMcpConnectionState.DISCONNECTED -> Triple(
            Icons.Filled.Circle,
            MaterialTheme.colorScheme.outline,
            if (status.enabled) "未连接" else "未启用"
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.serviceName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stateText,
                    fontSize = 12.sp,
                    color = color
                )
            }
        }
    }
}

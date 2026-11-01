package com.nihildigit.tongxing.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nihildigit.tongxing.model.UiMcpConnectionState
import com.nihildigit.tongxing.model.UiMcpConnectionStatus
import com.nihildigit.tongxing.model.UiMcpServiceTier
import com.nihildigit.tongxing.TongXingViewModel
import com.nihildigit.tongxing.mcp.MCPServiceConfig
import com.nihildigit.tongxing.ui.components.GlassSurface
import com.nihildigit.tongxing.ui.components.StatusBadge
import com.nihildigit.tongxing.ui.theme.TongXingColors
import kotlinx.coroutines.launch

@Composable
fun McpSettingsScreen(
    viewModel: TongXingViewModel,
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    // val hub = viewModel.getHub() // Hub is now managed by Service
    val statuses by viewModel.mcpStatusFlow.collectAsState()

    val definitions = MCPServiceConfig.PRESET_SERVICES.associateBy { it.id }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Button(
                onClick = {
                    // TODO: IPC call to Service to enable all
                    onShowMessage("单一实例架构下，需通过悬浮球管理连接")
                },
                modifier = Modifier.weight(1f)
            ) { Text("全部启用") }
            OutlinedButton(
                onClick = {
                    // TODO: IPC call to Service
                    onShowMessage("单一实例架构下，需通过悬浮球管理连接")
                },
                modifier = Modifier.weight(1f)
            ) { Text("禁用非核心") }
        }

        OutlinedButton(
            onClick = {
                // TODO: IPC call to Service
                onShowMessage("已发送重连指令 (Simulated)")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Cached, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("重新连接")
        }

        ServiceGroup(title = "CORE 核心服务") {
            statuses.filter { it.tier == UiMcpServiceTier.CORE }.forEach { st ->
                McpServiceCard(st, definitions[st.serviceId]?.description, onToggle = { enabled ->
                    // hub?.getConnectionManager()?.setServiceEnabled(st.serviceId, enabled)
                })
            }
        }

        ServiceGroup(title = "STANDARD 标准服务") {
            statuses.filter { it.tier == UiMcpServiceTier.STANDARD }.forEach { st ->
                McpServiceCard(st, definitions[st.serviceId]?.description, onToggle = { enabled ->
                    // hub?.getConnectionManager()?.setServiceEnabled(st.serviceId, enabled)
                })
            }
        }

        ServiceGroup(title = "OPTIONAL 可选服务") {
            statuses.filter { it.tier == UiMcpServiceTier.OPTIONAL }.forEach { st ->
                McpServiceCard(st, definitions[st.serviceId]?.description, onToggle = { enabled ->
                    // hub?.getConnectionManager()?.setServiceEnabled(st.serviceId, enabled)
                })
            }
        }
    }
}

@Composable
private fun ServiceGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(title, fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun McpServiceCard(
    status: UiMcpConnectionStatus,
    description: String?,
    onToggle: (Boolean) -> Unit
) {
    val color = when {
        !status.enabled -> TongXingColors.Outline
        status.state == UiMcpConnectionState.CONNECTED -> TongXingColors.Safe
        status.state == UiMcpConnectionState.CONNECTING -> TongXingColors.Caution
        status.state == UiMcpConnectionState.FAILED -> TongXingColors.Critical
        else -> TongXingColors.Caution
    }
    val stateLabel = when {
        !status.enabled -> "已禁用"
        status.state == UiMcpConnectionState.CONNECTED -> "已连接"
        status.state == UiMcpConnectionState.CONNECTING -> "连接中..."
        status.state == UiMcpConnectionState.FAILED -> "连接失败"
        else -> "未连接"
    }
    val badgeIcon = when {
        !status.enabled -> Icons.Filled.Warning
        status.state == UiMcpConnectionState.CONNECTED -> Icons.Filled.CheckCircle
        status.state == UiMcpConnectionState.FAILED -> Icons.Filled.Warning
        else -> Icons.Filled.Warning
    }

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "服务 ${status.serviceName}，状态 $stateLabel" }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(status.serviceName, fontWeight = FontWeight.SemiBold)
                    Text(status.serviceId, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = status.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = "${status.serviceName} 服务开关，当前状态${if (status.enabled) "已启用" else "已禁用"}" }
                )
            }

            description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            StatusBadge(
                label = stateLabel,
                icon = badgeIcon,
                color = color,
                contentDescription = stateLabel
            )
            if (status.state == UiMcpConnectionState.FAILED && !status.errorMessage.isNullOrBlank()) {
                Text("错误：${status.errorMessage}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

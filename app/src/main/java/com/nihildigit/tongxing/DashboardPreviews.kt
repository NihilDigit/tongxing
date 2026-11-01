package com.nihildigit.tongxing

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nihildigit.tongxing.ui.components.AssistantStatus
import com.nihildigit.tongxing.ui.components.ComboSwitchEntry
import com.nihildigit.tongxing.ui.components.CommandItem
import com.nihildigit.tongxing.ui.components.PremiumStartDisk
import com.nihildigit.tongxing.ui.components.StatusHorizontalHub
import com.nihildigit.tongxing.ui.components.StatusItemData
import com.nihildigit.tongxing.ui.components.ToolkitEntry
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingDimens

@Composable
fun DashboardScreenPreview(
    onOpenPermissions: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSafety: () -> Unit,
    onOpenSettings: () -> Unit,
    assistantStatus: AssistantStatus,
    onToggleAssistant: () -> Unit,
    allPermissionsGranted: Boolean = true,
    statusItems: List<StatusItemData> = listOf(
        StatusItemData("AI核心", "就绪", Icons.Filled.Memory, TongXingColors.Safe),
        StatusItemData(stringResource(R.string.safety_engine), "运行", Icons.Filled.Shield, TongXingColors.Safe),
        StatusItemData("MCP服务", "异常", Icons.Filled.Hub, TongXingColors.Critical, isFailed = true),
        StatusItemData("模式", "导航", Icons.Filled.NearMe, TongXingColors.Secondary)
    )
) {
    Surface(modifier = Modifier.fillMaxSize(), color = TongXingColors.Surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(TongXingDimens.SpaceM)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Row {
                    if (!allPermissionsGranted) {
                        IconButton(onClick = onOpenPermissions) {
                            Icon(Icons.Filled.Warning, null, tint = TongXingColors.Critical)
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
            StatusHorizontalHub(statuses = statusItems)
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                PremiumStartDisk(status = assistantStatus) { onToggleAssistant() }
            }
            ToolkitEntry(stringResource(R.string.plugins), stringResource(R.string.plugins_help), Icons.Filled.AutoAwesome, TongXingColors.Secondary, onClick = onOpenTools)
            ToolkitEntry(stringResource(R.string.voice_guide), stringResource(R.string.voice_guide_help), Icons.Filled.Lightbulb, TongXingColors.Primary, onClick = onOpenHistory)
            ToolkitEntry(stringResource(R.string.safety_demo), stringResource(R.string.safety_demo_help), Icons.Filled.Visibility, TongXingColors.Tertiary, onClick = onOpenSafety)
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

data class ToolHubItem(
    val id: String,
    val providerId: String,
    val name: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val enabled: Boolean
)

@Composable
fun ToolHubScreenPreview(
    onBack: () -> Unit,
    items: List<ToolHubItem> = listOf(
        ToolHubItem("amap-maps", "amap-maps", "高德地图", "提供导航、周边搜索、天气查询", Icons.Filled.Map, true),
        ToolHubItem("didi-sandbox", "didi-sandbox", "滴滴打车", "提供打车估价服务（沙箱）", Icons.Filled.LocalTaxi, false)
    ),
    onToggle: (ToolHubItem, Boolean) -> Unit = { _, _ -> }
) {
    Surface(modifier = Modifier.fillMaxSize(), color = TongXingColors.Surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(TongXingDimens.SpaceM)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceM)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                Text(stringResource(R.string.plugins), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            items.forEach { item ->
                ComboSwitchEntry(item.name, item.description, item.icon, item.enabled) { enabled ->
                    onToggle(item, enabled)
                }
            }
        }
    }
}

@Composable
fun VoiceGuideScreenPreview(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = TongXingColors.Surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(TongXingDimens.SpaceM)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                Text(stringResource(R.string.voice_guide), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "可以对我说：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("实时感知", fontWeight = FontWeight.Bold, color = TongXingColors.Primary)
                CommandItem(Icons.Filled.Visibility, "前面有什么？", "AI 将调用相机为您描述眼前的实时景象。", TongXingColors.Primary)
                CommandItem(Icons.Filled.Scanner, "读一下面前的文字", "帮助识别包装盒、药品或指示牌内容。", TongXingColors.Primary)
                Text("出行辅助", fontWeight = FontWeight.Bold, color = TongXingColors.Secondary)
                CommandItem(Icons.Filled.Navigation, "带我去最近的地铁站", "为您寻找最近的地铁入口并启动语音导航。", TongXingColors.Secondary)
                CommandItem(Icons.Filled.LocalTaxi, "我要打车去火车站", "基于当前位置为您快速预估打车费用。", TongXingColors.Secondary)
            }
        }
    }
}

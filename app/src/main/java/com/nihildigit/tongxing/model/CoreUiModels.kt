package com.nihildigit.tongxing.model

import com.nihildigit.agentcore.hub.ComboConfigResult
import com.nihildigit.agentcore.hub.ComboGroup
import com.nihildigit.agentcore.hub.ComboInfo
import com.nihildigit.agentcore.hub.McpConnectionState
import com.nihildigit.agentcore.hub.McpConnectionStatus
import com.nihildigit.agentcore.hub.McpServiceTier
import com.nihildigit.safetycore.core.ThreatLevel
import com.nihildigit.safetycore.orchestrator.FrameResult

/**
 * App-layer DTOs to keep UI independent from AgentCore/SafetyCore internals.
 */
enum class UiMcpConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

enum class UiMcpServiceTier {
    CORE,
    STANDARD,
    OPTIONAL
}

data class UiMcpConnectionStatus(
    val serviceId: String,
    val serviceName: String,
    val tier: UiMcpServiceTier,
    val state: UiMcpConnectionState,
    val enabled: Boolean,
    val errorMessage: String? = null,
    val lastConnectAttempt: Long? = null
)

enum class UiComboGroup(val displayName: String, val order: Int) {
    TRAVEL("出行", 1),
    TAXI("打车", 2),
    LIFE("生活", 3),
    INFO("资讯", 4),
    OTHER("其他", 99)
}

data class UiComboInfo(
    val name: String,
    val displayName: String,
    val description: String,
    val group: UiComboGroup,
    val requiredServices: List<String>,
    val isEnabled: Boolean,
    val isAvailable: Boolean,
    val providerId: String? = null
)

sealed class UiComboConfigResult {
    data class Success(val enabledCount: Int) : UiComboConfigResult()
    data class PartialSuccess(
        val enabledCount: Int,
        val failedServices: List<String>
    ) : UiComboConfigResult()
    data class Failure(val error: String) : UiComboConfigResult()
}

enum class UiThreatLevel {
    SAFE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class UiFrameSnapshot(
    val threatLevel: UiThreatLevel,
    val threatCount: Int,
    val trackCount: Int,
    val tsNanos: Long
)

fun McpConnectionStatus.toUiStatus(): UiMcpConnectionStatus {
    val tierUi = when (tier) {
        McpServiceTier.CORE -> UiMcpServiceTier.CORE
        McpServiceTier.STANDARD -> UiMcpServiceTier.STANDARD
        McpServiceTier.OPTIONAL -> UiMcpServiceTier.OPTIONAL
    }
    val stateUi = when (state) {
        McpConnectionState.DISCONNECTED -> UiMcpConnectionState.DISCONNECTED
        McpConnectionState.CONNECTING -> UiMcpConnectionState.CONNECTING
        McpConnectionState.CONNECTED -> UiMcpConnectionState.CONNECTED
        McpConnectionState.FAILED -> UiMcpConnectionState.FAILED
    }
    return UiMcpConnectionStatus(
        serviceId = serviceId,
        serviceName = serviceName,
        tier = tierUi,
        state = stateUi,
        enabled = enabled,
        errorMessage = errorMessage,
        lastConnectAttempt = lastConnectAttempt
    )
}

fun ComboInfo.toUiCombo(): UiComboInfo {
    val groupUi = when (group) {
        ComboGroup.TRAVEL -> UiComboGroup.TRAVEL
        ComboGroup.TAXI -> UiComboGroup.TAXI
        ComboGroup.LIFE -> UiComboGroup.LIFE
        ComboGroup.INFO -> UiComboGroup.INFO
        ComboGroup.OTHER -> UiComboGroup.OTHER
    }
    return UiComboInfo(
        name = name,
        displayName = displayName,
        description = description,
        group = groupUi,
        requiredServices = requiredServices,
        isEnabled = isEnabled,
        isAvailable = isAvailable,
        providerId = providerId
    )
}

fun ComboConfigResult.toUiResult(): UiComboConfigResult {
    return when (this) {
        is ComboConfigResult.Success -> UiComboConfigResult.Success(enabledCount)
        is ComboConfigResult.PartialSuccess -> UiComboConfigResult.PartialSuccess(
            enabledCount = enabledCount,
            failedServices = failedServices
        )
        is ComboConfigResult.Failure -> UiComboConfigResult.Failure(error)
    }
}

fun FrameResult.toUiSnapshot(): UiFrameSnapshot {
    val highest = threats.maxByOrNull { it.level.ordinal }?.level
    val level = when (highest) {
        null -> UiThreatLevel.SAFE
        ThreatLevel.LOW -> UiThreatLevel.LOW
        ThreatLevel.MEDIUM -> UiThreatLevel.MEDIUM
        ThreatLevel.HIGH -> UiThreatLevel.HIGH
        ThreatLevel.CRITICAL -> UiThreatLevel.CRITICAL
    }
    return UiFrameSnapshot(
        threatLevel = level,
        threatCount = threats.size,
        trackCount = tracks.size,
        tsNanos = tsNanos
    )
}

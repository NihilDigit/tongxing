package com.nihildigit.agentcore.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import com.nihildigit.agentcore.logging.LogConfig

/**
 * OpenAI-compatible chat models for AgentCore.
 */

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class Tool(
    val type: String = "function",
    val function: ToolFunction,
    @SerialName("tool_type") val toolType: ToolType = ToolType.INTERMEDIATE,  // Default to INTERMEDIATE for backward compatibility
    /** Tool priority for sorting (higher = earlier in list, Combo tools default to 100, raw MCP tools default to 0) */
    val priority: Int = 0,
    /** Renewal seconds for stem timeout extension (not serialized). */
    @kotlinx.serialization.Transient val renewalSeconds: Int = -1
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonElement? = null
)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<Tool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null
)

@Serializable
data class ChatResponse(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage? = null,
    val delta: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

/**
 * Streaming delta for incremental responses.
 */
@Serializable
data class StreamDelta(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<DeltaChoice>
)

@Serializable
data class DeltaChoice(
    val index: Int,
    val delta: DeltaContent,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class DeltaContent(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null
)

@Serializable
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallDelta? = null
)

@Serializable
data class FunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null
)

/**
 * Internal representation for parsed Qwen tool calls.
 */
data class ParsedToolCall(
    val name: String,
    val arguments: Map<String, Any?>,
    val id: String = "call_${System.currentTimeMillis()}"
)

/**
 * Tool execution result.
 */
data class ToolResult(
    val toolCallId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
    val toolType: ToolType = ToolType.INTERMEDIATE  // Tool type for ReAct workflow
)

/**
 * Tool types for ReAct workflow differentiation.
 */
enum class ToolType {
    @SerialName("action")
    ACTION,         // Tools that execute an action and exit ReAct loop; result deferred to next turn

    @SerialName("intermediate")
    INTERMEDIATE    // Tools that return data for further processing
}

/**
 * Escalation trigger types.
 */
enum class EscalationTrigger {
    IMAGE_INPUT,
    COMPLEX_REASONING,
    TOOL_FAILURE,
    LOW_CONFIDENCE,
    USER_REQUEST
}

/**
 * Agent execution state.
 */
enum class ExecutionMode {
    LOCAL,
    CLOUD
}

/**
 * Configuration for AgentCore.
 */
data class AgentConfig(
    val maxInputTokens: Int = 28000,
    val maxNewTokens: Int = 2048,
    val timeoutMs: Long = 60000,
    /** Timeout for a single tool execution (ms). */
    val toolTimeoutMs: Long = 10_000,
    val cloudCompletionMarker: String = "本轮已完成，可切回本地",
    val maxToolRetries: Int = 2,
    val enableCloudEscalation: Boolean = true,
    val maxAgentLoops: Int = 10,  // Maximum iterations in agent loop
    val maxToolCallsPerTurn: Int = 3,  // Maximum tool calls to execute per assistant turn
    val maxHistoryMessages: Int = 20,  // Maximum messages to keep in history window (for 4K context, only used in fallback mode)
    val logConfig: LogConfig = LogConfig.debugDefault(),  // Default to NORMAL level, can be overridden
    
    // Stem Context System configuration
    /** Whether to use Stem Context System (false = fallback to sliding window) */
    val useStemContext: Boolean = true,
    /** Timeout for stem warmup in milliseconds */
    val stemWarmupTimeoutMs: Long = 30000,
    /** Maximum tokens allowed for a single task (within 4K context) */
    val maxTaskTokens: Int = 3500,
    /** Timeout for task execution in milliseconds */
    val taskTimeoutMs: Long = 60000,
    /** Delay before auto-pruning after task ends (seconds), supports follow-up questions */
    /**
     * Auto-prune delay for Stem Context task tokens:
     * - `< 0`: disable auto-prune
     * - `= 0`: prune immediately after task
     * - `> 0`: prune after delay (allows follow-up)
     */
    val autoPruneDelaySeconds: Int = 30,
    /** Maximum consecutive prune failures before disabling stem and falling back */
    val maxPruneFailures: Int = 3
)

/**
 * Debug snapshot for ReAct workflow monitoring.
 */
data class DebugSnapshot(
    val conversationHistory: List<ChatMessage>,
    val executionMode: ExecutionMode,
    val loopCount: Int,
    val maxLoopCount: Int,
    val consecutiveFailures: Int,
    val lastToolExecutionTime: Long? = null,
    val currentToolName: String? = null,
    val requestId: String? = null,
    // Stem Context debug info (null if stem disabled)
    val stemState: String? = null,
    val stemBaseSeqLen: Long? = null,
    val stemCurrentSeqLen: Long? = null,
    val stemTaskTokenCount: Long? = null,
    val stemIsNearLimit: Boolean? = null
)

/**
 * Inference performance metrics.
 */
data class InferenceMetrics(
    val prefillTimeMs: Long,
    val decodeTimeMs: Long,
    val promptTokens: Int,
    val decodeTokens: Int,
    val totalTokens: Int,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val requestId: String
)

package com.nihildigit.agentcore.logging

import android.util.Log

/**
 * Unified logging interface for AgentCore with requestId support and level filtering.
 * 
 * All AgentCore components should use this interface instead of direct Log.* calls.
 * 
 * Log format: [TAG][Component][Operation] message key=value key=value
 * 
 * Context debugging tags:
 * - [CONTEXT][Stem] - Stem warmup prompt content and token count
 * - [CONTEXT][Task] - Task prompt content, message count, and token usage
 */
object AgentLogger {
    
    private const val CORE_TAG = "AgentCore"
    private const val CONTEXT_TAG = "CONTEXT"
    
    /**
     * Current log configuration.
     */
    private var config: LogConfig = LogConfig.debugDefault()
    
    /**
     * Set the log configuration.
     */
    fun setConfig(newConfig: LogConfig) {
        config = newConfig
    }
    
    /**
     * Get the current log configuration.
     */
    fun getConfig(): LogConfig = config
    
    /**
     * Log a minimal level message (errors and critical performance metrics only).
     */
    fun minimal(component: String, operation: String, message: String, vararg keyValues: Pair<String, Any>) {
        if (config.shouldLog(LogLevel.MINIMAL)) {
            logInternal(LogLevel.MINIMAL, component, operation, message, *keyValues)
        }
    }
    
    /**
     * Log a normal level message (default operational logs).
     */
    fun normal(component: String, operation: String, message: String, vararg keyValues: Pair<String, Any>) {
        if (config.shouldLog(LogLevel.NORMAL)) {
            logInternal(LogLevel.NORMAL, component, operation, message, *keyValues)
        }
    }
    
    /**
     * Log a verbose level message (detailed debugging information).
     */
    fun verbose(component: String, operation: String, message: String, vararg keyValues: Pair<String, Any>) {
        if (config.shouldLog(LogLevel.VERBOSE)) {
            logInternal(LogLevel.VERBOSE, component, operation, message, *keyValues)
        }
    }
    
    /**
     * Log an error message (always shown regardless of log level).
     */
    fun error(component: String, operation: String, message: String, throwable: Throwable? = null, vararg keyValues: Pair<String, Any>) {
        val fullMessage = buildMessage(component, operation, message, *keyValues)
        if (throwable != null) {
            Log.e(CORE_TAG, fullMessage, throwable)
        } else {
            Log.e(CORE_TAG, fullMessage)
        }
    }
    
    /**
     * Log with request ID prefix for request tracing.
     */
    fun withRequest(requestId: String, level: LogLevel, component: String, operation: String, message: String, vararg keyValues: Pair<String, Any>) {
        if (config.shouldLog(level)) {
            val messageWithReq = "[req:$requestId] $message"
            logInternal(level, component, operation, messageWithReq, *keyValues)
        }
    }
    
    /**
     * Log with request ID prefix for minimal level.
     */
    fun minimalWithRequest(requestId: String, component: String, operation: String, message: String, vararg keyValues: Pair<String, Any>) {
        withRequest(requestId, LogLevel.MINIMAL, component, operation, message, *keyValues)
    }
    
    /**
     * Log with request ID prefix for normal level.
     */
    fun normalWithRequest(requestId: String, component: String, operation: String, message: String, vararg keyValues: Pair<String, Any>) {
        withRequest(requestId, LogLevel.NORMAL, component, operation, message, *keyValues)
    }
    
    /**
     * Log with request ID prefix for verbose level.
     */
    fun verboseWithRequest(requestId: String, component: String, operation: String, message: String, vararg keyValues: Pair<String, Any>) {
        withRequest(requestId, LogLevel.VERBOSE, component, operation, message, *keyValues)
    }
    
    /**
     * Internal logging method.
     */
    private fun logInternal(level: LogLevel, component: String, operation: String, message: String, vararg keyValues: Pair<String, Any>) {
        val fullMessage = buildMessage(component, operation, message, *keyValues)
        
        when (level) {
            LogLevel.MINIMAL -> Log.w(CORE_TAG, fullMessage) // Use warning level for minimal to ensure visibility
            LogLevel.NORMAL -> Log.i(CORE_TAG, fullMessage)
            LogLevel.VERBOSE -> Log.d(CORE_TAG, fullMessage)
        }
    }
    
    /**
     * Build structured log message.
     */
    private fun buildMessage(component: String, operation: String, message: String, vararg keyValues: Pair<String, Any>): String {
        val builder = StringBuilder()
        builder.append("[$component][$operation] $message")
        
        if (keyValues.isNotEmpty()) {
            keyValues.forEach { (key, value) ->
                builder.append(" $key=$value")
            }
        }
        
        return builder.toString()
    }
    
    // ============================================================
    // Context Debug Logging - for Prompt engineering and debugging
    // ============================================================
    
    /**
     * Estimate token count from text (rough estimate: chars / 4 for Chinese/mixed text).
     * This is a simple heuristic suitable for debugging purposes.
     */
    fun estimateTokens(text: String): Int {
        return (text.length + 3) / 4  // Round up
    }
    
    /**
     * Log Stem context prompt (System Prompt + Tools Prompt) during warmup.
     * Format: [CONTEXT][Stem] content=... tokens=xxx
     * 
     * Logs at NORMAL level by default (summary), VERBOSE for full content.
     */
    fun logStemContext(
        systemPrompt: String,
        toolsPrompt: String,
        stemSeqLen: Long? = null
    ) {
        if (!config.shouldLog(LogLevel.NORMAL)) return
        
        val systemTokens = estimateTokens(systemPrompt)
        val toolsTokens = estimateTokens(toolsPrompt)
        val totalTokens = systemTokens + toolsTokens
        
        // Summary at NORMAL level
        val summary = StringBuilder()
        summary.append("[$CONTEXT_TAG][Stem] Stem warmup: ")
        summary.append("systemTokens=$systemTokens toolsTokens=$toolsTokens totalTokens=$totalTokens")
        if (stemSeqLen != null) {
            summary.append(" actualSeqLen=$stemSeqLen")
        }
        Log.i(CORE_TAG, summary.toString())
        
        // Full content at VERBOSE level
        if (config.shouldLog(LogLevel.VERBOSE)) {
            val logMessage = StringBuilder()
            logMessage.append("[$CONTEXT_TAG][Stem] Full stem prompt:\n")
            logMessage.append("--- System Prompt (${systemPrompt.length} chars) ---\n")
            logMessage.append(systemPrompt)
            logMessage.append("\n--- Tools Prompt (${toolsPrompt.length} chars) ---\n")
            logMessage.append(toolsPrompt)
            logMessage.append("\n--- End Stem Prompt ---")
            Log.d(CORE_TAG, logMessage.toString())
        }
    }
    
    /**
     * Log Task context prompt during chat inference.
     * Format: [CONTEXT][Task] messageCount=x taskTokens=xxx totalTokens=xxx
     * 
     * Only logs at VERBOSE level.
     * 
     * @param prompt The full task prompt (conversation history formatted for LLM)
     * @param messageCount Number of messages in the conversation
     * @param stemTokens Base token count from stem (if using Stem Context System)
     * @param logFullPrompt Whether to log the full prompt content (can be very long)
     */
    fun logTaskContext(
        prompt: String,
        messageCount: Int,
        stemTokens: Int = 0,
        logFullPrompt: Boolean = false
    ) {
        if (!config.shouldLog(LogLevel.VERBOSE)) return
        
        val taskTokens = estimateTokens(prompt)
        val totalTokens = stemTokens + taskTokens
        
        val logMessage = StringBuilder()
        logMessage.append("[$CONTEXT_TAG][Task] Task prompt\n")
        logMessage.append("  messageCount=$messageCount taskTokens=$taskTokens stemTokens=$stemTokens totalTokens=$totalTokens")
        
        if (logFullPrompt) {
            logMessage.append("\n--- Task Prompt (${prompt.length} chars) ---\n")
            logMessage.append(prompt)
            logMessage.append("\n--- End Task Prompt ---")
        } else {
            // Log just a preview
            val preview = if (prompt.length > 200) prompt.take(200) + "..." else prompt
            logMessage.append("\n  preview: $preview")
        }
        
        Log.d(CORE_TAG, logMessage.toString())
    }
    
    /**
     * Log token usage warning when approaching context limit.
     * Logs at NORMAL level (not just verbose) to ensure visibility.
     * 
     * @param currentTokens Current total token count
     * @param maxTokens Maximum allowed tokens
     * @param usagePercent Current usage percentage (0-100)
     */
    fun logTokenWarning(
        currentTokens: Int,
        maxTokens: Int,
        usagePercent: Int
    ) {
        if (!config.shouldLog(LogLevel.NORMAL)) return
        
        val logMessage = "[$CONTEXT_TAG][Warning] Token usage high: $currentTokens/$maxTokens ($usagePercent%) - consider simplifying request"
        Log.w(CORE_TAG, logMessage)
    }
    
    // ============================================================
    // LLM I/O Debug Logging - for debugging what LLM sees and says
    // ============================================================
    
    /**
     * Log what the LLM sees as input (the full prompt sent to LLM).
     * Logs at NORMAL level for debugging prompt engineering.
     * 
     * @param requestId Request ID for tracing
     * @param prompt The full prompt sent to LLM (after buildPrompt)
     * @param isContinuation Whether this is a continuation (incremental) or full reset
     */
    fun logLlmInput(
        requestId: String,
        prompt: String,
        isContinuation: Boolean = false
    ) {
        if (!config.shouldLog(LogLevel.NORMAL)) return
        
        val tokens = estimateTokens(prompt)
        val mode = if (isContinuation) "增量" else "完整"
        
        val logMessage = StringBuilder()
        logMessage.append("[$CONTEXT_TAG][LLM-IN][$requestId] 模式=$mode tokens≈$tokens\n")
        logMessage.append("┌─────────────────────────────────────────\n")
        // Log full content, split by lines for readability
        prompt.lines().forEach { line ->
            logMessage.append("│ $line\n")
        }
        logMessage.append("└─────────────────────────────────────────")
        
        Log.i(CORE_TAG, logMessage.toString())
    }

    /**
     * Log the fully templated prompt (e.g., <|im_start|> markers) as seen by the backend.
     */
    fun logLlmTemplate(
        requestId: String,
        prompt: String
    ) {
        if (!config.shouldLog(LogLevel.NORMAL)) return

        val tokens = estimateTokens(prompt)
        val logMessage = StringBuilder()
        logMessage.append("[$CONTEXT_TAG][LLM-TEMPLATE][$requestId] tokens≈$tokens\n")
        logMessage.append("┌─────────────────────────────────────────\n")
        prompt.lines().forEach { line ->
            logMessage.append("│ $line\n")
        }
        logMessage.append("└─────────────────────────────────────────")

        Log.i(CORE_TAG, logMessage.toString())
    }
    
    /**
     * Log what the LLM outputs as response (raw output before parsing).
     * Logs at NORMAL level for debugging LLM behavior.
     * 
     * @param requestId Request ID for tracing
     * @param response The raw response from LLM (before parseResponse)
     * @param toolCalls Parsed tool calls (if any)
     * @param content Parsed content (if any)
     */
    fun logLlmOutput(
        requestId: String,
        response: String,
        toolCalls: List<Any>? = null,
        content: String? = null
    ) {
        if (!config.shouldLog(LogLevel.NORMAL)) return
        
        val tokens = estimateTokens(response)
        val hasToolCalls = !toolCalls.isNullOrEmpty()
        val toolCount = toolCalls?.size ?: 0
        
        val logMessage = StringBuilder()
        logMessage.append("[$CONTEXT_TAG][LLM-OUT][$requestId] tokens≈$tokens toolCalls=$toolCount\n")
        logMessage.append("┌─────────────────────────────────────────\n")
        // Log full content, split by lines for readability
        response.lines().forEach { line ->
            logMessage.append("│ $line\n")
        }
        logMessage.append("└─────────────────────────────────────────")
        
        if (hasToolCalls) {
            logMessage.append("\n[解析结果] 工具调用:")
            toolCalls?.forEachIndexed { idx, tc ->
                logMessage.append("\n  [$idx] $tc")
            }
        }
        if (!content.isNullOrBlank()) {
            logMessage.append("\n[解析结果] 内容: ${content.take(200)}${if (content.length > 200) "..." else ""}")
        }
        
        Log.i(CORE_TAG, logMessage.toString())
    }
}

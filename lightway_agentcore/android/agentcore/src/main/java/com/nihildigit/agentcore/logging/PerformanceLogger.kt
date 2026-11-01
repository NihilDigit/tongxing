package com.nihildigit.agentcore.logging

/**
 * Performance logging utility for AgentCore.
 * 
 * Provides structured performance metrics logging with [PERF] tags.
 * Format: [PERF][Component][Operation] key=value key=value
 */
object PerformanceLogger {
    
    /**
     * Log LLM inference start.
     */
    fun logInferenceStart(requestId: String, promptTokens: Int) {
        AgentLogger.minimalWithRequest(
            requestId = requestId,
            component = "LLM",
            operation = "Inference",
            message = "started",
            "promptTokens" to promptTokens
        )
    }
    
    /**
     * Log LLM inference completion.
     */
    fun logInferenceComplete(requestId: String, prefillMs: Long, decodeMs: Long, totalTokens: Int, tokensPerSec: Double) {
        AgentLogger.minimalWithRequest(
            requestId = requestId,
            component = "LLM",
            operation = "Inference",
            message = "completed",
            "prefillMs" to prefillMs,
            "decodeMs" to decodeMs,
            "totalTokens" to totalTokens,
            "tokensPerSec" to String.format("%.2f", tokensPerSec)
        )
    }
    
    /**
     * Log ReAct loop completion.
     */
    fun logReActLoopComplete(requestId: String, loops: Int, toolCalls: Int, totalMs: Long, mode: String) {
        AgentLogger.minimalWithRequest(
            requestId = requestId,
            component = "ReAct",
            operation = "Loop",
            message = "completed",
            "loops" to loops,
            "toolCalls" to toolCalls,
            "totalMs" to totalMs,
            "mode" to mode
        )
    }
    
    /**
     * Log tool execution.
     */
    fun logToolExecution(requestId: String, toolName: String, toolType: String, durationMs: Long, status: String, errorMessage: String? = null) {
        val message = "tool=$toolName type=$toolType durationMs=$durationMs status=$status"
        val keyValues = mutableListOf<Pair<String, Any>>(
            "tool" to toolName,
            "type" to toolType,
            "durationMs" to durationMs,
            "status" to status
        )
        
        if (errorMessage != null) {
            keyValues.add("error" to errorMessage.take(100)) // Limit error message length
        }
        
        AgentLogger.minimalWithRequest(
            requestId = requestId,
            component = "Tool",
            operation = "Execute",
            message = message,
            *keyValues.toTypedArray()
        )
    }
    
    /**
     * Log generic performance metric.
     */
    fun logMetric(component: String, operation: String, requestId: String? = null, message: String, vararg keyValues: Pair<String, Any>) {
        if (requestId != null) {
            AgentLogger.minimalWithRequest(requestId, component, operation, message, *keyValues)
        } else {
            AgentLogger.minimal(component, operation, message, *keyValues)
        }
    }
    
    /**
     * Log a timing measurement.
     */
    inline fun <T> measureTime(
        component: String,
        operation: String,
        requestId: String? = null,
        message: String = "operation",
        block: () -> T
    ): T {
        val startTime = System.currentTimeMillis()
        val result = block()
        val duration = System.currentTimeMillis() - startTime
        
        logMetric(component, operation, requestId, "$message completed", "durationMs" to duration)
        
        return result
    }
    
    /**
     * Create a performance timer for manual timing.
     */
    fun createTimer(component: String, operation: String, requestId: String? = null): PerformanceTimer {
        return PerformanceTimer(component, operation, requestId)
    }
}

/**
 * Simple timer for performance measurements.
 */
class PerformanceTimer(
    private val component: String,
    private val operation: String,
    private val requestId: String?
) {
    private val startTime = System.currentTimeMillis()
    
    /**
     * End the timer and log the duration.
     */
    fun end(message: String = "operation", vararg additionalKeyValues: Pair<String, Any>): Long {
        val duration = System.currentTimeMillis() - startTime
        val keyValues = mutableListOf<Pair<String, Any>>("durationMs" to duration)
        keyValues.addAll(additionalKeyValues)
        
        PerformanceLogger.logMetric(component, operation, requestId, "$message completed", *keyValues.toTypedArray())
        
        return duration
    }
}
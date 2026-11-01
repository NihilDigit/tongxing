package com.nihildigit.agentcore.logging

/**
 * Log levels for AgentCore logging system.
 */
enum class LogLevel {
    /**
     * Only errors and critical performance metrics.
     */
    MINIMAL,
    
    /**
     * Default level. Includes request start/end, ReAct step summaries, 
     * tool call summaries, and performance metrics.
     */
    NORMAL,
    
    /**
     * All logs including LLM chunk details, tool call parameters, 
     * and context management details.
     */
    VERBOSE
}

/**
 * Configuration for AgentCore logging system.
 */
data class LogConfig(
    /**
     * Current log level.
     */
    val level: LogLevel = LogLevel.NORMAL,
    
    /**
     * Whether to include timestamps in logs.
     */
    val includeTimestamp: Boolean = true,
    
    /**
     * Whether to include thread information in logs.
     */
    val includeThreadInfo: Boolean = false
) {
    companion object {
        /**
         * Default configuration for debug builds.
         */
        fun debugDefault(): LogConfig = LogConfig(
            level = LogLevel.NORMAL,
            includeTimestamp = true,
            includeThreadInfo = false
        )
        
        /**
         * Default configuration for production builds.
         */
        fun productionDefault(): LogConfig = LogConfig(
            level = LogLevel.MINIMAL,
            includeTimestamp = false,
            includeThreadInfo = false
        )
    }
    
    /**
     * Check if a log level should be output.
     */
    fun shouldLog(targetLevel: LogLevel): Boolean {
        return when (level) {
            LogLevel.MINIMAL -> targetLevel == LogLevel.MINIMAL
            LogLevel.NORMAL -> targetLevel == LogLevel.MINIMAL || targetLevel == LogLevel.NORMAL
            LogLevel.VERBOSE -> true // All levels
        }
    }
}
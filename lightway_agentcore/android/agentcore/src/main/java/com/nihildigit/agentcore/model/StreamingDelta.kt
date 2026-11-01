package com.nihildigit.agentcore.model

/**
 * Streaming delta data class for real-time LLM output with think tag support.
 * Encapsulates all streaming state including think tag parsing results.
 */
data class StreamingDelta(
    val content: String,           // Current delta text
    val isThinking: Boolean,       // Whether currently in <think> region
    val thinkContent: String?,     // Accumulated think content (optional)
    val isComplete: Boolean = false // Whether this is the final delta
) {
    companion object {
        /**
         * Create an empty delta representing the start of streaming
         */
        fun empty(): StreamingDelta = StreamingDelta(
            content = "",
            isThinking = false,
            thinkContent = null,
            isComplete = false
        )

        /**
         * Create a completion delta marking the end of streaming
         */
        fun complete(): StreamingDelta = StreamingDelta(
            content = "",
            isThinking = false,
            thinkContent = null,
            isComplete = true
        )
    }
}
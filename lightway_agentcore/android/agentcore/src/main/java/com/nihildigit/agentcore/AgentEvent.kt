package com.nihildigit.agentcore

sealed class AgentEvent {
    data class ToolStart(val toolName: String) : AgentEvent()
    data class Announce(val text: String) : AgentEvent()
    data class ToolDone(val toolName: String) : AgentEvent()
    data class Response(val text: String) : AgentEvent()
    data class Status(val text: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

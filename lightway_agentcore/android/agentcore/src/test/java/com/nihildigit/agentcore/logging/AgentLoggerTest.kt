package com.nihildigit.agentcore.logging

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.*

/**
 * Unit tests for AgentLogger.
 * Note: These tests verify the logging logic without actually testing Android Log output.
 */
class AgentLoggerTest {
    
    @Before
    fun setUp() {
        // Reset config before each test
        AgentLogger.setConfig(LogConfig.debugDefault())
    }
    
    @Test
    fun `test config management`() {
        val customConfig = LogConfig(level = LogLevel.VERBOSE)
        AgentLogger.setConfig(customConfig)
        assertEquals(customConfig, AgentLogger.getConfig())
    }
    
    @Test
    fun `test log level filtering - MINIMAL config`() {
        AgentLogger.setConfig(LogConfig(level = LogLevel.MINIMAL))
        
        // These should not throw exceptions even if they don't actually log
        AgentLogger.minimal("Test", "Operation", "minimal message")
        AgentLogger.normal("Test", "Operation", "normal message") // Should be filtered out
        AgentLogger.verbose("Test", "Operation", "verbose message") // Should be filtered out
        AgentLogger.error("Test", "Operation", "error message") // Should always log
    }
    
    @Test
    fun `test log level filtering - NORMAL config`() {
        AgentLogger.setConfig(LogConfig(level = LogLevel.NORMAL))
        
        // These should not throw exceptions
        AgentLogger.minimal("Test", "Operation", "minimal message")
        AgentLogger.normal("Test", "Operation", "normal message")
        AgentLogger.verbose("Test", "Operation", "verbose message") // Should be filtered out
        AgentLogger.error("Test", "Operation", "error message") // Should always log
    }
    
    @Test
    fun `test log level filtering - VERBOSE config`() {
        AgentLogger.setConfig(LogConfig(level = LogLevel.VERBOSE))
        
        // These should not throw exceptions
        AgentLogger.minimal("Test", "Operation", "minimal message")
        AgentLogger.normal("Test", "Operation", "normal message")
        AgentLogger.verbose("Test", "Operation", "verbose message")
        AgentLogger.error("Test", "Operation", "error message") // Should always log
    }
    
    @Test
    fun `test request tracing methods`() {
        val requestId = "test123"
        
        // These should not throw exceptions
        AgentLogger.minimalWithRequest(requestId, "Test", "Operation", "minimal message")
        AgentLogger.normalWithRequest(requestId, "Test", "Operation", "normal message")
        AgentLogger.verboseWithRequest(requestId, "Test", "Operation", "verbose message")
        AgentLogger.withRequest(requestId, LogLevel.NORMAL, "Test", "Operation", "normal message")
    }
    
    @Test
    fun `test error logging with throwable`() {
        val exception = RuntimeException("Test exception")
        
        // Should not throw exception
        AgentLogger.error("Test", "Operation", "error message", exception)
        AgentLogger.error("Test", "Operation", "error message") // Without throwable
    }
    
    @Test
    fun `test log with key-value pairs`() {
        // These should not throw exceptions
        AgentLogger.minimal("Test", "Operation", "message", "key1" to "value1", "key2" to 42)
        AgentLogger.normal("Test", "Operation", "message", "key1" to "value1", "key2" to 42)
        AgentLogger.verbose("Test", "Operation", "message", "key1" to "value1", "key2" to 42)
        AgentLogger.error("Test", "Operation", "message", null, "key1" to "value1", "key2" to 42)
        
        // With request ID
        AgentLogger.minimalWithRequest("req123", "Test", "Operation", "message", "key1" to "value1")
        AgentLogger.normalWithRequest("req123", "Test", "Operation", "message", "key1" to "value1")
        AgentLogger.verboseWithRequest("req123", "Test", "Operation", "message", "key1" to "value1")
    }
}
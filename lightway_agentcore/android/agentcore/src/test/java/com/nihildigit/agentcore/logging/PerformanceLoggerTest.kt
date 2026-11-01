package com.nihildigit.agentcore.logging

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.*

/**
 * Unit tests for PerformanceLogger.
 */
class PerformanceLoggerTest {
    
    @Before
    fun setUp() {
        // Reset config before each test
        AgentLogger.setConfig(LogConfig.debugDefault())
    }
    
    @Test
    fun `test log inference start`() {
        val requestId = "test123"
        val promptTokens = 100
        
        // Should not throw exception
        PerformanceLogger.logInferenceStart(requestId, promptTokens)
    }
    
    @Test
    fun `test log inference complete`() {
        val requestId = "test123"
        val prefillMs = 500L
        val decodeMs = 1000L
        val totalTokens = 150
        val tokensPerSec = 75.5
        
        // Should not throw exception
        PerformanceLogger.logInferenceComplete(requestId, prefillMs, decodeMs, totalTokens, tokensPerSec)
    }
    
    @Test
    fun `test log ReAct loop complete`() {
        val requestId = "test123"
        val loops = 5
        val toolCalls = 3
        val totalMs = 2000L
        val mode = "LOCAL"
        
        // Should not throw exception
        PerformanceLogger.logReActLoopComplete(requestId, loops, toolCalls, totalMs, mode)
    }
    
    @Test
    fun `test log tool execution success`() {
        val requestId = "test123"
        val toolName = "test_tool"
        val toolType = "ACTION"
        val durationMs = 500L
        val status = "success"
        
        // Should not throw exception
        PerformanceLogger.logToolExecution(requestId, toolName, toolType, durationMs, status)
    }
    
    @Test
    fun `test log tool execution error`() {
        val requestId = "test123"
        val toolName = "test_tool"
        val toolType = "INTERMEDIATE"
        val durationMs = 1000L
        val status = "error"
        val errorMessage = "Tool execution failed"
        
        // Should not throw exception
        PerformanceLogger.logToolExecution(requestId, toolName, toolType, durationMs, status, errorMessage)
    }
    
    @Test
    fun `test log generic metric`() {
        val component = "TestComponent"
        val operation = "TestOperation"
        val requestId = "test123"
        val message = "test metric"
        
        // Should not throw exception
        PerformanceLogger.logMetric(component, operation, requestId, message, "key1" to "value1", "key2" to 42)
        
        // Without request ID
        PerformanceLogger.logMetric(component, operation, null, message, "key1" to "value1")
    }
    
    @Test
    fun `test measureTime utility`() {
        val component = "TestComponent"
        val operation = "TestOperation"
        val requestId = "test123"
        
        val result = PerformanceLogger.measureTime(component, operation, requestId, "test operation") {
            Thread.sleep(100) // Simulate work
            "test result"
        }
        
        assertEquals("test result", result)
    }
    
    @Test
    fun `test performance timer`() {
        val component = "TestComponent"
        val operation = "TestOperation"
        val requestId = "test123"
        
        val timer = PerformanceLogger.createTimer(component, operation, requestId)
        
        // Simulate some work
        Thread.sleep(50)
        
        val duration = timer.end("test operation", "additionalKey" to "additionalValue")
        
        assertTrue("Duration should be positive", duration > 0)
    }
    
    @Test
    fun `test performance timer without additional data`() {
        val component = "TestComponent"
        val operation = "TestOperation"
        
        val timer = PerformanceLogger.createTimer(component, operation)
        
        // Simulate some work
        Thread.sleep(50)
        
        val duration = timer.end()
        
        assertTrue("Duration should be positive", duration > 0)
    }
}
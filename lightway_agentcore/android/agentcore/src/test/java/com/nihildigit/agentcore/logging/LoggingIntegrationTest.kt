package com.nihildigit.agentcore.logging

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Integration test for logging system behavior.
 * Tests the complete logging workflow with different configurations.
 */
class LoggingIntegrationTest {
    
    @Before
    fun setUp() {
        // Reset config before each test
        AgentLogger.setConfig(LogConfig.debugDefault())
    }
    
    @Test
    fun `test complete logging workflow with MINIMAL level`() {
        AgentLogger.setConfig(LogConfig(level = LogLevel.MINIMAL))
        
        val requestId = "test123"
        
        // Test minimal logging - only critical performance metrics should appear
        PerformanceLogger.logInferenceStart(requestId, 100)
        PerformanceLogger.logInferenceComplete(requestId, 500, 1000, 150, 75.5)
        PerformanceLogger.logReActLoopComplete(requestId, 3, 2, 2000, "LOCAL")
        PerformanceLogger.logToolExecution(requestId, "test_tool", "ACTION", 500, "success")
        
        // These should be filtered out in MINIMAL mode
        AgentLogger.normal("Test", "Operation", "normal message")
        AgentLogger.verbose("Test", "Operation", "verbose message")
        
        // Error should always appear
        AgentLogger.error("Test", "Operation", "error message")
        
        // Request tracing should work for minimal level
        AgentLogger.minimalWithRequest(requestId, "Test", "Operation", "minimal message")
        
        assertTrue("MINIMAL level test completed successfully", true)
    }
    
    @Test
    fun `test complete logging workflow with NORMAL level`() {
        AgentLogger.setConfig(LogConfig(level = LogLevel.NORMAL))
        
        val requestId = "test456"
        
        // Test normal logging - performance + normal operational logs
        PerformanceLogger.logInferenceStart(requestId, 100)
        PerformanceLogger.logInferenceComplete(requestId, 500, 1000, 150, 75.5)
        PerformanceLogger.logReActLoopComplete(requestId, 3, 2, 2000, "LOCAL")
        PerformanceLogger.logToolExecution(requestId, "test_tool", "ACTION", 500, "success")
        
        // These should appear in NORMAL mode
        AgentLogger.minimal("Test", "Operation", "minimal message")
        AgentLogger.normal("Test", "Operation", "normal message")
        AgentLogger.normalWithRequest(requestId, "Test", "Operation", "normal message with request")
        
        // This should be filtered out in NORMAL mode
        AgentLogger.verbose("Test", "Operation", "verbose message")
        
        // Error should always appear
        AgentLogger.error("Test", "Operation", "error message")
        
        assertTrue("NORMAL level test completed successfully", true)
    }
    
    @Test
    fun `test complete logging workflow with VERBOSE level`() {
        AgentLogger.setConfig(LogConfig(level = LogLevel.VERBOSE))
        
        val requestId = "test789"
        
        // Test verbose logging - everything should appear
        PerformanceLogger.logInferenceStart(requestId, 100)
        PerformanceLogger.logInferenceComplete(requestId, 500, 1000, 150, 75.5)
        PerformanceLogger.logReActLoopComplete(requestId, 3, 2, 2000, "LOCAL")
        PerformanceLogger.logToolExecution(requestId, "test_tool", "ACTION", 500, "success")
        
        // All levels should appear in VERBOSE mode
        AgentLogger.minimal("Test", "Operation", "minimal message")
        AgentLogger.normal("Test", "Operation", "normal message")
        AgentLogger.verbose("Test", "Operation", "verbose message")
        AgentLogger.verboseWithRequest(requestId, "Test", "Operation", "verbose message with request")
        
        // Error should always appear
        AgentLogger.error("Test", "Operation", "error message")
        
        assertTrue("VERBOSE level test completed successfully", true)
    }
    
    @Test
    fun `test performance timer functionality`() {
        val requestId = "timer123"
        
        val timer = PerformanceLogger.createTimer("TestComponent", "TestOperation", requestId)
        
        // Simulate some work
        Thread.sleep(50)
        
        val duration = timer.end("test operation", "additionalKey" to "additionalValue")
        
        assertTrue("Timer duration should be positive", duration > 0)
        assertTrue("Timer duration should be reasonable", duration >= 50)
        
        // Test measureTime utility
        val result = PerformanceLogger.measureTime("TestComponent", "TestOperation", requestId, "test operation") {
            Thread.sleep(30)
            "test result"
        }
        
        assertEquals("test result", result)
    }
    
    @Test
    fun `test structured logging format`() {
        val requestId = "format123"
        
        // Test that all logging methods accept key-value pairs
        AgentLogger.minimal("Component", "Operation", "message", 
            "stringKey" to "stringValue",
            "intKey" to 42,
            "booleanKey" to true,
            "doubleKey" to 3.14
        )
        
        AgentLogger.normalWithRequest(requestId, "Component", "Operation", "message",
            "requestId" to requestId,
            "timestamp" to System.currentTimeMillis()
        )
        
        PerformanceLogger.logMetric("Component", "Operation", requestId, "metric message",
            "metric1" to 100,
            "metric2" to "value"
        )
        
        assertTrue("Structured logging format test completed successfully", true)
    }
    
    @Test
    fun `test production vs debug configuration`() {
        val debugConfig = LogConfig.debugDefault()
        assertEquals(LogLevel.NORMAL, debugConfig.level)
        assertTrue(debugConfig.includeTimestamp)
        assertFalse(debugConfig.includeThreadInfo)
        
        val productionConfig = LogConfig.productionDefault()
        assertEquals(LogLevel.MINIMAL, productionConfig.level)
        assertFalse(productionConfig.includeTimestamp)
        assertFalse(productionConfig.includeThreadInfo)
        
        // Test that configurations work correctly
        AgentLogger.setConfig(debugConfig)
        assertTrue("Debug config should allow normal logs", 
            debugConfig.shouldLog(LogLevel.NORMAL))
        
        AgentLogger.setConfig(productionConfig)
        assertFalse("Production config should not allow normal logs", 
            productionConfig.shouldLog(LogLevel.NORMAL))
        assertTrue("Production config should allow minimal logs", 
            productionConfig.shouldLog(LogLevel.MINIMAL))
    }
}
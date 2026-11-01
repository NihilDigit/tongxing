package com.nihildigit.agentcore.logging

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Unit tests for LogConfig.
 */
class LogConfigTest {
    
    @Test
    fun `test default log config levels`() {
        val debugDefault = LogConfig.debugDefault()
        assertEquals(LogLevel.NORMAL, debugDefault.level)
        assertTrue(debugDefault.includeTimestamp)
        assertFalse(debugDefault.includeThreadInfo)
        
        val productionDefault = LogConfig.productionDefault()
        assertEquals(LogLevel.MINIMAL, productionDefault.level)
        assertFalse(productionDefault.includeTimestamp)
        assertFalse(productionDefault.includeThreadInfo)
    }
    
    @Test
    fun `test shouldLog filtering`() {
        // MINIMAL level
        val minimalConfig = LogConfig(level = LogLevel.MINIMAL)
        assertTrue(minimalConfig.shouldLog(LogLevel.MINIMAL))
        assertFalse(minimalConfig.shouldLog(LogLevel.NORMAL))
        assertFalse(minimalConfig.shouldLog(LogLevel.VERBOSE))
        
        // NORMAL level
        val normalConfig = LogConfig(level = LogLevel.NORMAL)
        assertTrue(normalConfig.shouldLog(LogLevel.MINIMAL))
        assertTrue(normalConfig.shouldLog(LogLevel.NORMAL))
        assertFalse(normalConfig.shouldLog(LogLevel.VERBOSE))
        
        // VERBOSE level
        val verboseConfig = LogConfig(level = LogLevel.VERBOSE)
        assertTrue(verboseConfig.shouldLog(LogLevel.MINIMAL))
        assertTrue(verboseConfig.shouldLog(LogLevel.NORMAL))
        assertTrue(verboseConfig.shouldLog(LogLevel.VERBOSE))
    }
    
    @Test
    fun `test config with custom settings`() {
        val customConfig = LogConfig(
            level = LogLevel.VERBOSE,
            includeTimestamp = false,
            includeThreadInfo = true
        )
        
        assertEquals(LogLevel.VERBOSE, customConfig.level)
        assertFalse(customConfig.includeTimestamp)
        assertTrue(customConfig.includeThreadInfo)
        assertTrue(customConfig.shouldLog(LogLevel.MINIMAL))
        assertTrue(customConfig.shouldLog(LogLevel.NORMAL))
        assertTrue(customConfig.shouldLog(LogLevel.VERBOSE))
    }
}
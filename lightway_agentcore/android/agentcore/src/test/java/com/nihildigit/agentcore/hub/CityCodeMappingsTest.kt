package com.nihildigit.agentcore.hub

import org.junit.Assert.*
import org.junit.Test

class CityCodeMappingsTest {
    
    // === IATA Code Tests ===
    
    @Test
    fun `getIataCode returns code for major cities`() {
        assertEquals("BJS", CityCodeMappings.getIataCode("北京"))
        assertEquals("SHA", CityCodeMappings.getIataCode("上海"))
        assertEquals("CAN", CityCodeMappings.getIataCode("广州"))
        assertEquals("SZX", CityCodeMappings.getIataCode("深圳"))
        assertEquals("CTU", CityCodeMappings.getIataCode("成都"))
    }
    
    @Test
    fun `getIataCode handles city suffix`() {
        assertEquals("BJS", CityCodeMappings.getIataCode("北京市"))
        assertEquals("SHA", CityCodeMappings.getIataCode("上海市"))
        assertEquals("CAN", CityCodeMappings.getIataCode("广州市"))
    }
    
    @Test
    fun `getIataCode handles aliases`() {
        assertEquals("BJS", CityCodeMappings.getIataCode("京"))
        assertEquals("SHA", CityCodeMappings.getIataCode("沪"))
        assertEquals("CAN", CityCodeMappings.getIataCode("穗"))
        assertEquals("SZX", CityCodeMappings.getIataCode("鹏城"))
        assertEquals("CTU", CityCodeMappings.getIataCode("蓉"))
    }
    
    @Test
    fun `getIataCode returns null for unknown city`() {
        assertNull(CityCodeMappings.getIataCode("不存在的城市"))
        assertNull(CityCodeMappings.getIataCode("ABC"))
    }
    
    @Test
    fun `getIataCode handles whitespace`() {
        assertEquals("BJS", CityCodeMappings.getIataCode("  北京  "))
        assertEquals("SHA", CityCodeMappings.getIataCode("\t上海\n"))
    }
    
    // === City Support Check ===
    
    @Test
    fun `isCitySupported returns true for supported cities`() {
        assertTrue(CityCodeMappings.isCitySupported("北京"))
        assertTrue(CityCodeMappings.isCitySupported("北京市"))
        assertTrue(CityCodeMappings.isCitySupported("京"))
    }
    
    @Test
    fun `isCitySupported returns false for unsupported cities`() {
        assertFalse(CityCodeMappings.isCitySupported("不存在"))
        assertFalse(CityCodeMappings.isCitySupported(""))
    }
    
    // === Fuzzy Match Tests ===
    
    @Test
    fun `fuzzyMatchCity exact match`() {
        assertEquals("北京", CityCodeMappings.fuzzyMatchCity("北京"))
        assertEquals("上海", CityCodeMappings.fuzzyMatchCity("上海"))
    }
    
    @Test
    fun `fuzzyMatchCity with suffix`() {
        assertEquals("北京", CityCodeMappings.fuzzyMatchCity("北京市"))
        assertEquals("上海", CityCodeMappings.fuzzyMatchCity("上海市"))
    }
    
    @Test
    fun `fuzzyMatchCity contains match`() {
        assertEquals("北京", CityCodeMappings.fuzzyMatchCity("北京首都"))
    }
    
    @Test
    fun `fuzzyMatchCity returns null for no match`() {
        assertNull(CityCodeMappings.fuzzyMatchCity("不存在的地方"))
    }
    
    // === Normalize City Tests ===
    
    @Test
    fun `normalizeCity removes suffix`() {
        assertEquals("北京", CityCodeMappings.normalizeCity("北京市"))
        assertEquals("广州", CityCodeMappings.normalizeCity("广州市"))
    }
    
    @Test
    fun `normalizeCity resolves alias`() {
        assertEquals("北京", CityCodeMappings.normalizeCity("京"))
        assertEquals("上海", CityCodeMappings.normalizeCity("沪"))
    }
    
    // === Station Code Tests ===
    
    @Test
    fun `getStationCode returns code for common stations`() {
        assertEquals("BJP", CityCodeMappings.getStationCode("北京"))
        assertEquals("BXP", CityCodeMappings.getStationCode("北京西"))
        assertEquals("VNP", CityCodeMappings.getStationCode("北京南"))
        assertEquals("SHH", CityCodeMappings.getStationCode("上海"))
        assertEquals("AOH", CityCodeMappings.getStationCode("上海虹桥"))
    }
    
    @Test
    fun `getStationCode handles station suffix`() {
        assertEquals("BJP", CityCodeMappings.getStationCode("北京站"))
        assertEquals("BXP", CityCodeMappings.getStationCode("北京西站"))
    }
    
    @Test
    fun `getStationCode returns null for unknown station`() {
        assertNull(CityCodeMappings.getStationCode("不存在站"))
    }
    
    // === Supported Cities List ===
    
    @Test
    fun `getSupportedCities returns non-empty list`() {
        val cities = CityCodeMappings.getSupportedCities()
        
        assertTrue(cities.isNotEmpty())
        assertTrue(cities.contains("北京"))
        assertTrue(cities.contains("上海"))
    }
}

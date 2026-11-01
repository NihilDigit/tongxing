package com.nihildigit.agentcore.hub

/**
 * 城市代码映射工具
 * 
 * 提供中文城市名到各种代码的映射：
 * - IATA 3字母代码（飞常准用）
 * - 火车站代码（12306 用）
 */
object CityCodeMappings {
    
    /**
     * 中国主要城市 → IATA 城市代码映射
     * 
     * 注：城市代码（如 BJS）代表城市的所有机场
     * 机场代码（如 PEK, PKX）代表具体机场
     */
    private val cityToIataMap = mapOf(
        // 直辖市
        "北京" to "BJS",
        "上海" to "SHA",
        "天津" to "TSN",
        "重庆" to "CKG",
        
        // 华北
        "石家庄" to "SJW",
        "太原" to "TYN",
        "呼和浩特" to "HET",
        
        // 东北
        "沈阳" to "SHE",
        "大连" to "DLC",
        "长春" to "CGQ",
        "哈尔滨" to "HRB",
        
        // 华东
        "南京" to "NKG",
        "杭州" to "HGH",
        "宁波" to "NGB",
        "温州" to "WNZ",
        "合肥" to "HFE",
        "福州" to "FOC",
        "厦门" to "XMN",
        "南昌" to "KHN",
        "济南" to "TNA",
        "青岛" to "TAO",
        "烟台" to "YNT",
        
        // 华中
        "郑州" to "CGO",
        "武汉" to "WUH",
        "长沙" to "CSX",
        
        // 华南
        "广州" to "CAN",
        "深圳" to "SZX",
        "珠海" to "ZUH",
        "汕头" to "SWA",
        "南宁" to "NNG",
        "海口" to "HAK",
        "三亚" to "SYX",
        
        // 西南
        "成都" to "CTU",
        "贵阳" to "KWE",
        "昆明" to "KMG",
        "拉萨" to "LXA",
        
        // 西北
        "西安" to "XIY",
        "兰州" to "LHW",
        "西宁" to "XNN",
        "银川" to "INC",
        "乌鲁木齐" to "URC",
        
        // 其他常用
        "桂林" to "KWL",
        "丽江" to "LJG",
        "张家界" to "DYG",
        "九寨沟" to "JZH",
        "香格里拉" to "DIG",
        "大理" to "DLU",
        "敦煌" to "DNH",
        "延安" to "ENY",
        "威海" to "WEH",
        "无锡" to "WUX",
        "常州" to "CZX",
        "扬州" to "YTY",
        "徐州" to "XUZ",
        "连云港" to "LYG",
        "盐城" to "YNZ"
    )
    
    /**
     * 城市别名映射（支持简称和别名）
     */
    private val cityAliasMap = mapOf(
        "京" to "北京",
        "沪" to "上海",
        "津" to "天津",
        "渝" to "重庆",
        "穗" to "广州",
        "鹏城" to "深圳",
        "蓉" to "成都",
        "汉" to "武汉",
        "杭" to "杭州",
        "宁" to "南京",
        
        // 带"市"后缀
        "北京市" to "北京",
        "上海市" to "上海",
        "天津市" to "天津",
        "重庆市" to "重庆",
        "广州市" to "广州",
        "深圳市" to "深圳",
        "成都市" to "成都",
        "武汉市" to "武汉",
        "杭州市" to "杭州",
        "南京市" to "南京",
        "西安市" to "西安",
        "长沙市" to "长沙",
        "郑州市" to "郑州",
        "济南市" to "济南",
        "青岛市" to "青岛",
        "大连市" to "大连",
        "厦门市" to "厦门",
        "昆明市" to "昆明",
        "三亚市" to "三亚",
        "海口市" to "海口"
    )
    
    /**
     * 获取城市的 IATA 代码
     * 
     * @param cityName 城市名（支持简称、别名、带"市"后缀）
     * @return IATA 3字母代码，未找到返回 null
     */
    fun getIataCode(cityName: String): String? {
        val normalizedCity = normalizeCity(cityName)
        return cityToIataMap[normalizedCity]
    }
    
    /**
     * 检查是否支持该城市
     */
    fun isCitySupported(cityName: String): Boolean {
        return getIataCode(cityName) != null
    }
    
    /**
     * 获取所有支持的城市列表
     */
    fun getSupportedCities(): List<String> = cityToIataMap.keys.toList()
    
    /**
     * 标准化城市名（处理别名和后缀）
     */
    fun normalizeCity(cityName: String): String {
        val trimmed = cityName.trim()
        // 先检查别名
        cityAliasMap[trimmed]?.let { return it }
        // 移除常见后缀
        return trimmed
            .removeSuffix("市")
            .removeSuffix("区")
    }
    
    /**
     * 模糊匹配城市名
     * 
     * @param input 用户输入
     * @return 匹配到的城市名，未匹配返回 null
     */
    fun fuzzyMatchCity(input: String): String? {
        val normalized = normalizeCity(input)
        
        // 精确匹配
        if (cityToIataMap.containsKey(normalized)) {
            return normalized
        }
        
        // 包含匹配（如 "北京首都" 匹配 "北京"）
        cityToIataMap.keys.forEach { city ->
            if (normalized.contains(city) || city.contains(normalized)) {
                return city
            }
        }
        
        return null
    }
    
    // ========== 火车站代码相关 ==========
    // 注：12306 有自己的 API (get-station-code-of-city) 来查询车站代码
    // 这里提供一些常用映射作为备用
    
    /**
     * 常用火车站名 → 电报码映射
     * 
     * 注：12306 MCP 服务有 get-station-code-of-city 工具
     * 这里仅作为 fallback 或快速查询
     */
    private val commonStationCodes = mapOf(
        "北京" to "BJP",
        "北京西" to "BXP",
        "北京南" to "VNP",
        "北京北" to "VAP",
        "上海" to "SHH",
        "上海虹桥" to "AOH",
        "上海南" to "SNH",
        "广州" to "GZQ",
        "广州南" to "IZQ",
        "广州东" to "GGQ",
        "深圳" to "SZQ",
        "深圳北" to "IOQ",
        "天津" to "TJP",
        "天津西" to "TXP",
        "南京" to "NJH",
        "南京南" to "NKH",
        "杭州" to "HZH",
        "杭州东" to "HGH",
        "武汉" to "WHN",
        "西安" to "XAY",
        "西安北" to "EAY",
        "成都" to "CDW",
        "成都东" to "ICW",
        "重庆" to "CQW",
        "重庆北" to "CUW",
        "长沙" to "CSQ",
        "长沙南" to "CWQ"
    )
    
    /**
     * 获取火车站电报码（fallback）
     * 
     * 注意：优先使用 12306 MCP 的 get-station-code-of-city
     */
    fun getStationCode(stationName: String): String? {
        val normalized = stationName.trim().removeSuffix("站")
        return commonStationCodes[normalized]
    }
}

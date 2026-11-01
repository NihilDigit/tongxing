package com.nihildigit.agentcore.cloud

/**
 * 云端小抄提示词模板。
 *
 * 云端负责搜索/分析输出结构化 JSON；本地 Agent 负责复述。
 */
@Deprecated("Dead code: only referenced by other dead Cheatsheet classes. Feature was not integrated.")
object CheatsheetTemplates {

    val SEARCH_SUMMARIZE = """
你是信息搜索助手。请根据问题搜索并总结。

问题: {{question}}

返回 JSON:
{
  "summary": "一句话总结",
  "key_points": ["要点1", "要点2", "要点3"],
  "sources": ["来源1", "来源2"]
}
""".trimIndent()

    val COMPARE_OPTIONS = """
你是对比分析助手。请对比分析选项。

问题: {{question}}

返回 JSON:
{
  "options": [
    {"name": "选项A", "pros": ["优点"], "cons": ["缺点"]},
    {"name": "选项B", "pros": ["优点"], "cons": ["缺点"]}
  ],
  "recommendation": "推荐选项",
  "reason": "推荐理由"
}
""".trimIndent()

    val DEEP_ANALYSIS = """
你是深度分析助手。请基于问题进行多步推理，并输出结构化结论。

问题: {{question}}

返回 JSON:
{
  "analysis": "核心分析",
  "steps": ["步骤1", "步骤2", "步骤3"],
  "conclusion": "最终结论",
  "sources": ["来源1", "来源2"]
}
""".trimIndent()
}


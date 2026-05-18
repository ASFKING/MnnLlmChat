package com.poc.ondevice.util

import org.json.JSONObject
import org.json.JSONArray

/**
 * JsonUtils - JSON 提取和校验工具
 *
 * 职责：
 * 1. 从 LLM 的"不完美输出"中提取 JSON（LLM 经常在 JSON 前后加废话）
 * 2. 校验 JSON 格式是否正确
 * 3. 构建结构化提取的 prompt 模板
 *
 * 为什么要这个工具？
 * LLM 不是严格的 JSON 生成器——它可能会：
 * - 在 JSON 前面加一句"好的，以下是提取结果："
 * - 在 JSON 后面加一句"以上是提取的信息"
 * - 用 markdown 代码块包裹 JSON（\`\`\`json ... \`\`\`）
 * - 偶尔输出格式错误的 JSON（缺少引号、多余逗号等）
 *
 * JsonUtils 就是来处理这些"不完美"的
 */
object JsonUtils {

    // ==================== JSON 提取 ====================

    /**
     * 从 LLM 输出中提取 JSON 字符串
     *
     * 处理策略（按优先级）：
     * 1. 尝试直接解析整个文本为 JSON
     * 2. 提取 markdown 代码块中的 JSON
     * 3. 用正则匹配第一个 { ... } 或 [ ... ]
     *
     * @param text LLM 的原始输出
     * @return 提取出的 JSON 字符串，如果提取失败返回 null
     */
    fun extractJson(text: String): String? {
        if (text.isBlank()) return null

        val trimmed = text.trim()

        // 策略 1：直接尝试解析（LLM 可能直接输出了纯 JSON）
        if (isValidJson(trimmed)) return trimmed

        // 策略 2：提取 markdown 代码块
        // 正则匹配 ```json ... ``` 或 ``` ... ```
        // (?:json)? 表示 "json" 是可选的
        // [\\s\\S]*? 表示匹配任意字符（非贪婪模式）
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val codeBlockMatch = codeBlockRegex.find(trimmed)
        if (codeBlockMatch != null) {
            val jsonCandidate = codeBlockMatch.groupValues[1].trim()
            if (isValidJson(jsonCandidate)) return jsonCandidate
        }

        // 策略 3：用正则匹配第一个 JSON 对象或数组
        // 匹配 { ... }（对象）或 [ ... ]（数组）
        // 使用非贪婪匹配，并处理嵌套的大括号/方括号
        val jsonObjectRegex = Regex("\\{[\\s\\S]*\\}")
        val jsonArrayRegex = Regex("\\[[\\s\\S]*\\]")

        for (regex in listOf(jsonObjectRegex, jsonArrayRegex)) {
            val match = regex.find(trimmed)
            if (match != null) {
                val jsonCandidate = match.value
                if (isValidJson(jsonCandidate)) return jsonCandidate
            }
        }

        return null
    }

    /**
     * 校验字符串是否为合法 JSON
     *
     * 使用 Android 自带的 org.json.JSONObject / JSONArray
     * 如果解析不抛异常，就是合法 JSON
     *
     * @param text 要校验的字符串
     * @return true=合法 JSON，false=不合法
     */
    fun isValidJson(text: String): Boolean {
        return try {
            // 尝试作为对象解析
            JSONObject(text)
            true
        } catch (_: Exception) {
            try {
                // 尝试作为数组解析
                JSONArray(text)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // ==================== Prompt 模板 ====================

    /**
     * 构建结构化提取的 prompt
     *
     * 设计原则：
     * 1. 明确告诉 LLM 它的角色（信息提取助手）
     * 2. 给出输入文本
     * 3. 指定输出格式（JSON）
     * 4. 给一个示例（Few-shot），让 LLM 理解期望的输出
     * 5. 用低温度（0.1）确保输出稳定
     *
     * @param text       要提取信息的原始文本
     * @param schemaHint 可选的 schema 提示（告诉 LLM 要提取哪些字段）
     * @return 完整的 prompt 字符串
     */
    fun buildExtractPrompt(text: String, schemaHint: String = ""): String {
        val schemaSection = if (schemaHint.isNotBlank()) {
            "\n需要提取的字段：$schemaHint"
        } else {
            ""
        }

        return """你是一个信息提取助手。请从以下文本中提取结构化信息，输出纯 JSON 格式。
不要输出任何解释、注释或多余文字，只输出 JSON。$schemaSection

示例：
输入：张三的药品经营许可证编号是YP2024001，2024年1月15日签发，有效期至2029年1月14日。
输出：{"name":"张三","license_type":"药品经营许可证","license_number":"YP2024001","issue_date":"2024-01-15","expiry_date":"2029-01-14"}

现在请提取：
输入：$text
输出："""
    }

    /**
     * 构建文档生成的 prompt
     *
     * @param docType   文档类型（如"通知"、"报告"、"总结"）
     * @param inputData 输入数据（用户提供的关键信息）
     * @return 完整的 prompt 字符串
     */
    fun buildGeneratePrompt(docType: String, inputData: String): String {
        return """你是一个文档生成助手。请根据以下信息，生成一份规范的${docType}文档。
要求：格式规范、语言正式、内容完整。直接输出文档内容，不要加额外说明。

信息：$inputData

${docType}："""
    }
}

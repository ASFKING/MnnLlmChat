package com.poc.ondevice.engine

import android.util.Log
import kotlinx.coroutines.flow.Flow

/**
 * RAGEngine：检索增强生成引擎
 *
 * 职责：
 * - 把文档索引到向量库（分块 → 嵌入 → 存储）
 * - 接收用户问题，检索相关文档，组装 prompt，让 LLM 生成回答
 *
 * 一句话定义：先从书里找相关段落，再用这些段落当参考来回答问题。
 *
 * 生活类比：RAG 就像"开卷考试"。
 * - 闭卷考试：LLM 只能靠自己记住的知识回答（可能记错/编造）
 * - 开卷考试：先翻书找到相关章节，再根据书里的内容回答（更准确）
 *
 * RAG 的完整流程：
 * [用户提问] → 嵌入 → 向量搜索 → 找到相关文档片段
 * → 组装 prompt（系统指令 + 参考文档 + 用户问题）
 * → LLM 生成回答（基于参考文档，不是凭空编造）
 *
 * 为什么 RAG 能减少"幻觉"？
 * 因为我们在 prompt 里给了 LLM 具体的参考资料，
 * LLM 的任务从"回忆知识"变成了"阅读材料后回答"，
 * 就像开卷考试比闭卷考试更容易拿高分。
 */
class RAGEngine(
    /** embeddingEngine：把文本变成向量 */
    private val embeddingEngine: EmbeddingEngine,
    /** vectorStore：存储和检索向量 */
    private val vectorStore: VectorStore,
    /** llmEngine：大语言模型推理 */
    private val llmEngine: LLMEngine
) {

    // ==================== 文档索引 ====================

    /**
     * 索引一篇文档：分块 → 逐块嵌入 → 存入向量库
     *
     * 为什么要分块？
     * 1. 嵌入模型有最大长度限制（bge 最大 512 token）
     * 2. 长文本的嵌入会"稀释"关键信息，短块的嵌入更精确
     * 3. 搜索时能精确定位到相关段落，而不是返回整篇文章
     *
     * 为什么要重叠（overlap）？
     * 如果在句号处硬切，可能把一个完整的意思切成两半。
     * 重叠确保每个块的头尾都有上下文，不会丢失语义。
     *
     * 生活类比：你看书时用便利贴标记重点段落。
     * 如果一段话跨了两页，你会在两页都贴便利贴，而不是只贴一页。
     *
     * @param title 文档标题（存入元数据，方便后续展示来源）
     * @param content 文档全文
     * @return 成功索引的分块数量
     */
    suspend fun indexDocument(title: String, content: String): Int {
        Log.d(TAG, "开始索引文档: $title (${content.length} 字)")

        // Step 1: 把长文本切成多个小块
        val chunks = splitChunks(content)
        Log.d(TAG, "分块完成: ${chunks.size} 个块")

        // Step 2: 对每个块计算嵌入向量，存入向量库
        var indexedCount = 0
        for ((index, chunk) in chunks.withIndex()) {
            // encode() 把文本变成 512 维向量
            val embedding = embeddingEngine.encode(chunk)
            if (embedding != null) {
                // add() 把向量 + 文本 + 元数据存入内存
                vectorStore.add(
                    embedding = embedding,
                    text = chunk,
                    meta = mapOf(
                        "title" to title,                    // 文档标题
                        "chunkIndex" to index.toString()     // 分块序号
                    )
                )
                indexedCount++
            } else {
                Log.w(TAG, "块 $index 嵌入失败，跳过")
            }
        }

        Log.d(TAG, "文档索引完成: $title, $indexedCount/${chunks.size} 块成功")
        return indexedCount
    }

    // ==================== 文档分块 ====================

    /**
     * 把长文本切成多个小块（带重叠）
     *
     * 分块策略：
     * 1. 先按段落（\n\n）分割
     * 2. 短段落直接作为一块
     * 3. 长段落按句号再切分
     * 4. 每块之间保留 overlap 个字符的重叠
     * 5. 过滤掉太短的块（< 50 字，信息量太少）
     *
     * @param content 文档全文
     * @param chunkSize 每块最大字符数（默认 500）
     * @param overlap 块之间的重叠字符数（默认 100）
     * @return 分块后的文本列表
     */
    private fun splitChunks(
        content: String,
        chunkSize: Int = 500,
        overlap: Int = 100
    ): List<String> {
        // 按段落分割（连续两个换行符）
        // .filter { it.isNotBlank() } 过滤掉空段落
        val paragraphs = content.split("\n\n").filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()

        for (para in paragraphs) {
            if (para.length <= chunkSize) {
                // 短段落：直接作为一块
                chunks.add(para)
            } else {
                // 长段落：按句号切分
                // split() 用正则按中英文句号、感叹号、问号分割
                val sentences = para.split("。", "！", "？", ".", "!", "?")
                var current = ""

                for (sent in sentences) {
                    if (sent.isBlank()) continue  // 跳过空句子

                    if (current.length + sent.length > chunkSize && current.isNotBlank()) {
                        // 当前块加上这个句子会超长，先把当前块存起来
                        chunks.add(current)
                        // 新块开头保留 overlap 个字符作为上下文
                        // .takeLast(overlap) 取当前文本最后 overlap 个字符
                        current = current.takeLast(overlap) + sent
                    } else {
                        // 没超长，继续拼接
                        current += sent
                    }
                }
                // 最后一块不要忘了加进去
                if (current.isNotBlank()) chunks.add(current)
            }
        }

        // 过滤掉太短的块（< 50 字），信息量太少，检索到了也没用
        return chunks.filter { it.length >= 50 }
    }

    // ==================== RAG 问答 ====================

    /**
     * RAG 问答：检索相关文档 → 组装 prompt → LLM 流式生成回答
     *
     * 完整流程：
     * 1. 把用户问题编码为向量
     * 2. 在向量库中搜索最相似的 topK 个文档块
     * 3. 过滤掉相似度太低的（< 0.3，说明不相关）
     * 4. 把相关文档块组装成 prompt 的"参考文档"部分
     * 5. 拼接完整的 prompt（系统指令 + 参考文档 + 用户问题）
     * 6. 调用 LLM 流式生成回答
     *
     * @param question 用户的问题
     * @return LLM 流式输出的 token Flow
     */
    suspend fun ask(question: String): Flow<String> {
        // Step 1: 把问题编码为向量
        val queryEmbedding = embeddingEngine.encode(question)
        if (queryEmbedding == null) {
            // 嵌入失败，直接用 LLM 回答（无参考文档）
            Log.w(TAG, "问题嵌入失败，降级为普通对话")
            return llmEngine.generateStream("请回答以下问题：$question")
        }

        // Step 2: 在向量库中搜索最相似的 3 个文档块
        val results = vectorStore.search(queryEmbedding, topK = 3)
            .filter { it.score > 0.3f }  // 过滤掉相似度 < 0.3 的（不相关）

        Log.d(TAG, "检索到 ${results.size} 个相关文档块")
        for (r in results) {
            Log.d(TAG, "  - [${"%.3f".format(r.score)}] ${r.text.take(50)}...")
        }

        // Step 3: 组装参考文档上下文
        // 如果没有检索到相关文档，context 为空字符串
        val context = if (results.isNotEmpty()) {
            results.mapIndexed { index, result ->
                // 格式化每条参考文档：编号 + 相似度 + 文本
                "【参考${index + 1}】(相关度: ${"%.2f".format(result.score)}) ${result.text}"
            }.joinToString("\n\n")  // 用空行分隔
        } else {
            ""
        }

        // Step 4: 组装完整的 prompt
        // 这个 prompt 告诉 LLM：
        // - 你是知识问答助手
        // - 参考以下文档回答问题
        // - 如果文档中没有相关信息，就明说"未找到"
        val prompt = if (context.isNotEmpty()) {
            """你是知识问答助手。请根据以下参考文档回答问题。

$context

问题：$question

要求：
1. 仅基于参考文档回答，不要编造
2. 如果参考文档中没有相关信息，请明确说明"参考文档中未找到相关信息"
3. 回答末尾标注来源（如：【参考1】）"""
        } else {
            // 没有参考文档，降级为普通对话
            "请回答以下问题：$question"
        }

        // Step 5: 调用 LLM 流式生成
        return llmEngine.generateStream(prompt)
    }

    /**
     * 获取向量库当前状态（用于 UI 显示）
     */
    fun getStats(): String {
        return "向量库: ${vectorStore.size} 条记录"
    }

    companion object {
        private const val TAG = "RAGEngine"
    }
}

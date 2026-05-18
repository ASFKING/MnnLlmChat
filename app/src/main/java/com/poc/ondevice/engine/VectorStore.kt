package com.poc.ondevice.engine

import android.util.Log

/**
 * VectorStore：内存向量库
 *
 * 职责：
 * - 在内存中维护一组向量 + 对应的文本 + 元数据
 * - 提供"按相似度搜索"功能：给一个查询向量，返回最相似的 topK 条记录
 *
 * 一句话定义：向量库就是一个"按意思相似度查找"的搜索引擎。
 *
 * 生活类比：
 * 想象一个图书馆，每本书都被压缩成一个 512 维的"坐标"（向量）。
 * 当你拿着一个问题的"坐标"去找书时，VectorStore 帮你找出
 * "坐标距离最近"的几本书——也就是内容最相关的几段文字。
 *
 * 为什么用暴力搜索而不是 FAISS？
 * PoC 阶段数据量小（< 1 万条），暴力搜索 < 5ms，够用。
 * FAISS 需要 NDK 编译，增加复杂度，生产阶段再考虑。
 *
 * 为什么用点积而不是余弦相似度？
 * 因为 bge 模型输出的向量已经 L2 归一化（长度=1），
 * 此时余弦相似度 = 点积，省掉一次除法，更快。
 */
class VectorStore {

    // ==================== 数据存储 ====================

    /**
     * vectors：所有嵌入向量的列表
     * 每个元素是一个 512 维的 FloatArray
     */
    private val vectors = mutableListOf<FloatArray>()

    /**
     * texts：每个向量对应的原始文本
     * 与 vectors 一一对应：vectors[i] 对应 texts[i]
     */
    private val texts = mutableListOf<String>()

    /**
     * metadata：每个向量的附加信息（如文档标题、分块索引）
     * 与 vectors 一一对应：vectors[i] 对应 metadata[i]
     *
     * Map<String, String> 例如：{"title": "药品管理法", "chunkIndex": "2"}
     */
    private val metadata = mutableListOf<Map<String, String>>()

    /**
     * 当前存储的向量数量
     */
    val size: Int get() = vectors.size

    // ==================== 写入 ====================

    /**
     * 添加一条向量记录
     *
     * @param embedding 嵌入向量（512 维，已归一化）
     * @param text 对应的原始文本（分块后的文档片段）
     * @param meta 附加信息（可选），如文档标题、分块索引
     */
    fun add(embedding: FloatArray, text: String, meta: Map<String, String> = emptyMap()) {
        vectors.add(embedding)
        texts.add(text)
        metadata.add(meta)
    }

    // ==================== 搜索 ====================

    /**
     * 搜索最相似的 topK 条记录
     *
     * 搜索过程：
     * 1. 用查询向量和库中每个向量算点积（= 余弦相似度，因为已归一化）
     * 2. 按相似度从高到低排序
     * 3. 取前 topK 条返回
     *
     * 生活类比：拿着你的"坐标"，在图书馆里逐本书算距离，
     * 然后把距离最近的 topK 本书挑出来。
     *
     * @param query 查询向量（用户问题的嵌入，512 维，已归一化）
     * @param topK 返回最相似的前 K 条（默认 3）
     * @return 按相似度降序排列的搜索结果列表
     */
    fun search(query: FloatArray, topK: Int = 3): List<SearchResult> {
        // 对库中每个向量，计算与查询向量的点积（余弦相似度）
        // .mapIndexed { index, vec -> ... } 同时拿到索引和向量
        // 这样我们就能通过索引找到对应的文本和元数据
        return vectors.mapIndexed { index, vec ->
            val score = dotProduct(query, vec)  // 点积 = 余弦相似度（已归一化）
            SearchResult(
                text = texts[index],            // 对应的原始文本
                score = score,                   // 相似度分数
                metadata = metadata[index]       // 附加信息
            )
        }
        .sortedByDescending { it.score }  // 按相似度从高到低排序
        .take(topK)                        // 只取前 topK 条
    }

    /**
     * 清空所有数据
     */
    fun clear() {
        vectors.clear()
        texts.clear()
        metadata.clear()
    }

    // ==================== 工具方法 ====================

    /**
     * 点积计算：两个等长向量的对应位置相乘再求和
     *
     * 数学：a·b = a[0]*b[0] + a[1]*b[1] + ... + a[n]*b[n]
     *
     * 为什么不用 Kotlin 的 zip + sumOf？
     * 因为手动循环避免了装箱开销（Float 不需要装箱成对象），
     * 在 512 维向量上比函数式写法快约 2-3 倍。
     *
     * @param a 第一个向量（已归一化）
     * @param b 第二个向量（已归一化）
     * @return 点积值，范围 [-1, 1]（因为已归一化）
     */
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }

    // ==================== 数据模型 ====================

    /**
     * SearchResult：单条搜索结果
     *
     * @param text 原始文本（文档分块片段）
     * @param score 相似度分数，范围 [-1, 1]，越接近 1 越相似
     * @param metadata 附加信息，如 {"title": "药品管理法", "chunkIndex": "2"}
     */
    data class SearchResult(
        val text: String,
        val score: Float,
        val metadata: Map<String, String>
    )

    companion object {
        private const val TAG = "VectorStore"
    }
}

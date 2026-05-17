package com.poc.ondevice.engine

import android.util.Log
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * EmbeddingEngine：文本嵌入引擎
 *
 * 职责：
 * - 加载 bge-small-zh-v1.5 的 ONNX 模型
 * - 把中文文本变成 512 维的归一化向量（嵌入/Embedding）
 * - 供 RAG 系统做语义检索用
 *
 * 一句话定义：把文字变成一组坐标数字，意思相近的文字坐标也相近。
 *
 * 生活类比：
 * 想象一个巨大的地图，每个词都是地图上的一个点。
 * "药品"和"药物"在这个地图上距离很近，"药品"和"天气"距离很远。
 * EmbeddingEngine 就是把文字放到这张地图上的工具。
 *
 * 技术流程（4 步）：
 * 文本 → 分词（切成 token） → 推理（送进 bge 模型） → 取 [CLS] 向量 → L2 归一化
 *
 * 为什么用 ONNX Runtime 而不是 MNN？
 * bge 是 BERT encoder 架构，MNN 的 LlmSession 只支持 decoder-only LLM。
 * 我们在 Day 3 已经踩过这个坑——Llm::load() 硬编码了 LLM 的输入输出名，
 * 与 BERT 的输入输出不兼容。ONNX Runtime 有官方 Java API，一行依赖引入。
 *
 * 为什么用 DJL HuggingFace Tokenizer？
 * 调查发现 onnxruntime-extensions 0.12.4 的 Java API 没有独立的分词器类。
 * 它的 tokenization 是通过自定义 ONNX 算子嵌入到模型图里的（需要特殊导出的模型）。
 * 而 bge 的标准 ONNX 模型期望 input_ids/attention_mask 作为输入，
 * 所以我们需要独立的分词器来生成 token ids。
 * DJL 的 HuggingFaceTokenizer 是纯 Java 实现，能直接加载 tokenizer.json。
 */
class EmbeddingEngine {

    // ==================== 核心属性 ====================

    /**
     * ortEnv：ONNX Runtime 的环境对象
     *
     * 类比：就像 Android 的 Context，是使用 ONNX Runtime 的入口
     * 全局只需要一个实例（单例模式）
     */
    private var ortEnv: OrtEnvironment? = null

    /**
     * ortSession：ONNX Runtime 的推理会话
     *
     * 内部持有 bge 模型的计算图和权重
     * 调用 run() 时执行前向推理
     */
    private var ortSession: OrtSession? = null

    /**
     * tokenizer：HuggingFace BPE 分词器
     *
     * DJL（Deep Java Library）提供的纯 Java 实现
     * 能加载 HuggingFace 格式的 tokenizer.json
     * 把中文文本切成 token ids（数字序列）
     *
     * 例如："药品经营许可证" → [101, 5765, 1234, 5678, ..., 102]
     * 101 = [CLS]（句子开头标记）
     * 102 = [SEP]（句子结尾标记）
     */
    private var tokenizer: HuggingFaceTokenizer? = null

    /**
     * isLoaded：模型是否已加载
     */
    val isLoaded: Boolean
        get() = ortSession != null && tokenizer != null

    /**
     * embeddingDim：嵌入向量维度
     * bge-small-zh-v1.5 输出 512 维向量
     */
    val embeddingDim: Int = 512

    // ==================== 模型加载 ====================

    /**
     * 加载嵌入模型和分词器
     *
     * @param modelDir 模型目录路径，包含 model.onnx 和 tokenizer.json
     * @return true=加载成功，false=加载失败
     *
     * suspend fun：挂起函数，不阻塞线程
     * withContext(Dispatchers.IO)：切换到 IO 线程池（文件读取密集型）
     */
    suspend fun load(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始加载嵌入模型，路径: $modelDir")

            // ===== Step 1: 检查文件是否存在 =====
            val modelPath = "$modelDir/model.onnx"
            val tokenizerPath = "$modelDir/tokenizer.json"

            val modelFile = java.io.File(modelPath)
            val tokenizerFile = java.io.File(tokenizerPath)

            if (!modelFile.exists()) {
                Log.e(TAG, "model.onnx 不存在！路径: $modelPath")
                Log.e(TAG, "模型目录内容:")
                java.io.File(modelDir).listFiles()?.forEach {
                    Log.e(TAG, "  - ${it.name} (${it.length()} bytes)")
                }
                return@withContext false
            }
            if (!tokenizerFile.exists()) {
                Log.e(TAG, "tokenizer.json 不存在！路径: $tokenizerPath")
                return@withContext false
            }

            Log.d(TAG, "model.onnx 大小: ${modelFile.length()} bytes")
            Log.d(TAG, "tokenizer.json 大小: ${tokenizerFile.length()} bytes")

            // ===== Step 2: 创建 ONNX Runtime 环境 =====
            // OrtEnvironment.getEnvironment()：获取全局单例
            // 整个 App 只需要一个 OrtEnvironment 实例
            ortEnv = OrtEnvironment.getEnvironment()
            Log.d(TAG, "OrtEnvironment 创建成功")

            // ===== Step 3: 创建推理会话 =====
            // createSession()：加载 ONNX 模型文件，创建推理会话
            // 这一步会解析模型的计算图结构，但不一定立即加载所有权重
            // 实际权重加载可能延迟到第一次推理时
            ortSession = ortEnv!!.createSession(modelPath)
            Log.d(TAG, "OrtSession 创建成功")

            // 打印模型的输入输出信息（调试用）
            ortSession?.let { session ->
                Log.d(TAG, "模型输入: ${session.inputNames}")
                Log.d(TAG, "模型输出: ${session.outputNames}")
            }

            // ===== Step 4: 加载分词器 =====
            // HuggingFaceTokenizer.newInstance()：DJL 提供的工厂方法
            // 能直接加载 HuggingFace 格式的 tokenizer.json
            // 内部是纯 Java 实现的 BPE/WordPiece 分词器，不需要 native 依赖
            // Android 兼容，不依赖 JNI
            tokenizer = HuggingFaceTokenizer.newInstance(
                java.nio.file.Paths.get(tokenizerPath)
            )
            Log.d(TAG, "HuggingFaceTokenizer 加载成功")

            Log.d(TAG, "嵌入模型加载完成！")
            true
        } catch (e: Exception) {
            Log.e(TAG, "嵌入模型加载失败", e)
            release()
            false
        }
    }

    // ==================== 文本编码 ====================

    /**
     * 把文本编码为嵌入向量
     *
     * 完整流程：
     * 1. 分词：文本 → token ids（数字序列）
     * 2. 构造输入张量：token ids + attention mask
     * 3. 推理：送进 bge 模型 → 输出 [1, seq_len, 512] 的张量
     * 4. 取 [CLS]：第一个 token 的 512 维输出就是整个句子的嵌入
     * 5. L2 归一化：把向量长度缩放到 1
     *
     * 为什么取 [CLS] 位置？
     * BERT 模型在训练时，[CLS] token 被设计为"代表整个句子的语义"
     * 它的输出向量就是整个句子的嵌入表示
     *
     * 为什么要做 L2 归一化？
     * 归一化后，两个向量的余弦相似度 = 它们的点积
     * 点积计算比余弦相似度快（不需要除法），后续检索时性能更好
     *
     * @param text 输入文本
     * @return 512 维的归一化浮点数组
     */
    suspend fun encode(text: String): FloatArray = withContext(Dispatchers.Default) {
        val env = ortEnv ?: throw IllegalStateException("模型未加载，请先调用 load()")
        val session = ortSession ?: throw IllegalStateException("模型未加载，请先调用 load()")
        val tok = tokenizer ?: throw IllegalStateException("分词器未加载，请先调用 load()")

        // ===== Step 1: 分词 =====
        // HuggingFaceTokenizer.encode()：把文本切成 token
        // 返回 Encoding 对象，包含 ids、attentionMask、typeIds 等
        // 例如："药品经营" → ids=[101, 5765, 1234, 5678, 102]
        val encoding = tok.encode(text)

        // getIds()：获取 token ids（long[]）
        // 这是模型的 input_ids 输入
        val inputIds = encoding.ids

        // getAttentionMask()：获取注意力掩码（long[]）
        // 1 = 真实 token，0 = padding
        // 我们一次只处理一条文本，没有 padding，所以全是 1
        val attentionMask = encoding.attentionMask

        // getTypeIds()：获取 token type ids（long[]）
        // BERT 用它区分两个句子（句子 A=0，句子 B=1）
        // bge 只处理单个句子，所以全是 0
        val tokenTypeIds = encoding.typeIds

        val seqLen = inputIds.size
        Log.v(TAG, "分词结果：\"$text\" → ${seqLen} 个 token")

        // ===== Step 2: 构造 ONNX 输入张量 =====
        // OnnxTensor.createTensor()：创建 ONNX 张量
        // 参数：环境对象、数据（LongBuffer）、形状（[1, seqLen]）
        // 为什么用 LongBuffer？因为 ONNX 的 int64 类型对应 Java 的 long
        val inputIdsTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(inputIds),
            longArrayOf(1, seqLen.toLong())  // 形状：[batch_size=1, sequence_length]
        )

        val attentionMaskTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(attentionMask),
            longArrayOf(1, seqLen.toLong())
        )

        val tokenTypeIdsTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tokenTypeIds),
            longArrayOf(1, seqLen.toLong())
        )

        // ===== Step 3: 执行推理 =====
        // session.run()：执行前向推理
        // 参数：输入名称 → 张量 的 Map
        // bge 模型的输入名：input_ids, attention_mask, token_type_ids
        // 返回：推理结果（包含输出张量）
        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor,
            "token_type_ids" to tokenTypeIdsTensor
        )

        val results = session.run(inputs)

        // ===== Step 4: 取 [CLS] 位置的输出 =====
        // results[0]：第一个输出张量（last_hidden_state）
        // 形状：[1, seq_len, 512]
        // 我们要取 [0][0]（即 [CLS] 位置）→ [512]
        //
        // getValue() 返回 Array<FloatArray>（二维浮点数组）
        // 第一维是 batch（只有 1 条），第二维是 hidden_size（512）
        val lastHiddenState = results[0].value as Array<Array<FloatArray>>
        // lastHiddenState[0]：batch 0 的所有 token 输出 → [seq_len, 512]
        // lastHiddenState[0][0]：[CLS] token 的输出 → [512]
        val clsEmbedding = lastHiddenState[0][0]

        // ===== Step 5: L2 归一化 =====
        // 把向量长度缩放到 1，这样余弦相似度 = 点积
        val normalized = l2Normalize(clsEmbedding)

        // 释放张量资源（避免内存泄漏）
        inputIdsTensor.close()
        attentionMaskTensor.close()
        tokenTypeIdsTensor.close()
        results.close()

        normalized
    }

    /**
     * 批量编码：一次处理多条文本
     *
     * 比逐条调用 encode() 更高效的原因：
     * 1. 只需要加载一次模型
     * 2. 可以批量推理（如果模型支持动态 batch）
     *
     * @param texts 文本列表
     * @return 嵌入向量列表（与 texts 一一对应）
     */
    suspend fun encodeBatch(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.Default) {
            texts.map { encode(it) }
        }

    // ==================== 工具方法 ====================

    /**
     * L2 归一化：把向量长度缩放到 1
     *
     * 数学公式：normalized[i] = vector[i] / ||vector||
     * 其中 ||vector|| = sqrt(sum(vector[i]^2))
     *
     * 为什么叫"L2"？
     * L2 是"欧几里得范数"的简称，就是我们通常说的"向量长度"
     * L2 归一化 = 把向量缩放到单位长度（长度为 1）
     *
     * @param vector 输入向量
     * @return 归一化后的向量（长度为 1）
     */
    private fun l2Normalize(vector: FloatArray): FloatArray {
        // 计算向量的 L2 范数（长度）
        var sum = 0.0
        for (v in vector) {
            sum += v.toDouble() * v.toDouble()
        }
        val norm = sqrt(sum).toFloat()

        // 避免除以 0（如果向量全为 0）
        if (norm == 0f) {
            Log.w(TAG, "零向量，无法归一化")
            return vector
        }

        // 每个分量除以范数
        return FloatArray(vector.size) { vector[it] / norm }
    }

    // ==================== 资源释放 ====================

    /**
     * 释放所有资源
     *
     * 为什么需要手动释放？
     * ONNX Runtime 的 OrtSession 持有 Native 内存
     * DJL 的 HuggingFaceTokenizer 也持有内部资源
     * Kotlin 的 GC 看不到这些内存，必须手动 close()
     */
    fun release() {
        try {
            ortSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 OrtSession 异常", e)
        }
        try {
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 OrtEnvironment 异常", e)
        }
        try {
            tokenizer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 HuggingFaceTokenizer 异常", e)
        }
        ortSession = null
        ortEnv = null
        tokenizer = null
        Log.d(TAG, "嵌入模型资源已释放")
    }

    companion object {
        private const val TAG = "EmbeddingEngine"
    }
}

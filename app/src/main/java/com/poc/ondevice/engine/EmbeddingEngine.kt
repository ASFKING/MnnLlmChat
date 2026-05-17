package com.poc.ondevice.engine

import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.poc.ondevice.util.SimpleTokenizer
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
 * 技术流程（4 步）：
 * 文本 → 分词（切成 token） → 推理（送进 bge 模型） → 取 [CLS] 向量 → L2 归一化
 *
 * 分词器方案：
 * DJL 的 HuggingFaceTokenizer 依赖 Rust JNI，在 Android 上无法加载 native 库。
 * 我们改用纯 Kotlin 实现的 SimpleTokenizer（util/SimpleTokenizer.kt），
 * 它能解析 HuggingFace 的 tokenizer.json，实现 BERT WordPiece 分词。
 */
class EmbeddingEngine {

    // ==================== 核心属性 ====================

    /** ortEnv：ONNX Runtime 的环境对象（全局单例） */
    private var ortEnv: OrtEnvironment? = null

    /** ortSession：ONNX Runtime 的推理会话 */
    private var ortSession: OrtSession? = null

    /** tokenizer：纯 Kotlin 实现的 BERT WordPiece 分词器 */
    private var tokenizer: SimpleTokenizer? = null

    /** isLoaded：模型是否已加载 */
    val isLoaded: Boolean
        get() = ortSession != null && tokenizer != null

    /** embeddingDim：嵌入向量维度（bge-small-zh-v1.5 输出 512 维） */
    val embeddingDim: Int = 512

    // ==================== 模型加载 ====================

    /**
     * 加载嵌入模型和分词器
     *
     * @param modelDir 模型目录路径，包含 model.onnx 和 tokenizer.json
     * @return true=加载成功，false=加载失败
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
            ortEnv = OrtEnvironment.getEnvironment()
            Log.d(TAG, "OrtEnvironment 创建成功")

            // ===== Step 3: 创建推理会话 =====
            ortSession = ortEnv!!.createSession(modelPath)
            Log.d(TAG, "OrtSession 创建成功")

            // 打印模型的输入输出信息（调试用）
            ortSession?.let { session ->
                Log.d(TAG, "模型输入: ${session.inputNames}")
                Log.d(TAG, "模型输出: ${session.outputNames}")
            }

            // ===== Step 4: 加载分词器 =====
            // 使用纯 Kotlin 实现的 SimpleTokenizer
            // 它解析 tokenizer.json，实现 BERT WordPiece 分词
            // 不依赖任何 native 库，Android 完全兼容
            tokenizer = SimpleTokenizer(tokenizerPath)
            if (tokenizer?.isLoaded() != true) {
                Log.e(TAG, "分词器加载失败！词表为空")
                return@withContext false
            }
            Log.d(TAG, "SimpleTokenizer 加载成功，词表大小: ${tokenizer?.vocabSize()}")

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
     * @param text 输入文本
     * @return 512 维的归一化浮点数组
     */
    suspend fun encode(text: String): FloatArray = withContext(Dispatchers.Default) {
        val env = ortEnv ?: throw IllegalStateException("模型未加载，请先调用 load()")
        val session = ortSession ?: throw IllegalStateException("模型未加载，请先调用 load()")
        val tok = tokenizer ?: throw IllegalStateException("分词器未加载，请先调用 load()")

        // ===== Step 1: 分词 =====
        // SimpleTokenizer.encode() 把文本切成 token ids
        // 包含 [CLS] 在开头和 [SEP] 在结尾
        val inputIds = tok.encode(text)
        val seqLen = inputIds.size

        // 构造 attention mask（全是 1，没有 padding）
        val attentionMask = LongArray(seqLen) { 1L }

        // 构造 token type ids（全是 0，单句输入）
        val tokenTypeIds = LongArray(seqLen) { 0L }

        Log.v(TAG, "分词结果：\"$text\" → $seqLen 个 token")

        // ===== Step 2: 构造 ONNX 输入张量 =====
        val inputIdsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen.toLong())
        )
        val attentionMaskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(attentionMask), longArrayOf(1, seqLen.toLong())
        )
        val tokenTypeIdsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(tokenTypeIds), longArrayOf(1, seqLen.toLong())
        )

        // ===== Step 3: 执行推理 =====
        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor,
            "token_type_ids" to tokenTypeIdsTensor
        )
        val results = session.run(inputs)

        // ===== Step 4: 取 [CLS] 位置的输出 =====
        // 输出形状：[1, seq_len, 512]
        // 取 [0][0] 即 [CLS] token 的 512 维向量
        val lastHiddenState = results[0].value as Array<Array<FloatArray>>
        val clsEmbedding = lastHiddenState[0][0]

        // ===== Step 5: L2 归一化 =====
        val normalized = l2Normalize(clsEmbedding)

        // 释放张量资源
        inputIdsTensor.close()
        attentionMaskTensor.close()
        tokenTypeIdsTensor.close()
        results.close()

        normalized
    }

    /**
     * 批量编码
     */
    suspend fun encodeBatch(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.Default) {
            texts.map { encode(it) }
        }

    // ==================== 工具方法 ====================

    /**
     * L2 归一化：把向量长度缩放到 1
     */
    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) {
            sum += v.toDouble() * v.toDouble()
        }
        val norm = sqrt(sum).toFloat()
        if (norm == 0f) {
            Log.w(TAG, "零向量，无法归一化")
            return vector
        }
        return FloatArray(vector.size) { vector[it] / norm }
    }

    // ==================== 资源释放 ====================

    fun release() {
        try { ortSession?.close() } catch (e: Exception) { Log.w(TAG, "关闭 OrtSession 异常", e) }
        try { ortEnv?.close() } catch (e: Exception) { Log.w(TAG, "关闭 OrtEnvironment 异常", e) }
        ortSession = null
        ortEnv = null
        tokenizer = null
        Log.d(TAG, "嵌入模型资源已释放")
    }

    companion object {
        private const val TAG = "EmbeddingEngine"
    }
}

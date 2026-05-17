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
 * 文本 → 分词（切成 token） → 推理（送进 bge 模型） → 均值池化（所有 token 向量取平均） → L2 归一化
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
                lastError = "model.onnx 不存在: $modelPath"
                return@withContext false
            }
            if (!tokenizerFile.exists()) {
                Log.e(TAG, "tokenizer.json 不存在！路径: $tokenizerPath")
                lastError = "tokenizer.json 不存在: $tokenizerPath"
                return@withContext false
            }

            // 检查文件大小（防止下载不完整）
            if (modelFile.length() < 1000) {
                Log.e(TAG, "model.onnx 文件过小（${modelFile.length()} bytes），可能下载不完整")
                lastError = "model.onnx 文件过小，可能下载不完整"
                return@withContext false
            }
            if (tokenizerFile.length() < 100) {
                Log.e(TAG, "tokenizer.json 文件过小（${tokenizerFile.length()} bytes），可能下载不完整")
                lastError = "tokenizer.json 文件过小"
                return@withContext false
            }

            Log.d(TAG, "model.onnx 大小: ${modelFile.length()} bytes")
            Log.d(TAG, "tokenizer.json 大小: ${tokenizerFile.length()} bytes")

            // ===== Step 2: 先加载分词器（纯 Kotlin，不会 native 崩溃）=====
            tokenizer = try {
                SimpleTokenizer(tokenizerPath)
            } catch (e: Exception) {
                Log.e(TAG, "SimpleTokenizer 构造异常", e)
                lastError = "分词器构造失败: ${e.message}"
                null
            }
            if (tokenizer?.isLoaded() != true) {
                Log.e(TAG, "分词器加载失败！原因: ${tokenizer?.loadError() ?: "构造异常"}")
                lastError = "分词器加载失败: ${tokenizer?.loadError() ?: "构造异常"}"
                return@withContext false
            }
            Log.d(TAG, "SimpleTokenizer 加载成功，词表大小: ${tokenizer?.vocabSize()}")

            // ===== Step 3: 创建 ONNX Runtime 环境 =====
            ortEnv = try {
                OrtEnvironment.getEnvironment()
            } catch (e: Exception) {
                Log.e(TAG, "OrtEnvironment 创建失败", e)
                lastError = "ONNX Runtime 环境创建失败: ${e.message}"
                return@withContext false
            }
            Log.d(TAG, "OrtEnvironment 创建成功")

            // ===== Step 4: 创建推理会话（最可能 native 崩溃的地方）=====
            ortSession = try {
                ortEnv!!.createSession(modelPath)
            } catch (e: Exception) {
                Log.e(TAG, "OrtSession 创建失败", e)
                lastError = "ONNX 模型加载失败: ${e.javaClass.simpleName}: ${e.message}"
                return@withContext false
            }
            Log.d(TAG, "OrtSession 创建成功")

            // 打印模型的输入输出信息（调试用）
            ortSession?.let { session ->
                inputNames = try { session.inputNames.toList() } catch (e: Exception) { emptyList() }
                outputNames = try { session.outputNames.toList() } catch (e: Exception) { emptyList() }
                Log.d(TAG, "模型输入名称: $inputNames")
                Log.d(TAG, "模型输出名称: $outputNames")
                try {
                    val allInputInfo = session.inputInfo
                    for ((name, info) in allInputInfo) {
                        Log.d(TAG, "  输入 '$name': $info")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  获取输入信息失败: ${e.message}")
                }
                try {
                    val allOutputInfo = session.outputInfo
                    for ((name, info) in allOutputInfo) {
                        Log.d(TAG, "  输出 '$name': $info")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  获取输出信息失败: ${e.message}")
                }
            }

            Log.d(TAG, "嵌入模型加载完成！")
            lastError = null
            true
        } catch (e: Throwable) {
            // 捕获 Throwable 而不是 Exception，能捕获 Error 和 native 崩溃
            Log.e(TAG, "嵌入模型加载失败（Throwable）", e)
            lastError = "加载异常: ${e.javaClass.simpleName}: ${e.message}"
            release()
            false
        }
    }

    // ==================== 文本编码 ====================

    /**
     * 最近一次错误信息（调试用）
     */
    var lastError: String? = null
        private set

    /**
     * 模型输入名称列表（调试用，load 成功后可读取）
     */
    var inputNames: List<String> = emptyList()
        private set

    /**
     * 模型输出名称列表（调试用，load 成功后可读取）
     */
    var outputNames: List<String> = emptyList()
        private set

    /**
     * 输出张量的形状（调试用，第一次 encode 后可读取）
     */
    var outputShape: String = ""
        private set

    /**
     * 把文本编码为嵌入向量
     *
     * 完整流程：
     * 1. 分词：文本 → token ids（数字序列）
     * 2. 构造输入张量：token ids + attention mask + token type ids
     * 3. 推理：送进 bge 模型 → 输出 [1, seq_len, 512] 的张量
     * 4. 均值池化（Mean Pooling）：把所有 token 的向量加权平均，用 attention_mask 排除 padding
     * 5. L2 归一化：把向量长度缩放到 1
     *
     * 为什么用均值池化而不是取 [CLS]？
     * bge 系列模型在训练时使用均值池化策略——把句子中所有有效 token 的隐藏状态取平均。
     * [CLS] token 虽然在 BERT 原始设计中用于句子表示，但 bge 的训练目标是让
     * 所有 token 的均值表示具有语义相似度。取 [CLS] 会丢失大量信息，导致相似度计算不准确。
     *
     * 生活类比：评价一个班级的水平，用全班平均分比只看班长的成绩更准确。
     *
     * 防御性设计：每一步都有 try-catch，出错时设置 lastError 而不是抛异常
     *
     * @param text 输入文本
     * @return 512 维的归一化浮点数组，失败返回 null
     */
    suspend fun encode(text: String): FloatArray? = withContext(Dispatchers.Default) {
        val env = ortEnv ?: run {
            lastError = "OrtEnvironment 未初始化"
            return@withContext null
        }
        val session = ortSession ?: run {
            lastError = "OrtSession 未初始化"
            return@withContext null
        }
        val tok = tokenizer ?: run {
            lastError = "分词器未初始化"
            return@withContext null
        }

        // 用于释放资源的张量列表
        val tensorsToClose = mutableListOf<AutoCloseable>()

        try {
            // ===== Step 1: 分词 =====
            val inputIds = tok.encode(text)
            val seqLen = inputIds.size

            if (seqLen == 0) {
                lastError = "分词结果为空（输入: \"$text\"）"
                return@withContext null
            }

            val attentionMask = LongArray(seqLen) { 1L }
            val tokenTypeIds = LongArray(seqLen) { 0L }

            Log.v(TAG, "分词结果：\"$text\" → $seqLen 个 token")

            // ===== Step 2: 构造 ONNX 输入张量 =====
            // 防御：逐个创建张量，捕获可能的错误
            val inputIdsTensor = try {
                OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen.toLong()))
            } catch (e: Exception) {
                lastError = "创建 input_ids 张量失败: ${e.message}"
                return@withContext null
            }
            tensorsToClose.add(inputIdsTensor)

            val attentionMaskTensor = try {
                OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), longArrayOf(1, seqLen.toLong()))
            } catch (e: Exception) {
                lastError = "创建 attention_mask 张量失败: ${e.message}"
                return@withContext null
            }
            tensorsToClose.add(attentionMaskTensor)

            val tokenTypeIdsTensor = try {
                OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), longArrayOf(1, seqLen.toLong()))
            } catch (e: Exception) {
                lastError = "创建 token_type_ids 张量失败: ${e.message}"
                return@withContext null
            }
            tensorsToClose.add(tokenTypeIdsTensor)

            // ===== Step 3: 执行推理 =====
            // 防御：模型可能不接受 token_type_ids（某些 ONNX 导出没有这个输入）
            val inputs: Map<String, OnnxTensor>
            try {
                // 先尝试用全部三个输入
                inputs = mapOf(
                    "input_ids" to inputIdsTensor,
                    "attention_mask" to attentionMaskTensor,
                    "token_type_ids" to tokenTypeIdsTensor
                )
            } catch (e: Exception) {
                lastError = "构造输入映射失败: ${e.message}"
                return@withContext null
            }

            val results = try {
                session.run(inputs)
            } catch (e: Exception) {
                // 如果三个输入失败，尝试只用两个（有些模型不需要 token_type_ids）
                Log.w(TAG, "三个输入推理失败，尝试只用 input_ids + attention_mask: ${e.message}")
                try {
                    val inputs2 = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor
                    )
                    session.run(inputs2)
                } catch (e2: Exception) {
                    lastError = "推理失败（尝试了两种输入组合）: ${e2.javaClass.simpleName}: ${e2.message}"
                    return@withContext null
                }
            }

            // ===== Step 4: 均值池化（Mean Pooling）=====
            // 为什么用均值池化？bge 模型训练时用的就是这个策略
            // 把所有有效 token（attention_mask=1）的向量求平均，再除以有效 token 数量
            // 这样得到的句子向量比只取 [CLS] 更能代表整个句子的语义
            val pooledEmbedding = try {
                extractMeanPooledEmbedding(results, attentionMask)
            } catch (e: Exception) {
                lastError = "均值池化失败: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, lastError!!, e)
                return@withContext null
            } finally {
                try { results.close() } catch (_: Exception) {}
            }

            if (pooledEmbedding == null) {
                // lastError 已经在 extractMeanPooledEmbedding 中设置了
                return@withContext null
            }

            // ===== Step 5: L2 归一化 =====
            // bge 模型输出的向量需要 L2 归一化后才能用余弦相似度（点积）比较
            val normalized = l2Normalize(pooledEmbedding)

            // 验证输出维度
            if (normalized.size != embeddingDim) {
                Log.w(TAG, "输出维度不匹配: 期望 $embeddingDim, 实际 ${normalized.size}")
            }

            normalized

        } catch (e: Exception) {
            lastError = "encode 异常: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError!!, e)
            null
        } finally {
            // 释放所有张量资源
            for (tensor in tensorsToClose) {
                try { tensor.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 从 ONNX 推理结果中提取均值池化（Mean Pooled）的嵌入向量
     *
     * 均值池化的数学过程：
     * 设模型输出为 H = [h₁, h₂, ..., hₙ]（n 个 token 的 512 维向量）
     * 设 attention_mask 为 M = [m₁, m₂, ..., mₙ]（1=有效，0=padding）
     *
     * pooled = (Σ hᵢ × mᵢ) / (Σ mᵢ)
     *
     * 即：把所有有效 token 的向量相加，除以有效 token 的数量
     *
     * 为什么不用 [CLS]？
     * bge 模型训练时用的就是均值池化，[CLS] 位置的向量不包含完整的句子语义。
     * 用 [CLS] 会导致相似文本的相似度只有 0.3 左右（应该 > 0.8）。
     *
     * 防御性设计：ONNX 模型的输出格式可能不同，逐层尝试解析
     *
     * @param results ONNX 推理结果
     * @param attentionMask attention mask 数组（1=有效 token，0=padding）
     * @return 512 维的池化向量，失败返回 null
     */
    private fun extractMeanPooledEmbedding(
        results: ai.onnxruntime.OrtSession.Result,
        attentionMask: LongArray
    ): FloatArray? {
        // 先打印输出信息（第一次调用时）
        if (outputShape.isEmpty()) {
            try {
                val outputTensor = results[0]
                val value = outputTensor.value
                outputShape = value?.let { describeType(it) } ?: "null"
                Log.d(TAG, "ONNX 输出类型: ${value?.javaClass?.name}")
                Log.d(TAG, "ONNX 输出描述: $outputShape")
            } catch (e: Exception) {
                Log.w(TAG, "获取输出信息失败: ${e.message}")
            }
        }

        val rawValue = results[0].value

        // ===== 尝试 1：三维数组 Array<Array<FloatArray>> =====
        // 输出形状：[batch=1, seq_len, hidden=512]
        // 这是最常见的 ONNX BERT 输出格式
        try {
            @Suppress("UNCHECKED_CAST")
            val arr3d = rawValue as Array<Array<FloatArray>>
            // arr3d[0] 取出 batch 维度后，得到 [seq_len, hidden]，即 Array<FloatArray>
            // 这正是 meanPool2D 需要的格式
            return meanPool2D(arr3d[0], attentionMask)
        } catch (e: ClassCastException) {
            Log.d(TAG, "输出不是 Array<Array<FloatArray>>，尝试其他方式...")
        }

        // ===== 尝试 2：一维展平 FloatArray =====
        // 某些 ONNX 导出会把三维张量展平成一维：[batch*seq_len*hidden]
        try {
            if (rawValue is FloatArray) {
                Log.d(TAG, "输出是一维 FloatArray，长度: ${rawValue.size}")
                // 计算 seq_len：总长度 / hidden_dim
                val seqLen = rawValue.size / embeddingDim
                if (seqLen > 0) {
                    // 把一维数组重塑为二维 [seq_len, hidden]
                    val reshaped = Array(seqLen) { i ->
                        rawValue.copyOfRange(i * embeddingDim, (i + 1) * embeddingDim)
                    }
                    return meanPool2D(reshaped, attentionMask)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "输出不是 FloatArray: ${e.message}")
        }

        // ===== 尝试 3：二维数组 Array<FloatArray> =====
        try {
            @Suppress("UNCHECKED_CAST")
            val arr2d = rawValue as Array<FloatArray>
            return meanPool2D(arr2d, attentionMask)
        } catch (e: ClassCastException) {
            Log.d(TAG, "输出不是 Array<FloatArray>，尝试其他方式...")
        }

        // ===== 尝试 4：反射分析类型 =====
        try {
            val clazz = rawValue?.javaClass
            Log.e(TAG, "无法解析 ONNX 输出。类型: ${clazz?.name}")
            if (clazz?.isArray == true) {
                val length = java.lang.reflect.Array.getLength(rawValue)
                Log.e(TAG, "数组长度: $length")
                if (length > 0) {
                    val element = java.lang.reflect.Array.get(rawValue, 0)
                    Log.e(TAG, "第一个元素类型: ${element?.javaClass?.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "反射分析失败: ${e.message}")
        }

        lastError = "无法从 ONNX 输出中提取嵌入向量（类型: ${rawValue?.javaClass?.name}）"
        return null
    }

    /**
     * 对三维输出的中间层做均值池化
     *
     * @param tokenEmbeddings [seq_len, hidden] 的二维数组，每个元素是一个 token 的 512 维向量
     * @param attentionMask [seq_len] 的 mask 数组，1=有效 token，0=padding
     * @return 512 维的均值池化向量
     */
    private fun meanPool2D(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val hiddenSize = embeddingDim  // 512
        val pooled = FloatArray(hiddenSize)
        var validTokenCount = 0f

        // 遍历每个 token
        for (i in tokenEmbeddings.indices) {
            // 只对有效 token（attention_mask=1）求和
            // padding token（attention_mask=0）不参与计算
            if (i < attentionMask.size && attentionMask[i] == 1L) {
                for (j in 0 until hiddenSize) {
                    pooled[j] += tokenEmbeddings[i][j]
                }
                validTokenCount += 1f
            }
        }

        // 除以有效 token 数量，得到平均值
        if (validTokenCount > 0f) {
            for (j in 0 until hiddenSize) {
                pooled[j] /= validTokenCount
            }
        } else {
            Log.w(TAG, "均值池化：没有有效 token！attentionMask=${attentionMask.contentToString()}")
        }

        Log.d(TAG, "均值池化完成：$validTokenCount 个有效 token，输出 $hiddenSize 维向量")
        return pooled
    }



    /**
     * 描述一个值的结构（调试用）
     */
    private fun describeType(value: Any): String {
        val clazz = value.javaClass
        if (!clazz.isArray) return clazz.simpleName

        val sb = StringBuilder()
        var c: Class<*> = clazz
        while (c.isArray) {
            sb.append("[]")
            c = c.componentType!!
        }
        return "${c.simpleName}${sb}"
    }

    /**
     * 批量编码
     * encode() 现在返回 FloatArray?（失败时返回 null）
     * encodeBatch 过滤掉 null，只返回成功的向量
     */
    suspend fun encodeBatch(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.Default) {
            texts.mapNotNull { encode(it) }
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

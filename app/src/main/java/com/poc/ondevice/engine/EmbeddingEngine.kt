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
                inputNames = session.inputNames.toList()
                outputNames = session.outputNames.toList()
                Log.d(TAG, "模型输入名称: $inputNames")
                Log.d(TAG, "模型输出名称: $outputNames")
                // getInputInfo() 返回 Map<String, NodeInfo>，用 key 查找
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
     * 2. 构造输入张量：token ids + attention mask
     * 3. 推理：送进 bge 模型 → 输出 [1, seq_len, 512] 的张量
     * 4. 取 [CLS]：第一个 token 的 512 维输出就是整个句子的嵌入
     * 5. L2 归一化：把向量长度缩放到 1
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

            // ===== Step 4: 取 [CLS] 位置的输出 =====
            // 防御：ONNX 输出的类型可能不一致，逐层尝试解析
            val clsEmbedding = try {
                extractClsEmbedding(results)
            } catch (e: Exception) {
                lastError = "提取 [CLS] 嵌入失败: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, lastError!!, e)
                return@withContext null
            } finally {
                try { results.close() } catch (_: Exception) {}
            }

            if (clsEmbedding == null) {
                // lastError 已经在 extractClsEmbedding 中设置了
                return@withContext null
            }

            // ===== Step 5: L2 归一化 =====
            val normalized = l2Normalize(clsEmbedding)

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
     * 从 ONNX 推理结果中提取 [CLS] 嵌入向量
     *
     * 防御性设计：ONNX 模型的输出格式可能不同
     * - 常见格式 1：float[][][] （三维浮点数组）
     * - 常见格式 2：OnnxTensor 对象（需要 getValue()）
     * - 常见格式 3：Map 包含命名输出
     *
     * 我们逐层尝试解析
     */
    private fun extractClsEmbedding(results: ai.onnxruntime.OrtSession.Result): FloatArray? {
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

        // ===== 尝试 1：直接作为 Array<Array<FloatArray>> =====
        try {
            @Suppress("UNCHECKED_CAST")
            val arr3d = rawValue as Array<Array<FloatArray>>
            // 输出形状：[batch=1, seq_len, hidden=512]
            // [0][0] = [CLS] token 的 512 维向量
            return arr3d[0][0]
        } catch (e: ClassCastException) {
            Log.d(TAG, "输出不是 Array<Array<FloatArray>>，尝试其他方式...")
        }

        // ===== 尝试 2：作为 FloatArray（一维展平） =====
        try {
            if (rawValue is FloatArray) {
                // 一维展平格式：前 512 个元素就是 [CLS] 的嵌入
                Log.d(TAG, "输出是一维 FloatArray，取前 $embeddingDim 个元素作为 [CLS]")
                return rawValue.copyOfRange(0, embeddingDim)
            }
        } catch (e: Exception) {
            Log.d(TAG, "输出不是 FloatArray: ${e.message}")
        }

        // ===== 尝试 3：作为 Array<FloatArray>（二维） =====
        try {
            @Suppress("UNCHECKED_CAST")
            val arr2d = rawValue as Array<FloatArray>
            return arr2d[0]
        } catch (e: ClassCastException) {
            Log.d(TAG, "输出不是 Array<FloatArray>，尝试其他方式...")
        }

        // ===== 尝试 4：用反射分析类型 =====
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

package com.alibaba.mnnllm.android.llm

import android.util.Log
import com.alibaba.mnnllm.android.llm.ChatService.Companion.provide
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import com.alibaba.mnnllm.android.model.ModelTypeUtils
import com.alibaba.mnnllm.android.modelsettings.ModelConfig.Companion.getExtraConfigFile
import com.google.gson.Gson
import timber.log.Timber
import java.io.File
import java.util.stream.Collectors
import kotlin.concurrent.Volatile
import android.util.Pair
import com.alibaba.mnnllm.android.utils.MmapUtils
import android.content.Context
import android.app.ActivityManager
import com.alibaba.mnnllm.android.modelsettings.Jinja
import com.alibaba.mnnllm.android.modelsettings.JinjaContext
import com.alibaba.mnnllm.android.modelsettings.ModelConfig.Companion.loadConfig
import com.alibaba.mnnllm.android.utils.FileSplitter
import com.alibaba.mnnllm.android.qnn.QnnModule

/**
 * LlmSession：LLM 推理会话（核心类）
 *
 * 一句话：管理一个 LLM 模型的完整生命周期——加载、推理、释放。
 * 生活类比：就像一个翻译员——上岗（加载）→ 翻译（推理）→ 下班（释放）。
 *
 * 这是整个项目最核心的类，所有 LLM 推理都经过它：
 * 1. load()：加载模型权重到内存，初始化 JNI 引擎
 * 2. generate()：接收 prompt，逐 token 生成回答
 * 3. reset()：清除 KV Cache，开始新对话
 * 4. release()：释放 native 内存
 *
 * 线程安全：
 * - generating 和 modelLoading 用 @Volatile 保证可见性
 * - release() 用 synchronized + wait/notify 保证安全释放
 * - generate() 用 synchronized 保证同一时刻只有一个推理任务
 */
class LlmSession(
    private val modelId: String,            // 模型 ID
    override var sessionId: String,          // 会话 ID
    private val configPath: String,          // config.json 的路径
    var savedHistory: List<ChatDataItem>?,   // 对话历史
    var backendType: String? = null,         // 推理后端（cpu/opencl/vulkan）
    private val useCustomConfig: Boolean = true  // 是否使用自定义配置
) : ChatSession {

    override var supportOmni: Boolean = false

    // nativePtr：C++ 层 MNN 推理引擎的指针
    // 0 表示未加载或已释放
    private var nativePtr: Long = 0

    // @Volatile：保证多线程可见性
    // 当一个线程修改了这些变量，其他线程立即看到最新值
    // 类比：公告栏——贴上去所有人都能看到
    @Volatile
    private var modelLoading = false    // 模型是否正在加载中

    @Volatile
    private var generating = false      // 是否正在生成中

    @Volatile
    private var releaseRequested = false  // 是否请求释放

    private var keepHistory = false     // 是否保留对话历史
    private var isQnn = false           // 是否使用 QNN NPU

    override fun getHistory(): List<ChatDataItem>? {
        return savedHistory
    }

    override fun setHistory(history: List<ChatDataItem>?) {
    }

    /**
     * load：加载模型
     *
     * 整个加载流程：
     * 1. 检查并合并分片文件（如果模型文件被拆分成多个小文件）
     * 2. 加载配置文件（config.json + custom_config.json）
     * 3. 设置 mmap 缓存目录
     * 4. 调用 JNI initNative() 初始化 C++ 引擎
     * 5. 检查 nativePtr 是否有效
     */
    override fun load() {
        Log.d(TAG, "MNN_DEBUG load begin modelId: $modelId backend: $backendType")
        modelLoading = true
        isQnn = ModelTypeUtils.isQnnModel(modelId)

        // Step 1: 检查并合并分片文件
        // 有些大模型文件（>1GB）在下载时被拆成多个 .part 文件
        checkAndMergeSplitFiles()

        // Step 2: 准备对话历史
        // 如果有历史记录，提取文本列表传给 native 层
        var historyStringList: List<String>? = null
        val currentHistory = this.savedHistory
        if (!currentHistory.isNullOrEmpty()) {
            // stream()：Java Stream API（Kotlin 也能用）
            // map {}：变换每个元素
            // filter {}：过滤
            // collect(Collectors.toList())：收集成列表
            historyStringList =
                currentHistory.stream()
                    .map { obj: ChatDataItem -> obj.text }
                    .filter { obj: String? -> obj != null }
                    .map { obj: String? -> obj!! }
                    .collect(Collectors.toList())
        }

        // Step 3: 加载配置
        val config = if (useCustomConfig) {
            ModelConfig.loadMergedConfig(configPath, getExtraConfigFile(modelId))!!
        } else {
            ModelConfig.loadDefaultConfig(configPath)!!
        }

        // Step 4: 设置 mmap 目录
        var rootCacheDir: String? = ""
        if (config.useMmap == true) {
            rootCacheDir = MmapUtils.getMmapDir(modelId)
            File(rootCacheDir).mkdirs()
        }

        // Step 5: 构建配置 Map
        val configMap = HashMap<String, Any>().apply {
            // .apply {} 是作用域函数，在对象上执行 lambda
            // this 指向 HashMap，put() 就是 HashMap 的方法
            put("is_r1", ModelTypeUtils.isR1Model(modelId))
            put("mmap_dir", rootCacheDir ?: "")
            put("keep_history", keepHistory)
        }

        val llmConfig = if (useCustomConfig) {
            ModelConfig.loadMergedConfig(configPath, getExtraConfigFile(modelId))!!
        } else {
            ModelConfig.loadDefaultConfig(configPath)!!
        }

        // 如果构造函数指定了 backendType，覆盖配置中的值
        if (backendType != null) {
            llmConfig.backendType = backendType
        }
        // QNN 模型使用专用的视觉模型文件名
        if (isQnn) {
            llmConfig.visualModel = "visual_qnn_${QnnModule.modelMiddleName()}.mnn"
        }

        // Step 6: 调用 JNI 初始化
        Log.d(TAG, "MNN_DEBUG load initNative")
        nativePtr = initNative(
            configPath,
            historyStringList,
            if (llmConfig != null) {
                Gson().toJson(llmConfig)  // 把配置序列化为 JSON 字符串传给 C++
            } else {
                "{}"
            },
            Gson().toJson(configMap)
        )
        Log.d(TAG, "MNN_DEBUG load initNative end")
        modelLoading = false

        // 检查 nativePtr 是否有效
        if (nativePtr == 0L) {
            Log.e(TAG, "Model load failed - native initialization returned null pointer")
            throw IllegalStateException("Model load failed - the model module could not be loaded")
        }
        // 如果在加载过程中被请求释放
        if (releaseRequested) {
            release()
        }
    }

    /**
     * isModelLoaded：检查模型是否已加载
     */
    fun isModelLoaded(): Boolean {
        return nativePtr != 0L
    }

    /**
     * checkAndMergeSplitFiles：检查并合并分片文件
     *
     * 为什么有分片文件？
     * 有些模型文件 >1GB，GitHub 等平台对单文件大小有限制，
     * 所以下载时可能被拆成多个 .part1、.part2 等小文件。
     * 加载前需要先合并回原始大文件。
     */
    private fun checkAndMergeSplitFiles() {
        try {
            val configFile = File(configPath)
            val modelDir = configFile.parentFile

            if (modelDir != null && modelDir.exists()) {
                Log.d(TAG, "Checking for split files in model directory: ${modelDir.absolutePath}")

                if (FileSplitter.needsMerging(modelDir)) {
                    Log.d(TAG, "Found split files that need merging in ${modelDir.absolutePath}")
                    val success = FileSplitter.mergeAllSplitFiles(modelDir)
                    if (success) {
                        Log.d(TAG, "Successfully merged split files for model: $modelId")
                    } else {
                        Log.w(TAG, "Failed to merge some split files for model: $modelId")
                    }
                } else {
                    Log.d(TAG, "No split files found for model: $modelId")
                }
            } else {
                Log.w(TAG, "Model directory not found: ${modelDir?.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking/merging split files for model: $modelId", e)
        }
    }

    fun getConfig(): ModelConfig? {
        return ModelConfig.loadMergedConfig(configPath, getExtraConfigFile(modelId))
    }

    private fun generateNewSessionId(): String {
        this.sessionId = System.currentTimeMillis().toString()
        return this.sessionId
    }

    /**
     * generate：生成回答（核心推理方法）
     *
     * @param prompt 用户输入的 prompt
     * @param params 额外参数（如 temperature 等，目前未使用）
     * @param progressListener token 级别的进度回调
     * @return HashMap 包含推理指标：
     *   - "prompt_len"：prompt 的 token 数
     *   - "decode_len"：生成的 token 数
     *   - "prefill_time"：prefill 耗时（微秒）
     *   - "decode_time"：decode 耗时（微秒）
     *   - "success"：是否成功
     */
    override fun generate(
        prompt: String,
        params: Map<String, Any>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any> {
        Log.d(TAG, "start generate prompt: $prompt")
        // synchronized(this)：加锁，保证同一时刻只有一个推理任务
        // 为什么？MNN 引擎不支持并发推理，并发会导致崩溃
        synchronized(this) {
            if (mockLatex) {
                Timber.d("MNN_DEBUG generate intercepted by mockLatex")
                return submitMockLatexHistory(progressListener)
            }
            Log.d(TAG, "MNN_DEBUG submit$prompt")
            generating = true
            // submitNative()：JNI 调用，把 prompt 发送到 C++ 推理引擎
            // C++ 引擎开始 prefill（处理 prompt）+ decode（逐 token 生成）
            // 每生成一个 token，就通过 progressListener.onProgress() 回调
            val result = submitNative(nativePtr, prompt, keepHistory, progressListener)
            generating = false
            // 如果在推理过程中被请求释放
            if (releaseRequested) {
                release()
            }
            return result
        }
    }

    /**
     * reset：重置会话（清除 KV Cache）
     *
     * KV Cache 是什么（三层解释法）：
     * 一句话：Transformer 模型在生成过程中缓存历史计算结果的机制。
     * 生活类比：就像你做数学题时，把前面步骤的结果写在草稿纸上，后面步骤直接引用。
     * 技术细节：Transformer 的 Self-Attention 需要计算每个 token 与之前所有 token 的关系，
     *           KV Cache 缓存了之前 token 的 Key 和 Value 向量，避免重复计算。
     *           reset() 清除 KV Cache，相当于清空草稿纸。
     */
    override fun reset(): String {
        synchronized(this) {
            resetNative(nativePtr)
        }
        return generateNewSessionId()
    }

    /**
     * release：释放模型资源
     *
     * 安全释放机制：
     * 1. 如果当前没有推理/加载 → 立即释放
     * 2. 如果正在推理/加载 → 标记 releaseRequested，等推理完成后释放
     *    用 wait() 阻塞当前线程，直到释放完成
     */
    override fun release() {
        synchronized(this) {
            Log.d(TAG, "MNN_DEBUG release nativePtr: $nativePtr mGenerating: $generating")
            if (!generating && !modelLoading) {
                releaseInner()
            } else {
                // 标记为待释放
                releaseRequested = true
                // wait()：释放锁并等待，直到其他线程调用 notifyAll()
                // (this as Object)：把 this 转成 Object 才能调用 wait()
                while (generating || modelLoading) {
                    try {
                        (this as Object).wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Log.e(TAG, "Thread interrupted while waiting for release", e)
                    }
                }
                releaseInner()
            }
        }
    }

    /**
     * releaseInner：实际释放资源（内部方法）
     *
     * 1. 调用 JNI releaseNative() 释放 C++ 内存
     * 2. 从 ChatService 中移除会话
     * 3. notifyAll()：唤醒所有等待的线程
     */
    private fun releaseInner() {
        if (nativePtr != 0L) {
            releaseNative(nativePtr)
            nativePtr = 0
            provide().removeSession(sessionId)
            // notifyAll()：唤醒所有在 wait() 中等待的线程
            (this as Object).notifyAll()
        }
    }

    // ===== JNI 原生方法声明 =====
    // 这些方法的实现在 C++ 的 llm_mnn_jni.cpp 中

    /** 初始化 MNN LLM 引擎，返回 C++ 对象指针 */
    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?
    ): Long

    /** 提交 prompt 并开始推理 */
    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener
    ): HashMap<String, Any>

    /** 重置会话（清除 KV Cache） */
    private external fun resetNative(instanceId: Long)

    /** 获取调试信息 */
    private external fun getDebugInfoNative(instanceId: Long): String

    /** 释放 C++ 资源 */
    private external fun releaseNative(instanceId: Long)

    /** 设置音频波形回调 */
    private external fun setWavformCallbackNative(
        instanceId: Long,
        listener: AudioDataListener?
    ): Boolean

    override fun setKeepHistory(keepHistory: Boolean) {
        this.keepHistory = keepHistory
    }

    override fun setEnableAudioOutput(enable: Boolean) {
        updateEnableAudioOutputNative(nativePtr, enable)
    }

    override val debugInfo
        get() = getDebugInfoNative(nativePtr) + "\n"

    fun setAudioDataListener(listener: AudioDataListener?) {
        synchronized(this) {
            if (nativePtr != 0L) {
                setWavformCallbackNative(nativePtr, listener)
            } else {
                Log.e(TAG, "nativePtr null")
            }
        }
    }

    fun updateMaxNewTokens(maxNewTokens: Int) {
        updateMaxNewTokensNative(nativePtr, maxNewTokens)
    }

    fun updateSystemPrompt(systemPrompt: String) {
        updateSystemPromptNative(nativePtr, systemPrompt)
    }

    /**
     * updateThinking：切换思考模式
     *
     * Qwen3 支持"深度思考"模式，模型会先展示推理过程，再给出最终答案。
     * 这个方法通过修改 Jinja 模板配置来切换思考模式。
     */
    override fun updateThinking(thinking: Boolean) {
        val loadedConfig = loadConfig(modelId)
        loadedConfig?.let {
            loadedConfig.jinja = Jinja(context = JinjaContext(enableThinking = thinking))
            ModelConfig.saveConfig(getExtraConfigFile(modelId), loadedConfig)
            updateConfig(Gson().toJson(loadedConfig))
        }
    }

    fun updateConfig(configJson: String) {
        Log.d(TAG, "updateConfig: $configJson")
        updateConfigNative(nativePtr, configJson)
    }

    // ===== 更多 JNI 方法 =====
    private external fun updateEnableAudioOutputNative(llmPtr: Long, enable: Boolean)
    private external fun updateMaxNewTokensNative(llmPtr: Long, maxNewTokens: Int)
    private external fun updateSystemPromptNative(llmPtr: Long, systemPrompt: String)
    private external fun updateAssistantPromptNative(llmPtr: Long, assistantPrompt: String)
    private external fun updateConfigNative(llmPtr: Long, configJson: String)

    companion object {
        const val TAG: String = "LlmSession"

        // mockLatex：调试用，模拟 LaTeX 输出
        var mockLatex: Boolean = false
        var mockLatexContent: String? = null

        init {
            // 加载 MNN JNI 库
            System.loadLibrary("mnnllmapp")
        }
    }

    /**
     * submitFullHistory：提交完整对话历史
     *
     * 与 generate() 的区别：
     * - generate()：只提交当前 prompt（单轮或多轮靠 keepHistory）
     * - submitFullHistory()：提交完整的对话历史列表（更精确的多轮控制）
     */
    fun submitFullHistory(
        history: List<Pair<String, String>>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any> {
        synchronized(this) {
            if (mockLatex) {
                Timber.d("MNN_DEBUG submitFullHistory intercepted by mockLatex")
                return submitMockLatexHistory(progressListener)
            }
            Timber.d("MNN_DEBUG submitFullHistory with ${history.size} messages")
            // 类型转换：kotlin.Pair → android.util.Pair
            // JNI 使用 android.util.Pair，不是 Kotlin 的 Pair
            val androidHistory = history.map { android.util.Pair(it.first, it.second) }
            val result = submitFullHistoryNative(nativePtr, androidHistory, progressListener)
            generating = false
            return result
        }
    }

    /**
     * submitMockLatexHistory：模拟 LaTeX 输出（调试用）
     * 在新线程中模拟流式输出，每 50ms 发送 3 个字符
     */
    private fun submitMockLatexHistory(progressListener: GenerateProgressListener): HashMap<String, Any> {
        val mockText = mockLatexContent ?: "Here is a math formula:\n\n\$E=mc^2$\n\nEnd of mock."
        Thread {
            try {
                var index = 0
                val chunkSize = 3
                while (index < mockText.length) {
                    Thread.sleep(50)
                    val endIndex = Math.min(index + chunkSize, mockText.length)
                    val chunk = mockText.substring(index, endIndex)
                    if (progressListener.onProgress(chunk)) {
                        break
                    }
                    index = endIndex
                }
                progressListener.onProgress(null)  // null 表示生成结束
            } catch (e: Exception) {
                Timber.e(e, "Mock generation failed")
            } finally {
                generating = false
            }
        }.start()
        val map = HashMap<String, Any>()
        map["success"] = true
        map["prompt_len"] = 10L
        map["decode_len"] = mockText.length.toLong()
        map["prefill_time"] = 100000L
        map["decode_time"] = 2000000L
        return map
    }

    private external fun submitFullHistoryNative(
        nativePtr: Long,
        history: List<android.util.Pair<String, String>>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any>

    fun modelId(): String {
        return modelId
    }

    fun getSystemPrompt(): String? {
        return getSystemPromptNative(nativePtr)
    }

    private external fun getSystemPromptNative(llmPtr: Long): String?
    private external fun dumpConfigNative(llmPtr: Long): String

    fun dumpConfig(): String {
        return if (nativePtr != 0L) {
            dumpConfigNative(nativePtr)
        } else {
            "{}"
        }
    }

    private fun getCurrentMemoryUsageMB(context: Context): Long {
        val runtime = Runtime.getRuntime()
        val usedMemoryBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedMemoryBytes / (1024 * 1024)
    }

    private fun getMemoryInfo(context: Context): Pair<Long, Long> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val availMemoryMB = memoryInfo.availMem / (1024 * 1024)
        return Pair(usedMemoryMB, availMemoryMB)
    }

    /**
     * runBenchmark：运行性能基准测试
     * 调用 C++ 层的 llm_bench.cpp 实现
     */
    fun runBenchmark(
        context: Context,
        commandParams: com.alibaba.mnnllm.android.benchmark.CommandParameters,
        testInstance: com.alibaba.mnnllm.android.benchmark.TestInstance,
        callback: com.alibaba.mnnllm.android.benchmark.BenchmarkCallback
    ): com.alibaba.mnnllm.android.benchmark.BenchmarkResult {
        return try {
            runBenchmarkNative(
                nativePtr,
                commandParams.backend,
                commandParams.threads,
                commandParams.useMmap,
                commandParams.power,
                commandParams.precision,
                commandParams.memory,
                commandParams.dynamicOption,
                commandParams.nPrompt,
                commandParams.nGenerate,
                commandParams.nRepeat,
                commandParams.kvCache == "true",
                testInstance,
                callback
            )
        } catch (e: Exception) {
            com.alibaba.mnnllm.android.benchmark.BenchmarkResult(
                testInstance = testInstance,
                success = false,
                errorMessage = "benchmark failed: ${e.message}"
            )
        }
    }

    private external fun runBenchmarkNative(
        nativePtr: Long,
        backend: Int,
        threads: Int,
        useMmap: Boolean,
        power: Int,
        precision: Int,
        memory: Int,
        dynamicOption: Int,
        nPrompt: Int,
        nGenerate: Int,
        nRepeat: Int,
        kvCache: Boolean,
        testInstance: com.alibaba.mnnllm.android.benchmark.TestInstance,
        callback: com.alibaba.mnnllm.android.benchmark.BenchmarkCallback
    ): com.alibaba.mnnllm.android.benchmark.BenchmarkResult
}

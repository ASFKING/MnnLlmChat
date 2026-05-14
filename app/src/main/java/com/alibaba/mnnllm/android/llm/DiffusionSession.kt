package com.alibaba.mnnllm.android.llm

import android.util.Log
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.llm.ChatService.Companion.provide
import com.alibaba.mnnllm.android.llm.LlmSession.Companion.TAG

/**
 * DiffusionSession：图像生成会话（Stable Diffusion 系列）
 *
 * 一句话：用 Diffusion 模型根据文字描述生成图片。
 * 生活类比：就像一个画家——你告诉他"画一只猫在月球上"，他画出来。
 * 技术细节：通过 JNI 调用 MNN 的 Diffusion 推理引擎，
 *           输入文字 prompt，输出图片文件。
 *
 * 与 LlmSession 的区别：
 * - LlmSession：文字 → 文字（自回归生成，逐 token）
 * - DiffusionSession：文字 → 图片（迭代去噪，逐步生成）
 */
class DiffusionSession(
    private val modelId: String,
    override var sessionId: String,
    private val configPath: String,
    private var savedHistory: List<ChatDataItem>? = null
) : ChatSession {

    override var supportOmni: Boolean = false
    // nativePtr：C++ 对象的指针（Long 类型）
    // JNI 通过这个指针找到 C++ 层的 Diffusion 会话对象
    private var nativePtr: Long = 0
    override val debugInfo: String = ""

    // @Volatile：保证多线程可见性
    // C++ 回调线程可能修改这些标志位
    @Volatile
    private var releaseRequested = false
    @Volatile
    private var generating = false

    /**
     * load：加载 Diffusion 模型
     *
     * 调用 native initNative() 初始化 C++ 层的 Diffusion 引擎
     * 如果在加载过程中收到释放请求，加载完成后立即释放
     */
    override fun load() {
        nativePtr = initNative(
            configPath,
            // 构建额外配置 JSON（如 diffusion_memory_mode）
            DiffusionLoadConfigResolver.buildExtraConfigJson(modelId, configPath)
        )
        Log.d(TAG, "DiffusionSession load nativePtr=$nativePtr configPath=$configPath")
        // 如果在加载过程中被请求释放，加载完成后立即释放
        if (releaseRequested) {
            release()
        }
    }

    /**
     * generate：根据 prompt 生成图片
     *
     * @param prompt 文字描述（如 "一只猫在月球上"）
     * @param params 额外参数：
     *   - output: 输出图片路径
     *   - iterNum: 迭代步数（越多质量越好，但越慢）
     *   - randomSeed: 随机种子（相同种子 = 相同图片）
     * @param progressListener 进度回调
     * @return 推理结果
     */
    override fun generate(
        prompt: String,
        params: Map<String, Any>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any> {
        // synchronized(this)：加锁，保证同一时刻只有一个生成任务
        // Diffusion 生成是 CPU/GPU 密集型，并发会导致资源争抢
        synchronized(this) {
            Log.d(TAG, "MNN_DEBUG submit$prompt")
            if (nativePtr == 0L) {
                Log.e(TAG, "Diffusion nativePtr is 0, cannot generate")
                return hashMapOf<String, Any>(
                    "error" to true,
                    "message" to "Native diffusion session not initialized"
                )
            }
            generating = true

            // 从 params 中取出 Diffusion 专用参数
            val output = params["output"] as String      // 输出路径
            val iterNum = params["iterNum"] as Int        // 迭代步数
            val randomSeed = params["randomSeed"] as Int  // 随机种子

            // 调用 JNI 方法生成图片
            val nativeResult = submitDiffusionNative(
                nativePtr,
                prompt,
                output,
                iterNum,
                randomSeed,
                progressListener
            )
            // 如果 native 返回 null，构造一个错误结果
            val result: HashMap<String, Any> = nativeResult ?: hashMapOf<String, Any>(
                "error" to true,
                "message" to "Native diffusion returned null"
            )
            generating = false
            if (releaseRequested) {
                releaseInner()
            }
            return result
        }
    }

    private fun releaseInner() {
        if (nativePtr != 0L) {
            releaseNative(nativePtr)
            nativePtr = 0
            provide().removeSession(sessionId)
            // (this as Object).notifyAll()：唤醒所有等待在这个对象上的线程
            // 在 release() 中有线程在 wait()，这里唤醒它们
            (this as Object).notifyAll()
        }
    }

    // ===== JNI 原生方法声明 =====

    /** 初始化 Diffusion 引擎，返回 C++ 对象指针 */
    private external fun initNative(configPath: String, extraConfig: String): Long

    /** 提交生成任务到 C++ 层 */
    private external fun submitDiffusionNative(
        instanceId: Long,
        input: String,
        outputPath: String,
        iterNum: Int,
        randomSeed: Int,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any>?

    private fun generateNewSessionId(): String {
        this.sessionId = System.currentTimeMillis().toString()
        return this.sessionId
    }

    override fun reset(): String {
        return generateNewSessionId()
    }

    override fun release() {
        synchronized(this) {
            Log.d(TAG, "MNN_DEBUG release nativePtr: $nativePtr generating: $generating")
            if (!generating) {
                releaseInner()
            } else {
                // 如果正在生成，标记为待释放，等生成完成后再释放
                releaseRequested = true
            }
        }
    }

    override fun setKeepHistory(keepHistory: Boolean) {
        // Diffusion 模型不使用 keepHistory
    }

    override fun setEnableAudioOutput(enable: Boolean) {
        // Diffusion 模型不支持音频输出
    }

    override fun getHistory(): List<ChatDataItem>? {
        Log.d(TAG, "getHistory: returning ${savedHistory?.size ?: 0} items")
        return savedHistory
    }

    override fun setHistory(history: List<ChatDataItem>?) {
        Log.d(TAG, "setHistory: setting ${history?.size ?: 0} items")
        savedHistory = history
    }

    override fun updateThinking(thinking: Boolean) {
        // Diffusion 模型不支持思考模式
    }

    private external fun resetNative(instanceId: Long)
    private external fun releaseNative(instanceId: Long)

    companion object {
        const val TAG: String = "DiffusionSession"

        init {
            // 加载 MNN JNI 库
            System.loadLibrary("mnnllmapp")
        }
    }
}

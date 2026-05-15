package com.poc.ondevice.engine

import android.util.Log
import com.alibaba.mnnllm.android.llm.ChatService
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LLMEngine：大语言模型推理引擎
 *
 * 职责：
 * - 封装 MNN-LLM 的推理能力，提供简洁的 Kotlin 接口
 * - 通过 Kotlin Flow 实现流式输出（逐 token 推送给 UI）
 * - 保证线程安全（同一时刻只允许一个推理任务）
 *
 * 为什么需要这个封装层？
 * 1. MNN 的原生接口是回调式的（GenerateProgressListener），不太符合 Kotlin 的 Flow 风格
 * 2. 我们需要统一管理模型的加载、推理、释放生命周期
 * 3. 后续的 RAG、结构化提取等功能都依赖这个引擎
 *
 * 类比：LLMEngine 就像一个翻译官——
 * UI 层说"给我生成一段回答"（Kotlin Flow 接口）
 * 翻译官把它翻译成 MNN 能理解的指令（JNI 调用）
 * 再把 MNN 的结果翻译回 UI 能用的格式（Flow emit）
 */
class LLMEngine {

    // ==================== 核心属性 ====================

    /**
     * chatSession：MNN 的推理会话对象
     *
     * 这是与 MNN 引擎交互的核心对象：
     * - 它内部持有模型权重、KV Cache 等状态
     * - 调用 generate() 时，它会执行 prefill + decode 流程
     * - 每次调用 generate() 会消耗 token，直到生成结束符
     *
     * 为什么用 var 而不是 val？
     * 因为我们需要在 load() 时创建它，在 release() 时设为 null
     * 可空类型（?）表示"可能还没加载模型"
     */
    private var chatSession: com.alibaba.mnnllm.android.llm.ChatSession? = null

    /**
     * mutex：互斥锁
     *
     * 为什么需要互斥锁？
     * MNN 的推理会话不是线程安全的——如果两个协程同时调用 generate()，
     * 会导致 KV Cache 混乱，输出乱码甚至崩溃。
     * Mutex 保证同一时刻只有一个协程能执行推理。
     *
     * 类比：Mutex 就像厕所的门锁——一个人进去了，其他人必须等他出来才能进。
     */
    private val mutex = Mutex()

    /**
     * isLoaded：模型是否已加载
     *
     * get() 是 Kotlin 的属性访问器，每次访问 binding.isLoaded 时都会执行这段代码
     * 这里用 !! 操作符断言 chatSession 非空（如果为 null 会抛异常）
     * 但实际上我们只在 chatSession != null 时返回 true
     */
    val isLoaded: Boolean
        get() = chatSession != null

    // ==================== 模型加载 ====================

    /**
     * 加载模型
     *
     * suspend fun：挂起函数——执行时会"暂停"当前协程，但不阻塞线程
     * 线程可以去执行其他协程，等模型加载完再回来继续
     *
     * withContext(Dispatchers.IO)：切换到 IO 线程池
     * 为什么？模型加载涉及大量文件读取，属于 IO 密集型操作
     * 如果在主线程执行，会导致 UI 卡顿
     *
     * @param modelDir 模型目录路径，包含 config.json 等文件
     * @return true=加载成功，false=加载失败
     */
    suspend fun load(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("LLMEngine", "开始加载模型，路径: $modelDir")

            // ChatService.provide()：获取 ChatService 单例
            // ChatService 是 MNN 项目中已有的会话工厂，负责创建推理会话
            val service = ChatService.provide()
            Log.d("LLMEngine", "ChatService 获取成功")

            // createLlmSession：创建一个 LLM 推理会话
            // 参数说明：
            // - modelId：模型标识符（用于日志和调试）
            // - modelDir：模型目录路径（包含 config.json、模型权重等）
            // - sessionId：会话标识符（一个模型可以有多个会话，各有独立的 KV Cache）
            // - chatDataItemList：对话历史（null = 空白对话）
            // - supportOmni：是否支持多模态（false = 纯文本 LLM）
            // - backendType：推理后端（null = 自动选择，"opencl" = GPU）
            // - useCustomConfig：是否使用自定义配置（true = 读取 custom_config.json）
            chatSession = service.createLlmSession(
                modelId = "poc_llm",
                modelDir = modelDir,
                sessionIdParam = "poc_session",
                chatDataItemList = null,
                supportOmni = false,
                backendType = null,
                useCustomConfig = true
            )
            Log.d("LLMEngine", "LlmSession 创建成功")

            // load()：实际加载模型权重到内存
            // 这一步会读取 .mnn 和 .mnn.weight 文件，耗时较长（几秒到十几秒）
            // 注意：load() 不返回值，如果失败会抛异常
            Log.d("LLMEngine", "开始调用 session.load()...")
            chatSession?.load()
            Log.d("LLMEngine", "session.load() 完成")

            // 如果执行到这里没有异常，说明加载成功
            true
        } catch (e: Exception) {
            // 捕获异常，防止 App 崩溃
            // 打印日志便于调试
            Log.e("LLMEngine", "模型加载失败", e)
            chatSession?.release()
            chatSession = null
            false
        }
    }

    // ==================== 流式生成 ====================

    /**
     * 流式生成：逐 token 返回生成结果
     *
     * 为什么用 Flow 而不是直接返回 String？
     * 1. 用户体验：LLM 生成一段话可能需要几秒，如果等全部生成完再显示，用户会以为卡了
     * 2. 流式输出：每生成一个 token 就立刻显示，用户看到文字"跳出来"，感觉很快
     * 3. 可取消：用户可以随时停止生成（比如觉得回答不好，按停止按钮）
     *
     * callbackFlow：把回调式 API 转换为 Flow
     * 为什么用 callbackFlow 而不是 flow {}？
     * 因为 MNN 的 GenerateProgressListener 是回调式的——它会不断调用 onProgress()
     * callbackFlow 能把这种"被动接收"模式转换为 Flow 的"主动拉取"模式
     *
     * @param prompt 用户的输入/提示词
     * @return Flow<String>，每次 emit 一个 token（一小段文字）
     */
    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        // mutex.withLock：获取互斥锁，执行完 lambda 后自动释放
        // 如果其他协程正在推理，这里会挂起等待，直到锁被释放
        mutex.withLock {
            // 检查模型是否已加载
            val session = chatSession
            if (session == null) {
                // close() 关闭 Flow，表示"没有数据了"
                // IllegalStateException 是"状态不合法"异常
                close(IllegalStateException("模型未加载，请先调用 load()"))
                return@withLock
            }

            // 调用 MNN 的 generate 方法
            // 参数：
            // - prompt：输入文本
            // - emptyMap()：额外参数（这里没有）
            // - GenerateProgressListener：回调接口，每生成一个 token 就调用一次
            session.generate(prompt, emptyMap(), object : GenerateProgressListener {
                /**
                 * onProgress：生成进度回调
                 *
                 * @param token 本次生成的 token（一小段文字）
                 * @return false=继续生成，true=停止生成
                 *
                 * trySend()：向 Flow 发送数据
                 * 返回 Result 类型，这里我们忽略发送失败的情况（Flow 已关闭等）
                 */
                override fun onProgress(token: String?): Boolean {
                    // 安全调用：如果 token 不为 null，就发送到 Flow
                    token?.let { trySend(it) }
                    // 返回 false 表示"继续生成"（返回 true 会提前停止）
                    return false
                }
            })
        }

        // awaitClose：当 Flow 被取消时执行的清理操作
        // 这里不需要特殊清理，但 callbackFlow 要求必须有 awaitClose
        // 类比：关水龙头时要检查一下有没有漏水
        awaitClose { }
    }
    // .flowOn(Dispatchers.Default)：指定 Flow 上游代码在 Default 线程池执行
    // Default 线程池适合 CPU 密集型任务（推理是纯计算）
    // 下游（collect）仍然在调用者的线程（通常是主线程）
        .flowOn(Dispatchers.Default)

    // ==================== 一次性生成 ====================

    /**
     * 一次性生成：等待全部生成完成后返回完整结果
     *
     * 与 generateStream 的区别：
     * - generateStream：流式，逐 token 返回（适合 UI 显示）
     * - generate：阻塞式，等全部完成后返回（适合 RAG、结构化提取等需要完整结果的场景）
     *
     * @param prompt 输入文本
     * @param maxTokens 最大生成 token 数（防止无限生成）
     * @return 完整的生成结果
     */
    suspend fun generate(prompt: String, maxTokens: Int = 512): String {
        // StringBuilder：高效拼接字符串
        // 每次 append() 不会创建新的 String 对象，而是追加到内部缓冲区
        val result = StringBuilder()

        // collect：收集 Flow 中的所有数据
        // 每次 Flow emit 一个 token，这里的 lambda 就执行一次
        generateStream(prompt).collect { token ->
            result.append(token)
            // 如果超过 maxTokens 个字符，提前停止
            // 这里用字符数近似 token 数（中文 1 字 ≈ 1-2 token）
            if (result.length >= maxTokens * 2) return@collect
        }

        return result.toString()
    }

    // ==================== 会话管理 ====================

    /**
     * 重置会话：清除 KV Cache 和对话历史
     *
     * 什么时候需要重置？
     * - 开始一轮新对话时（不想让之前的对话影响当前回答）
     * - 切换话题时（避免上下文混乱）
     *
     * KV Cache 是什么？
     * 一句话：Transformer 模型在生成过程中缓存历史计算结果的机制
     * 生活类比：你做数学题时，把前面步骤的结果写在草稿纸上，后面步骤直接引用
     * 重置会话 = 清空草稿纸
     */
    fun resetSession() {
        chatSession?.reset()
    }

    /**
     * 释放资源：卸载模型，释放内存
     *
     * 什么时候需要释放？
     * - App 退到后台时（系统可能需要回收内存）
     * - 切换到其他大模型时（8GB 设备不能同时加载多个大模型）
     *
     * 为什么要手动释放？
     * Kotlin/Java 的垃圾回收器（GC）只能管理 JVM 堆内存
     * MNN 的模型权重存在 Native 内存中（通过 JNI 分配），GC 看不到
     * 如果不手动 release()，这些内存永远不会被回收，直到 App 被系统杀掉
     */
    fun release() {
        chatSession?.release()
        chatSession = null
    }
}

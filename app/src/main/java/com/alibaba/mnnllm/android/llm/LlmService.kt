package com.alibaba.mnnllm.android.llm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LlmService：LLM 推理服务封装
 *
 * 一句话：把 MNN 的回调式 API 封装成 Kotlin Flow 流式 API。
 * 生活类比：就像把"打印机打一行通知你一行"改成"水龙头放水，你接一杯看一杯"。
 * 技术细节：MNN 的 generate() 通过 GenerateProgressListener 回调逐 token 推送，
 *           LlmService 用 channelFlow 把这些回调转换成 Flow，
 *           上层代码用 .collect {} 就能像处理数据流一样处理 token 流。
 *
 * 为什么用 channelFlow 而不是 flow？
 * - flow {} 中不能从外部协程发射元素（emit 必须在同一个协程中）
 * - channelFlow 支持从不同协程发射元素（通过 send()）
 * - MNN 的 onProgress 回调在 C++ 的线程中执行，需要从外部协程 send
 */
class LlmService {
    // chatSession：当前的 LLM 会话（可空，因为可能还没加载模型）
    private var chatSession: ChatSession? = null

    // stopRequested：是否请求停止生成
    // @Volatile 保证多线程可见性（C++ 回调线程可能修改这个值）
    @Volatile
    private var stopRequested = false

    /**
     * init：加载模型
     *
     * suspend fun：挂起函数，可以在等待模型加载时不阻塞主线程
     * withContext(Dispatchers.IO)：切换到 IO 线程池执行文件读取操作
     *
     * @param modelDir 模型目录路径（包含 config.json 和权重文件）
     * @return true = 加载成功
     */
    suspend fun init(modelDir: String?): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "createSession begin")
        // 创建 LLM 会话
        // ChatService.provide() 获取单例 → createLlmSession() 创建会话
        chatSession = ChatService.provide().createLlmSession(
            "test_modelId_sessionId",     // 模型 ID（PoC 中用固定值）
            "$modelDir/config.json",       // 配置文件路径
            "test_sessionId",              // 会话 ID
            null,                          // 对话历史（首次加载为空）
            false                          // 不支持 Omni
        )
        Log.d(TAG, "createSession create success")
        // load()：实际加载模型到内存（读取权重文件、分配内存）
        chatSession!!.load()
        Log.d(TAG, "createSession  load success")
        true
    }

    /**
     * startNewSession：开始新会话（清除上下文）
     *
     * reset() 会清除 KV Cache，让模型"失忆"
     * 多轮对话场景下，如果你想开始一个新话题，就调这个方法
     */
    fun startNewSession() {
        chatSession?.reset()
    }

    /**
     * generate：流式生成（核心方法）
     *
     * 把 MNN 的回调式 API 转换成 Kotlin Flow
     *
     * Flow 是什么（三层解释法）：
     * 一句话：Flow 是 Kotlin 的异步数据流，可以逐个发射数据。
     * 生活类比：就像水龙头——你打开水龙头（collect），水（token）就流出来。
     * 技术细节：channelFlow 创建一个带缓冲的通道，
     *           MNN 的 onProgress 回调通过 send() 把 token 发送到通道，
     *           上层通过 .collect {} 从通道接收 token。
     *
     * @param text 用户输入的 prompt
     * @return Flow<Pair<当前token, 累积全文>>
     */
    fun generate(text: String): Flow<Pair<String?, String>> = channelFlow {
        // channelFlow 的 lambda 在一个新的协程中执行
        stopRequested = false
        val result = StringBuilder()

        // withContext(Dispatchers.Default)：切换到 Default 线程池
        // Default 适合 CPU 密集型任务（如推理计算）
        withContext(Dispatchers.Default) {
            // 调用 ChatSession.generate() 开始推理
            // 第三个参数是 GenerateProgressListener，用 object : 接口 {} 创建匿名实现
            chatSession?.generate(text, emptyMap(), object : GenerateProgressListener {
                /**
                 * onProgress：每个 token 生成时被回调
                 *
                 * 注意：这个回调在 C++ 的推理线程中执行，不在主线程
                 * 所以用 CoroutineScope(Dispatchers.Default).launch {} 在新协程中 send
                 */
                override fun onProgress(progress: String?): Boolean {
                    // 在新的协程中发送 token 到 Flow
                    CoroutineScope(Dispatchers.Default).launch {
                        if (progress != null) {
                            result.append(progress)
                        }
                        // send()：向 channelFlow 发射一个元素
                        // Pair(progress, result.toString()) = (当前token, 累积全文)
                        send(Pair(progress, result.toString()))
                    }
                    // 返回 stopRequested：true = 停止生成，false = 继续
                    return stopRequested
                }
            })
        }
    }.cancellable()  // .cancellable()：允许调用方通过取消协程来停止生成

    /**
     * requestStop：请求停止生成
     *
     * 设置标志位，下次 onProgress 回调时会返回 true，通知 MNN 停止推理
     */
    fun requestStop() {
        stopRequested = true
    }

    /**
     * unload：卸载模型，释放资源
     *
     * 调用时机：App 退到后台或内存紧张时
     */
    fun unload() {
        chatSession?.release()
    }

    companion object {
        private const val TAG = "LLMService"
    }
}

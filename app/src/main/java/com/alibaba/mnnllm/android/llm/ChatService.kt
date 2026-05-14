package com.alibaba.mnnllm.android.llm

import android.text.TextUtils
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.model.ModelTypeUtils

/**
 * ChatService：会话工厂（单例）
 *
 * 一句话：负责创建和管理所有对话会话。
 * 生活类比：就像餐厅的前台——你告诉它你要什么类型的桌子，它帮你安排。
 * 技术细节：根据模型名称判断类型（LLM / Diffusion / Sana），
 *           创建对应的 ChatSession 实例，并维护会话映射表。
 *
 * 为什么用单例？
 * - 全局只需要一个会话工厂（避免重复创建）
 * - @Synchronized 保证线程安全（多个线程同时创建会话不会冲突）
 */
class ChatService {
    // transformerSessionMap：存储 LLM 会话（Transformer 架构的模型）
    // MutableMap：可变映射表，key=sessionId, value=ChatSession
    private val transformerSessionMap: MutableMap<String, ChatSession> = HashMap()

    // diffusionSessionMap：存储图像生成会话（Diffusion 模型）
    private val diffusionSessionMap: MutableMap<String, ChatSession> = HashMap()

    /**
     * createSession：通用会话创建方法（根据模型名称自动判断类型）
     *
     * @Synchronized：保证同一时刻只有一个线程能执行这个方法
     * 防止并发创建会话时出现数据不一致
     *
     * @param modelId 模型 ID
     * @param modelName 模型名称（用于判断模型类型）
     * @param sessionIdParam 会话 ID（为空则自动生成）
     * @param historyList 对话历史
     * @param configPath 配置文件路径
     * @param useNewConfig 是否使用新配置
     * @param useCustomConfig 是否使用自定义配置（custom_config.json）
     * @return 创建的 ChatSession 实例
     */
    @Synchronized
    fun createSession(
        modelId: String,
        modelName: String,
        sessionIdParam: String?,
        historyList: List<ChatDataItem>?,
        configPath: String?,
        useNewConfig: Boolean = false,
        useCustomConfig: Boolean = true
    ): ChatSession {
        // 如果 sessionId 为空，用当前时间戳作为 ID
        // TextUtils.isEmpty()：Android 工具方法，检查字符串为 null 或 ""
        val sessionId = if (TextUtils.isEmpty(sessionIdParam)) {
            System.currentTimeMillis().toString()
        } else {
            sessionIdParam!!  // !! 非空断言：我们已经检查过不为空
        }

        // 根据模型名称判断类型，创建对应的会话
        // if-else 表达式在 Kotlin 中可以有返回值
        val session = if (ModelTypeUtils.isSanaModel(modelName)) {
            SanaSession(modelId, sessionId, configPath!!, historyList)
        } else if (ModelTypeUtils.isDiffusionModel(modelName)) {
            DiffusionSession(modelId, sessionId, configPath!!, historyList)
        } else {
            // 默认创建 LLM 会话
            val llmSession = LlmSession(modelId, sessionId, configPath!!, historyList, useCustomConfig = useCustomConfig)
            // 设置是否支持 Omni 模式
            llmSession.supportOmni = ModelTypeUtils.isOmni(modelName)
            llmSession
        }

        // 存储到对应的映射表
        // is LlmSession：Kotlin 的类型检查（类似 Java 的 instanceof）
        if (session is LlmSession) {
            transformerSessionMap[sessionId] = session
        } else {
            diffusionSessionMap[sessionId] = session
        }

        return session
    }

    /**
     * createLlmSession：专门创建 LLM 会话的便捷方法
     *
     * 与 createSession 的区别：
     * - 不需要判断模型类型，直接创建 LlmSession
     * - 支持 backendType 参数（指定推理后端：CPU / GPU / QNN）
     */
    @Synchronized
    fun createLlmSession(
        modelId: String?,
        modelDir: String?,
        sessionIdParam: String?,
        chatDataItemList: List<ChatDataItem>?,
        supportOmni: Boolean,
        backendType: String? = null,
        useCustomConfig: Boolean = true
    ): LlmSession {
        var sessionId: String = if (TextUtils.isEmpty(sessionIdParam)) {
            System.currentTimeMillis().toString()
        } else {
            sessionIdParam!!
        }
        val session = LlmSession(modelId!!, sessionId, modelDir!!, chatDataItemList, backendType, useCustomConfig)
        session.supportOmni = supportOmni
        transformerSessionMap[sessionId] = session
        return session
    }

    /**
     * createDiffusionSession：专门创建 Diffusion 会话的便捷方法
     */
    @Synchronized
    fun createDiffusionSession(
        modelId: String?,
        modelDir: String?,
        sessionIdParam: String?,
        chatDataItemList: List<ChatDataItem>?
    ): ChatSession {
        var sessionId: String = if (TextUtils.isEmpty(sessionIdParam)) {
            System.currentTimeMillis().toString()
        } else {
            sessionIdParam!!
        }
        val session = DiffusionSession(modelId!!, sessionId, modelDir!!, chatDataItemList)
        diffusionSessionMap[sessionId] = session
        return session
    }

    /**
     * getSession：根据 sessionId 获取会话
     *
     * 先在 transformer 映射表中找，找不到再在 diffusion 映射表中找
     * 都找不到返回 null
     */
    @Synchronized
    fun getSession(sessionId: String): ChatSession? {
        return if (transformerSessionMap.containsKey(sessionId)) {
            transformerSessionMap[sessionId]
        } else {
            diffusionSessionMap[sessionId]
        }
    }

    /**
     * removeSession：移除会话
     *
     * 在 LlmSession.release() 中会被调用，清理映射表中的引用
     */
    @Synchronized
    fun removeSession(sessionId: String) {
        transformerSessionMap.remove(sessionId)
        diffusionSessionMap.remove(sessionId)
    }

    companion object {
        // 单例实例（@Volatile 保证多线程可见性）
        @Volatile
        private var instance: ChatService? = null

        /**
         * provide：获取 ChatService 单例
         *
         * @JvmStatic：让这个方法在 Java 代码中也能像静态方法一样调用
         * @Synchronized：保证线程安全的单例创建
         *
         * 双重检查锁模式（Double-Checked Locking）：
         * 外层检查 → 加锁 → 内层检查 → 创建
         * 这样大部分调用不需要加锁，只有第一次创建时才需要
         */
        @JvmStatic
        @Synchronized
        fun provide(): ChatService {
            if (instance == null) {
                instance = ChatService()
            }
            return instance!!
        }
    }
}

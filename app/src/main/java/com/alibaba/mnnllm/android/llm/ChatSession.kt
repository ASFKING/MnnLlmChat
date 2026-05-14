package com.alibaba.mnnllm.android.llm

import com.alibaba.mnnllm.android.chat.model.ChatDataItem

/**
 * ChatSession：对话会话接口
 *
 * 一句话：定义了一个"对话会话"应该具备的能力。
 * 生活类比：就像餐厅里的一张桌子——你可以点菜（generate）、清桌（reset）、离席（release）。
 * 技术细节：这是 LlmSession（LLM 推理）、DiffusionSession（图像生成）、SanaSession（Sana 编辑）
 *           的共同接口，让上层代码可以用统一的方式调用不同类型的会话。
 *
 * 接口 vs 抽象类：
 * - 接口（interface）：只定义方法签名，不包含状态
 * - 抽象类（abstract class）：可以包含属性和部分实现
 * - 这里用接口，因为不同会话类型的实现差异很大
 */
interface ChatSession {

    // debugInfo：调试信息（如模型参数、KV Cache 状态等）
    val debugInfo: String

    // sessionId：会话唯一标识
    val sessionId: String?

    // supportOmni：是否支持 Omni 模式（音频+文本多模态）
    val supportOmni: Boolean

    /**
     * load：加载模型到内存
     *
     * 调用时机：用户选择模型后，第一次开始对话前
     * 内部操作：读取 config.json → 初始化 native 指针 → 分配内存 → 加载权重
     */
    fun load()

    /**
     * generate：生成回答
     *
     * @param prompt 用户输入的 prompt
     * @param params 额外参数（如 temperature、maxTokens 等）
     * @param progressListener token 级别的进度回调
     * @return 推理结果 HashMap，包含 "prompt_len"、"decode_len"、"prefill_time" 等指标
     */
    fun generate(prompt: String, params: Map<String, Any>, progressListener: GenerateProgressListener): HashMap<String, Any>

    /**
     * reset：重置会话（清除上下文）
     *
     * 一句话：清空 KV Cache，让模型"失忆"。
     * 生活类比：就像把草稿纸撕掉，下次做题从头算。
     * 技术细节：清除 Transformer 的 KV Cache（键值缓存），
     *           模型不再记得之前的对话历史。
     *           注意：模型权重还在内存里，只是清除了对话状态。
     *
     * @return 新的 sessionId
     */
    fun reset(): String

    /**
     * release：释放模型资源
     *
     * 调用时机：不再需要这个会话时（如用户切换模型、App 退到后台）
     * 内部操作：释放 native 内存、从 ChatService 中移除会话
     */
    fun release()

    // setKeepHistory：是否保留对话历史
    // true = 模型记住之前的对话（多轮对话）
    // false = 每次对话都是独立的（单轮问答）
    fun setKeepHistory(keepHistory: Boolean)

    // setEnableAudioOutput：是否启用音频输出（Omni 模型专用）
    fun setEnableAudioOutput(enable: Boolean)

    // getHistory / setHistory：获取/设置对话历史
    fun getHistory(): List<ChatDataItem>?
    fun setHistory(history: List<ChatDataItem>?)

    // updateThinking：切换思考模式（Qwen3 的深度思考功能）
    fun updateThinking(thinking: Boolean)
}

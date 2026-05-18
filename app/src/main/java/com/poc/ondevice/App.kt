package com.poc.ondevice

import android.app.Application
import com.alibaba.mls.api.ApplicationProvider
import com.poc.ondevice.engine.EmbeddingEngine
import com.poc.ondevice.engine.LLMEngine
import com.poc.ondevice.engine.ASREngine
import com.poc.ondevice.engine.RAGEngine
import com.poc.ondevice.engine.VectorStore
import com.poc.ondevice.engine.VisionEngine

/**
 * App：Application 入口 + 全局引擎管理
 *
 * Application 是 Android 应用的"大管家"，比所有 Activity 都先创建，最后一个销毁。
 * 适合做全局初始化工作。
 *
 * 为什么把引擎放在 App 级别？
 * 如果每个 Fragment 各自创建 LLMEngine，模型会被加载多次，浪费内存。
 * 放在 App 级别，模型只加载一次，所有 Fragment 共享同一个引擎实例。
 *
 * 类比：公司只需要一台打印机，放在公共区域所有部门共用，
 * 而不是每个部门各买一台。
 */
class App : Application() {

    /**
     * llmEngine：全局 LLM 推理引擎（所有 Fragment 共享）
     *
     * by lazy：只有第一次访问时才创建，线程安全，只初始化一次
     */
    val llmEngine by lazy { LLMEngine() }

    /**
     * embeddingEngine：全局嵌入引擎（所有 Fragment 共享）
     */
    val embeddingEngine by lazy { EmbeddingEngine() }

    /**
     * vectorStore：全局向量库（所有 Fragment 共享）
     */
    val vectorStore by lazy { VectorStore() }

    /**
     * ragEngine：RAG 引擎（组合嵌入 + 向量库 + LLM）
     *
     * 为什么也用 lazy？
     * RAGEngine 依赖 embeddingEngine 和 llmEngine，
     * 放在 App 级别可以确保依赖关系正确初始化。
     */
    val ragEngine by lazy {
        RAGEngine(embeddingEngine, vectorStore, llmEngine)
    }

    /**
     * visionEngine：多模态视觉引擎（复用 LLMEngine）
     *
     * VisionEngine 不加载独立模型，而是复用 LLMEngine 的 Qwen2.5-VL 模型。
     * 当用户选择图片理解功能时，需要先切换到 VL 模型。
     */
    val visionEngine by lazy { VisionEngine(llmEngine) }

    /**
     * asrEngine：语音识别引擎（SenseVoice 离线模型）
     *
     * ASREngine 封装 Sherpa-MNN 的 OfflineRecognizer，
     * 使用 SenseVoice 模型进行中文语音识别。
     */
    val asrEngine by lazy { ASREngine() }

    /**
     * onCreate：Application 创建时调用（整个 App 生命周期只调用一次）
     */
    override fun onCreate() {
        super.onCreate()
        // 初始化 ApplicationProvider，让全局代码能拿到 Application Context
        ApplicationProvider.init(this)
    }
}

package com.alibaba.mnnllm.android.qnn

/**
 * QnnModule：高通 QNN（Qualcomm Neural Network）NPU 适配模块
 *
 * 一句话：高通手机的 NPU（神经网络处理器）加速接口。
 * 生活类比：就像显卡之于游戏——NPU 之于 AI 推理，专用硬件比通用 CPU 更快。
 *
 * PoC 阶段不做 NPU 适配，这个桩类只为了让 LlmSession 编译通过。
 *
 * 为什么 PoC 不做 NPU？
 * - NPU 适配需要厂商 SDK（高通 QNN SDK），增加复杂度
 * - MNN 的 CPU/GPU 后端已经足够 PoC 验证
 * - 生产阶段再考虑 NPU 优化
 */
object QnnModule {

    /**
     * modelMiddleName：获取 QNN 模型的中间名称
     *
     * 在 LlmSession 中，如果是 QNN 模型，会用这个方法拼接 visual model 的文件名：
     * llmConfig.visualModel = "visual_qnn_${QnnModule.modelMiddleName()}.mnn"
     *
     * PoC 返回空字符串，因为我们不使用 QNN。
     */
    fun modelMiddleName(): String = ""
}

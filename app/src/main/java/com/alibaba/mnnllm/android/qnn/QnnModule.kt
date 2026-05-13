package com.alibaba.mnnllm.android.qnn

/**
 * QnnModule：高通 QNN（Qualcomm Neural Network）NPU 适配模块
 *
 * 原始 MNN 项目中，这个类负责高通 NPU 的模型加载和推理。
 * PoC 阶段不做 NPU 适配，这个桩类只为了让 LlmSession 编译通过。
 *
 * 为什么 PoC 不做 NPU？
 * - NPU 适配需要厂商 SDK（高通 QNN SDK），增加复杂度
 * - MNN 的 CPU/GPU 后端已经足够 PoC 验证
 * - 生产阶段再考虑 NPU 优化
 */
object QnnModule {

    /**
     * 获取模型中间名称（用于 QNN 模型文件名拼接）
     *
     * 在 LlmSession 中，如果是 QNN 模型，会用这个方法拼接 visual model 的文件名：
     * llmConfig.visualModel = "visual_qnn_${QnnModule.modelMiddleName()}.mnn"
     *
     * PoC 返回空字符串，因为我们不使用 QNN。
     */
    fun modelMiddleName(): String = ""
}

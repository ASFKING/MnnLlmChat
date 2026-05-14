package com.alibaba.mnnllm.android.model

import com.alibaba.mnnllm.android.model.ModelTypeUtils.isAudioModel
import com.alibaba.mnnllm.android.model.ModelTypeUtils.isVisualModel

/**
 * Modality：模态类型定义
 *
 * 一句话：定义了模型支持的输入/输出模态类型。
 * 生活类比：就像餐厅的菜单分类——有素食、荤食、海鲜等不同类别。
 * 技术细节：用于 UI 层过滤模型列表（如只显示支持视觉的模型）。
 */
object Modality {
    const val Text = "Text"           // 纯文本
    const val Visual = "Visual"       // 视觉（图片理解）
    const val Audio = "Audio"         // 音频（语音理解）
    const val Omni = "Omni"           // 全模态（文本+音频+视觉）
    const val Diffusion = "Diffusion" // 图像生成

    /**
     * checkModality：检查模型是否支持指定模态
     *
     * @param modelId 模型 ID
     * @param modality 要检查的模态类型
     * @return true = 支持
     */
    fun checkModality(modelId: String, modality: String): Boolean {
        // Text 模态所有模型都支持
        if (modality == Text) {
            return true
        } else if (modality == Visual) {
            return isVisualModel(modelId)
        } else if (modality == Audio) {
            return isAudioModel(modelId)
        } else if (modality == Omni) {
            return ModelTypeUtils.isOmni(modelId)
        } else if (modality == Diffusion) {
            return ModelTypeUtils.isDiffusionModel(modelId)
        }
        return false
    }

    // 模态选择器列表（用于 UI 筛选）
    val modalitySelectorList = listOf(Visual, Audio, Omni, Diffusion)
}

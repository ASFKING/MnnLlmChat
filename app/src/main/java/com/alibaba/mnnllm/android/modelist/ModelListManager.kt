package com.alibaba.mnnllm.android.modelist

import com.alibaba.mls.api.ModelItem

/**
 * ModelListManager：模型列表管理器
 *
 * 一句话：管理已下载模型的元信息（名称、标签、类型等）。
 * 生活类比：就像手机里的"已安装应用"列表——记录了每个应用的名称、图标、版本。
 *
 * PoC 阶段用简化实现，核心是让 ModelUtils 和 ModelTypeUtils 编译通过。
 */
object ModelListManager {

    // modelIdModelMap：模型 ID → 模型信息的映射表
    // PoC 中为空，因为我们的模型通过 ModelDownloader 下载，不走 MNN 的模型市场
    private val modelIdModelMap = mutableMapOf<String, ModelItem>()

    fun getModelIdModelMap(): Map<String, ModelItem> = modelIdModelMap

    /**
     * isAudioModel：根据模型列表判断是否是音频模型
     * PoC 中用简单的名称匹配
     */
    fun isAudioModel(modelId: String): Boolean {
        return modelId.lowercase().contains("audio")
    }

    fun isVisualModel(modelId: String): Boolean {
        return modelId.lowercase().contains("vl") || modelId.lowercase().contains("visual")
    }

    fun isVideoModel(modelId: String): Boolean {
        return modelId.lowercase().contains("video")
    }

    /**
     * getModelTags：获取模型的标签列表
     * 标签用于更精确的模型分类（如 "QNN"、"Vision"、"TTS"）
     */
    fun getModelTags(modelId: String): List<String> {
        return modelIdModelMap[modelId]?.tags ?: emptyList()
    }
}

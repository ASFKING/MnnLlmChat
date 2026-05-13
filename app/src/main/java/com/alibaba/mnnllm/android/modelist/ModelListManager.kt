package com.alibaba.mnnllm.android.modelist

import com.alibaba.mls.api.ModelItem

/**
 * ModelListManager：模型列表管理器
 *
 * 原始 MNN 项目中，管理已下载模型的列表和元信息。
 * PoC 阶段用简化实现，核心是让 ModelUtils 和 ModelTypeUtils 编译通过。
 */
object ModelListManager {

    private val modelIdModelMap = mutableMapOf<String, ModelItem>()

    fun getModelIdModelMap(): Map<String, ModelItem> = modelIdModelMap

    fun isAudioModel(modelId: String): Boolean {
        return modelId.lowercase().contains("audio")
    }

    fun isVisualModel(modelId: String): Boolean {
        return modelId.lowercase().contains("vl") || modelId.lowercase().contains("visual")
    }

    fun isVideoModel(modelId: String): Boolean {
        return modelId.lowercase().contains("video")
    }

    fun getModelTags(modelId: String): List<String> {
        return modelIdModelMap[modelId]?.tags ?: emptyList()
    }
}

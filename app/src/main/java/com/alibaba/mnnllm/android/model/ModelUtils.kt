// Created by ruoyi.sjd on 2024/12/25.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// PoC 精简版：删除了原始项目中对 modelmarket、R.drawable 等的依赖
package com.alibaba.mnnllm.android.model

import com.alibaba.mls.api.ApplicationProvider
import com.alibaba.mls.api.download.ModelDownloadManager
import com.alibaba.mls.api.source.ModelSources
import com.alibaba.mnnllm.android.modelist.ModelListManager
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import java.io.File
import java.util.Locale
import android.util.Log

object ModelUtils {

    fun getVendor(modelName: String): String {
        val modelLower = modelName.lowercase(Locale.getDefault())
        return when {
            modelLower.contains("deepseek") -> ModelVendors.DeepSeek
            modelLower.contains("qwen") || modelLower.contains("qwq") -> ModelVendors.Qwen
            modelLower.contains("llama") || modelLower.contains("mobilellm") -> ModelVendors.Llama
            modelLower.contains("phi") -> ModelVendors.Phi
            modelLower.contains("gemma") -> ModelVendors.Gemma
            modelLower.contains("mimo") -> ModelVendors.Mimo
            else -> ModelVendors.Others
        }
    }

    @JvmStatic
    fun getModelName(modelId: String?): String? {
        if (modelId != null && modelId.contains("/")) {
            return modelId.substring(modelId.lastIndexOf("/") + 1)
        }
        return modelId
    }

    fun safeModelId(modelId: String): String {
        return modelId.replace("/".toRegex(), "_")
    }

    fun splitSource(modelId: String): Array<String> {
        val firstSlashIndex = modelId.indexOf('/')
        if (firstSlashIndex == -1) {
            return arrayOf(modelId)
        }
        val source = modelId.substring(0, firstSlashIndex)
        val path = modelId.substring(firstSlashIndex + 1)
        return arrayOf(source, path)
    }

    fun getSource(modelId: String): String? {
        val firstSlashIndex = modelId.indexOf('/')
        if (firstSlashIndex == -1) {
            return null
        }
        return modelId.substring(0, firstSlashIndex)
    }

    fun getRepositoryPath(modelId: String): String {
        return splitSource(modelId)[1]
    }

    fun getValidModelIdFromName(instance: ModelDownloadManager, modelName: String): String? {
        listOf(
            ModelSources.sourceModelers,
            ModelSources.sourceHuffingFace,
            ModelSources.sourceModelScope
        ).forEach { source ->
            val modelId = if (source == ModelSources.sourceHuffingFace)
                "$source/taobao-mnn/$modelName" else "$source/MNN/$modelName"
            val downloadFile = instance.getDownloadedFile(modelId)
            Log.d(TAG, "getValidModelIdFromName: modelId: $modelId downloadFile: $downloadFile exists: ${downloadFile?.exists()}")
            if (downloadFile != null) {
                return modelId
            }
        }
        return null
    }

    fun getConfigPathForModel(modelId: String): String? {
        return ModelConfig.getDefaultConfigFile(modelId)
    }

    private const val TAG = "ModelUtils"
}

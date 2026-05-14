package com.alibaba.mnnllm.android.model

import com.alibaba.mls.api.ApplicationProvider
import com.alibaba.mls.api.download.ModelDownloadManager
import com.alibaba.mls.api.source.ModelSources
import com.alibaba.mnnllm.android.modelist.ModelListManager
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import java.io.File
import java.util.Locale
import android.util.Log

/**
 * ModelUtils：模型工具类
 *
 * 一句话：提供模型相关的辅助方法（名称解析、路径获取、来源判断等）。
 * 生活类比：就像图书馆的索引系统——帮你从书名找到书架位置。
 */
object ModelUtils {

    /**
     * getVendor：根据模型名称判断厂商
     *
     * @param modelName 模型名称（如 "Qwen3-1.7B"）
     * @return 厂商标识（如 "Qwen"）
     */
    fun getVendor(modelName: String): String {
        val modelLower = modelName.lowercase(Locale.getDefault())
        // when 表达式：Kotlin 的模式匹配，比 if-else 链更清晰
        // 每个分支用 -> 分隔
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

    /**
     * getModelName：从模型 ID 中提取模型名称
     *
     * "MNN/Qwen3-1.7B-MNN" → "Qwen3-1.7B-MNN"
     * lastIndexOf("/")：查找最后一个 "/" 的位置
     * substring(startIndex)：截取从 startIndex 到末尾的子串
     */
    @JvmStatic
    fun getModelName(modelId: String?): String? {
        if (modelId != null && modelId.contains("/")) {
            return modelId.substring(modelId.lastIndexOf("/") + 1)
        }
        return modelId
    }

    /**
     * safeModelId：把模型 ID 转成安全的文件名
     *
     * "/" 在文件名中是非法字符（路径分隔符），替换成 "_"
     * 正则表达式 "/" 匹配所有 "/" 字符
     */
    fun safeModelId(modelId: String): String {
        return modelId.replace("/".toRegex(), "_")
    }

    /**
     * splitSource：拆分模型 ID 为 [来源, 路径]
     *
     * "ModelScope/MNN/Qwen3-1.7B-MNN" → ["ModelScope", "MNN/Qwen3-1.7B-MNN"]
     */
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

    /**
     * getValidModelIdFromName：从模型名查找有效的模型 ID
     *
     * 遍历所有模型源（Modelers、HuggingFace、ModelScope），
     * 找到第一个已下载的模型对应的 modelId
     */
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

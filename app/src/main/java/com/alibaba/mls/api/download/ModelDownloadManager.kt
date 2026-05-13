package com.alibaba.mls.api.download

import android.content.Context
import java.io.File

/**
 * ModelDownloadManager：模型下载管理器
 *
 * 原始 MNN 项目中，负责从 ModelScope/HuggingFace 下载模型。
 * PoC 阶段我们用自己的 ModelDownloader 替代，这个桩类只提供编译所需的接口。
 *
 * 关键方法：
 * - getInstance(context)：获取单例
 * - getDownloadedFile(modelId)：获取已下载模型的目录
 */
class ModelDownloadManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: ModelDownloadManager? = null

        fun getInstance(context: Context): ModelDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: ModelDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 获取已下载模型的目录
     *
     * 在我们的 PoC 中，模型通过 ModelDownloader 下载到 filesDir/models/ 下，
     * 这里返回对应的目录路径。
     */
    fun getDownloadedFile(modelId: String): File? {
        // PoC 模型存放路径：/data/data/com.poc.ondevice/files/models/{modelDirName}
        val modelsDir = File(context.filesDir, "models")
        val modelDir = File(modelsDir, modelId)
        return if (modelDir.exists()) modelDir else null
    }
}

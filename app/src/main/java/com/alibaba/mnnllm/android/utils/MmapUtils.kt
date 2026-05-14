package com.alibaba.mnnllm.android.utils

import com.alibaba.mls.api.ApplicationProvider
import com.alibaba.mls.api.download.DownloadFileUtils
import com.alibaba.mls.api.source.ModelSources
import com.alibaba.mnnllm.android.model.ModelUtils
import java.io.File

/**
 * MmapUtils：内存映射文件工具类
 *
 * mmap 是什么（三层解释法）：
 * 一句话：mmap 把文件直接映射到内存，读文件就像读内存一样快。
 * 生活类比：就像把书的内容复印到草稿纸上——翻草稿纸比翻书快。
 * 技术细节：操作系统把文件内容映射到进程的虚拟内存空间，
 *           读取时如果数据不在内存中，会触发缺页中断自动加载。
 *           MNN 用 mmap 加载模型权重，可以减少内存拷贝。
 */
object MmapUtils {

    /**
     * 清除 mmap 缓存
     */
    fun clearMmapCache(modelId: String): Boolean {
        return DownloadFileUtils.deleteDirectoryRecursively(File(getMmapDir(modelId)))
    }

    /**
     * 获取 mmap 缓存目录
     *
     * 不同来源的模型使用不同的缓存目录：
     * - 本地模型：local_temps/
     * - 内置模型：builtin_temps/
     * - 其他来源：tmps/{safeModelId}/
     */
    fun getMmapDir(modelId: String): String {
        // 本地模型使用专用缓存目录
        if (modelId.startsWith("local/")) {
            val safeId = ModelUtils.safeModelId(modelId)
            return ApplicationProvider.get().filesDir.toString() + "/local_temps/" + safeId
        }
        if (modelId.startsWith("Builtin/")) {
            val safeId = ModelUtils.safeModelId(modelId)
            return ApplicationProvider.get().filesDir.toString() + "/builtin_temps/" + safeId
        }

        var newModelId = modelId
        val isModelScope = modelId.startsWith(ModelSources.sourceModelScope)
        val isModelers = modelId.startsWith(ModelSources.sourceModelers)
        val isHuggingFace = modelId.startsWith(ModelSources.sourceHuffingFace)
        if (isModelers || isHuggingFace || isModelScope) {
            newModelId = modelId.substring(modelId.indexOf("/") + 1)
        }
        if (newModelId.startsWith("MNN/")) {
            newModelId = newModelId.replace("MNN/", "taobao-mnn/")
        }
        var rootCacheDir =
            ApplicationProvider.get().filesDir.toString() + "/tmps/" + ModelUtils.safeModelId(
                newModelId
            )
        if (isModelScope) {
            rootCacheDir = "$rootCacheDir/modelscope"
        } else if (isModelers) {
            rootCacheDir = "$rootCacheDir/modelers"
        }
        return rootCacheDir
    }
}

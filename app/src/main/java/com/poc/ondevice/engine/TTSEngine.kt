package com.poc.ondevice.engine

import android.util.Log
import com.k2fsa.sherpa.mnn.OfflineTts
import com.k2fsa.sherpa.mnn.OfflineTtsConfig
import com.k2fsa.sherpa.mnn.OfflineTtsModelConfig
import com.k2fsa.sherpa.mnn.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTSEngine：语音合成引擎（Text-to-Speech）
 *
 * 一句话定义：把文字转成语音音频。
 * 生活类比：就像一个播音员——你给他稿子（文字），他读给你听（语音）。
 *
 * 使用 BertVITS2 或 SuperTonic 模型（MNN 格式）。
 * BertVITS2 支持中英双语，SuperTonic 只支持英文。
 *
 * @param ttsModelDir 模型目录名（用于查找模型文件）
 */
class TTSEngine {

    /** tts：Sherpa-MNN 的 TTS 实例 */
    private var tts: OfflineTts? = null

    /** isLoaded：模型是否已加载 */
    val isLoaded: Boolean get() = tts != null

    /** sampleRate：合成音频的采样率（加载后才能确定） */
    val sampleRate: Int get() = tts?.sampleRate() ?: 44100

    /**
     * 加载 TTS 模型
     *
     * @param modelDir 模型目录的绝对路径
     *                 目录下应有 config.json 或模型文件（model.onnx + tokens.txt + lexicon.txt）
     * @return true = 加载成功
     */
    suspend fun load(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading TTS model from: $modelDir")

            // 列出目录下所有文件（递归，调试用）
            val dir = java.io.File(modelDir)
            Log.d(TAG, "=== Directory tree for: $modelDir ===")
            dir.walkTopDown().forEach { f ->
                val indent = "  ".repeat(f.absolutePath.removePrefix(modelDir).count { it == '/' })
                val type = if (f.isDirectory) "[DIR]" else "[FILE]"
                Log.d(TAG, "$indent$type ${f.name} (${f.length()} bytes)")
            }
            Log.d(TAG, "=== End of directory tree ===")

            // 查找模型文件
            // BertVITS2 模型通常是 model.onnx 或 model.mnn
            val modelFile = findModelFile(modelDir)
            if (modelFile == null) {
                Log.e(TAG, "No model file found in $modelDir")
                return@withContext false
            }

            val tokensFile = findFile(modelDir, listOf("tokens.txt", "tokens.json"))
            val lexiconFile = findFile(modelDir, listOf("lexicon.txt"))

            // 构建 TTS 配置
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        lexicon = lexiconFile?.absolutePath ?: "",
                        tokens = tokensFile?.absolutePath ?: "",
                        dataDir = modelDir,
                    ),
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
            )

            // 创建 TTS 实例
            tts = OfflineTts(null, config)
            Log.d(TAG, "TTS model loaded, sampleRate=${tts?.sampleRate()}, speakers=${tts?.numSpeakers()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TTS model", e)
            false
        }
    }

    /**
     * 一次性合成完整音频
     *
     * @param text  要合成的文字
     * @param speed 语速（1.0 = 正常）
     * @return FloatArray 音频数据（范围 -1.0 ~ 1.0）
     */
    suspend fun synthesize(text: String, speed: Float = 1.0f): FloatArray =
        withContext(Dispatchers.Default) {
            tts?.generate(text, sid = 0, speed = speed)?.samples ?: FloatArray(0)
        }

    /**
     * 流式合成：分段回调音频数据
     *
     * 适合边合成边播放，减少首字延迟。
     *
     * @param text      要合成的文字
     * @param speed     语速
     * @param onChunk   每合成一小段音频就回调一次
     * @param onComplete 合成完成回调
     */
    suspend fun synthesizeStream(
        text: String,
        speed: Float = 1.0f,
        onChunk: (FloatArray) -> Unit,
        onComplete: () -> Unit
    ) = withContext(Dispatchers.Default) {
        tts?.generateWithCallback(text, sid = 0, speed = speed) { samples ->
            onChunk(samples)
            0  // 0 = 继续合成
        }
        onComplete()
    }

    /** 查找模型文件（递归搜索子目录） */
    private fun findModelFile(modelDir: String): File? {
        val candidates = listOf("model.onnx", "model.mnn", "model.int8.onnx")
        // 先在顶层目录找
        for (name in candidates) {
            val file = File(modelDir, name)
            if (file.exists()) return file
        }
        // 再递归搜索子目录
        val dir = File(modelDir)
        dir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            for (name in candidates) {
                val file = File(subDir, name)
                if (file.exists()) return file
            }
            // 子目录中找任意 .onnx 或 .mnn 文件
            subDir.listFiles()?.firstOrNull {
                it.isFile && (it.extension == "onnx" || it.extension == "mnn")
            }?.let { return it }
        }
        // 最后在整个目录树中找
        return dir.walkTopDown().firstOrNull {
            it.isFile && (it.extension == "onnx" || it.extension == "mnn")
        }
    }

    /** 查找文件（递归搜索子目录） */
    private fun findFile(modelDir: String, candidates: List<String>): File? {
        // 先在顶层目录找
        for (name in candidates) {
            val file = File(modelDir, name)
            if (file.exists()) return file
        }
        // 再递归搜索子目录
        return File(modelDir).walkTopDown().firstOrNull {
            it.isFile && candidates.contains(it.name)
        }
    }

    /** 释放资源 */
    fun release() {
        tts?.release()
        tts = null
        Log.d(TAG, "TTSEngine released")
    }

    companion object {
        private const val TAG = "TTSEngine"
    }
}

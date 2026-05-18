package com.poc.ondevice.engine

import android.util.Log
import com.k2fsa.sherpa.mnn.FeatureConfig
import com.k2fsa.sherpa.mnn.OfflineModelConfig
import com.k2fsa.sherpa.mnn.OfflineRecognizer
import com.k2fsa.sherpa.mnn.OfflineRecognizerConfig
import com.k2fsa.sherpa.mnn.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ASREngine：语音识别引擎（基于 SenseVoice 离线模型）
 *
 * 一句话定义：录完一整段音频后，一次性识别出文字、语言、情感。
 * 生活类比：不像同声传译（边听边翻），SenseVoice 更像笔译——
 *           你把整篇文章给翻译官，翻译官看完再给你结果。
 *           虽然不是"实时"的，但翻译质量更高，而且速度很快（< 1s）。
 *
 * 为什么用 SenseVoice（离线）而不是 Zipformer（在线）？
 * 1. SenseVoice 中文识别准确率极高（> 95%）
 * 2. 支持语言识别、情感识别、音频事件检测
 * 3. 非自回归架构，推理速度非常快
 * 4. 对于 PoC 的"按住说完再识别"场景，离线方式完全够用
 *
 * 职责：
 * - 加载 SenseVoice ASR 模型
 * - 提供非流式识别接口（录完再识别）
 * - 返回识别结果（文字 + 语言 + 情感）
 */
class ASREngine {

    /** recognizer：Sherpa-MNN 的离线识别器实例 */
    private var recognizer: OfflineRecognizer? = null

    /** isLoaded：模型是否已加载 */
    val isLoaded: Boolean get() = recognizer != null

    /** sampleRate：音频采样率（SenseVoice 要求 16kHz） */
    private val sampleRate = 16000

    /**
     * 加载 SenseVoice ASR 模型
     *
     * @param modelDir 模型目录的绝对路径
     *                 目录下应有：
     *                 - model.mnn（SenseVoice 模型文件，MNN 格式）
     *                 - tokens.txt（词表文件）
     *                 SenseVoice 不需要 encoder/decoder/joiner，只有一个模型文件。
     * @return true = 加载成功
     */
    suspend fun load(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading SenseVoice model from: $modelDir")

            // 查找模型文件
            // SenseVoice 的模型文件可能是 model.mnn 或 model.int8.mnn
            val modelFile = findModelFile(modelDir)
            if (modelFile == null) {
                Log.e(TAG, "No model file found in $modelDir")
                return@withContext false
            }

            val tokensFile = File(modelDir, "tokens.txt")
            if (!tokensFile.exists()) {
                Log.e(TAG, "tokens.txt not found in $modelDir")
                return@withContext false
            }

            // 构建 SenseVoice 离线配置
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelFile.absolutePath,
                        language = "auto",   // 自动识别语言
                        useInverseTextNormalization = true,  // 数字转文字
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )

            // 创建离线识别器（加载模型到内存）
            recognizer = OfflineRecognizer(null, config)
            Log.d(TAG, "SenseVoice model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SenseVoice model", e)
            false
        }
    }

    /**
     * 非流式识别：传入完整音频数据，返回识别结果
     *
     * @param audioData 音频数据（FloatArray，范围 -1.0 ~ 1.0，16kHz 采样率）
     * @return 识别出的文字
     *
     * 工作流程（类比：笔译）：
     * 1. 创建 stream（打开一个空白文档）
     * 2. acceptWaveform（把整篇文章放进去）
     * 3. decode（翻译官一次性看完并翻译）
     * 4. getResult（拿到翻译结果）
     * 5. stream.release（归还文档）
     */
    suspend fun recognize(audioData: FloatArray): String = withContext(Dispatchers.Default) {
        recognizer?.let { rec ->
            val stream = rec.createStream()

            // 一次性送入完整音频
            stream.acceptWaveform(audioData, sampleRate)

            // 一次性解码（离线模型只需要调用一次 decode）
            rec.decode(stream)

            // 获取结果
            val result = rec.getResult(stream)
            stream.release()

            Log.d(TAG, "ASR result: text='${result.text}', lang=${result.lang}, emotion=${result.emotion}")
            result.text
        } ?: ""
    }

    /**
     * 详细识别：返回完整结果（包含语言、情感、事件）
     *
     * @param audioData 音频数据
     * @return OfflineRecognizerResult 包含 text/lang/emotion/event
     */
    suspend fun recognizeDetailed(audioData: FloatArray) =
        withContext(Dispatchers.Default) {
            recognizer?.let { rec ->
                val stream = rec.createStream()
                stream.acceptWaveform(audioData, sampleRate)
                rec.decode(stream)
                val result = rec.getResult(stream)
                stream.release()
                result
            }
        }

    /**
     * 在模型目录中查找模型文件
     *
     * SenseVoice 的模型文件名可能有多种：
     * - model.mnn
     * - model.int8.mnn
     * - sense-voice.mnn
     */
    private fun findModelFile(modelDir: String): File? {
        val candidates = listOf("model.mnn", "model.int8.mnn", "sense-voice.mnn")
        for (name in candidates) {
            val file = File(modelDir, name)
            if (file.exists()) return file
        }
        // 兜底：找目录下任意 .mnn 文件
        return File(modelDir).listFiles()?.firstOrNull { it.extension == "mnn" }
    }

    /** 释放资源 */
    fun release() {
        recognizer?.release()
        recognizer = null
        Log.d(TAG, "ASREngine released")
    }

    companion object {
        private const val TAG = "ASREngine"
    }
}

package com.poc.ondevice.engine

import android.util.Log
import com.k2fsa.sherpa.mnn.OnlineCtcFstDecoderConfig
import com.k2fsa.sherpa.mnn.OnlineRecognizer
import com.k2fsa.sherpa.mnn.OnlineRecognizerConfig
import com.k2fsa.sherpa.mnn.getEndpointConfig
import com.k2fsa.sherpa.mnn.getFeatureConfig
import com.k2fsa.sherpa.mnn.getModelConfigFromDirectory
import com.k2fsa.sherpa.mnn.getOnlineLMConfigFromDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ASREngine：语音识别引擎（基于 Sherpa-MNN 流式 Zipformer）
 *
 * 一句话定义：把用户的语音转成文字。
 * 生活类比：一个同声传译官——你说话的同时，他就在翻译。
 *
 * 为什么用 Zipformer（流式）而不是 SenseVoice（离线）？
 * 官方 MnnLlmChat 使用的就是 Zipformer 模型（MNN 格式，ModelScope 可直接下载）。
 * Zipformer 支持流式识别（边说边识别），用户体验更好。
 *
 * 流式识别 vs 离线识别的区别：
 * - 流式（Zipformer）：边说边识别，说完时结果已经出来了。像同声传译。
 * - 离线（SenseVoice）：录完再识别，准确率更高但要等。像笔译。
 *
 * 工作流程：
 * 1. load(modelDir) → 加载模型（从 config.json 读取 encoder/decoder/joiner 路径）
 * 2. recognize(audioData) → 录完后一次性识别（内部用流式接口处理）
 * 3. release() → 释放资源
 */
class ASREngine {

    /** recognizer：Sherpa-MNN 的在线识别器实例 */
    private var recognizer: OnlineRecognizer? = null

    /** isLoaded：模型是否已加载 */
    val isLoaded: Boolean get() = recognizer != null

    /** sampleRate：音频采样率（必须和模型一致，通常 16000） */
    private val sampleRate = 16000

    /**
     * 加载 ASR 模型
     *
     * @param modelDir 模型目录的绝对路径
     *                 目录下应有 config.json（含 encoder/decoder/joiner 路径）
     *                 或直接有 encoder-epoch-99-avg-1.int8.mnn 等文件
     * @return true = 加载成功
     */
    suspend fun load(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading ASR model from: $modelDir")

            // 从 config.json 读取模型配置
            // getModelConfigFromDirectory 会自动查找 config.json，
            // 如果找不到就用兜底配置（根据目录名推断文件名）
            val modelConfig = getModelConfigFromDirectory(modelDir)
            if (modelConfig == null) {
                Log.e(TAG, "Failed to get model config from directory: $modelDir")
                return@withContext false
            }

            // 组装 OnlineRecognizerConfig
            val config = OnlineRecognizerConfig(
                featConfig = getFeatureConfig(sampleRate, 80),
                modelConfig = modelConfig,
                lmConfig = getOnlineLMConfigFromDirectory(modelDir),
                ctcFstDecoderConfig = OnlineCtcFstDecoderConfig("", 3000),
                endpointConfig = getEndpointConfig(),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )

            // 创建识别器实例（加载模型到内存）
            recognizer = OnlineRecognizer(null, config)
            Log.d(TAG, "ASR model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ASR model", e)
            false
        }
    }

    /**
     * 非流式识别：传入完整音频数据，返回识别结果
     *
     * 虽然底层用的是流式识别器（OnlineRecognizer），
     * 但我们把整段音频一次性送入，模拟非流式使用方式。
     *
     * @param audioData 音频数据（FloatArray，范围 -1.0 ~ 1.0，16kHz）
     * @return 识别出的文字
     */
    suspend fun recognize(audioData: FloatArray): String = withContext(Dispatchers.Default) {
        recognizer?.let { rec ->
            val stream = rec.createStream()

            // 送入完整音频
            stream.acceptWaveform(audioData, sampleRate)
            // 通知输入结束
            stream.inputFinished()

            // 循环解码直到处理完毕
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }

            // 获取结果
            val result = rec.getResult(stream).text
            stream.release()

            Log.d(TAG, "ASR result: $result")
            result
        } ?: ""
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

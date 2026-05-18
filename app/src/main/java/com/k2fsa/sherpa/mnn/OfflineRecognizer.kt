package com.k2fsa.sherpa.mnn

import android.content.res.AssetManager

/**
 * OfflineRecognizerResult：离线识别结果
 *
 * SenseVoice 模型除了文字，还能输出语言、情感、音频事件等信息。
 *
 * @param text       识别出的文字
 * @param tokens     token 列表
 * @param timestamps 时间戳
 * @param lang       识别出的语言（zh/en/ja/ko/yue）
 * @param emotion    识别出的情感（happy/sad/angry/neutral 等）
 * @param event      识别出的音频事件（music/applause/laughter 等）
 */
data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String,
    val emotion: String,
    val event: String,
)

// ==================== 离线模型配置 ====================

data class OfflineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
)

data class OfflineParaformerModelConfig(
    var model: String = "",
)

data class OfflineNemoEncDecCtcModelConfig(
    var model: String = "",
)

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "en",
    var task: String = "transcribe",
    var tailPaddings: Int = 1000,
)

data class OfflineFireRedAsrModelConfig(
    var encoder: String = "",
    var decoder: String = "",
)

data class OfflineMoonshineModelConfig(
    var preprocessor: String = "",
    var encoder: String = "",
    var uncachedDecoder: String = "",
    var cachedDecoder: String = "",
)

/**
 * OfflineSenseVoiceModelConfig：SenseVoice 模型配置
 *
 * SenseVoice 是阿里达摩院的多语言语音理解模型，
 * 支持中/英/日/韩/粤语，还能识别情感和音频事件。
 *
 * @param model                   模型文件路径
 * @param language                语言（"zh"/"en"/"ja"/"ko"/"yue"/"auto"）
 * @param useInverseTextNormalization 是否使用逆文本标准化（数字转文字等）
 */
data class OfflineSenseVoiceModelConfig(
    var model: String = "",
    var language: String = "",
    var useInverseTextNormalization: Boolean = true,
)

/**
 * OfflineModelConfig：离线模型总配置
 *
 * 不同于 OnlineModelConfig（流式），离线模型需要先录完再识别。
 * 配置中包含多种模型类型的子配置，实际使用时只填一种。
 */
data class OfflineModelConfig(
    var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    var paraformer: OfflineParaformerModelConfig = OfflineParaformerModelConfig(),
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var fireRedAsr: OfflineFireRedAsrModelConfig = OfflineFireRedAsrModelConfig(),
    var moonshine: OfflineMoonshineModelConfig = OfflineMoonshineModelConfig(),
    var nemo: OfflineNemoEncDecCtcModelConfig = OfflineNemoEncDecCtcModelConfig(),
    var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    var teleSpeech: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var tokens: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

/**
 * OfflineRecognizerConfig：离线识别器总配置
 */
data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

// ==================== 核心离线识别器 ====================

/**
 * OfflineRecognizer：离线语音识别器
 *
 * 一句话定义：录完一整段音频后，一次性识别出文字。
 * 生活类比：不像同声传译（OnlineRecognizer 边听边翻），
 *           离线识别更像笔译——你把整篇文章给翻译官，翻译官看完再给你结果。
 *
 * 为什么 SenseVoice 用离线而不是在线？
 * SenseVoice 的模型架构（非自回归）决定了它必须看到完整音频才能做最优解码。
 * 好处是：识别准确率更高，且速度非常快（通常 < 1s）。
 *
 * 使用流程：
 * 1. 创建 OfflineRecognizerConfig
 * 2. new OfflineRecognizer(config) → 加载模型
 * 3. recognizer.createStream() → 创建音频流
 * 4. stream.acceptWaveform(audioData, sampleRate) → 送入完整音频
 * 5. recognizer.decode(stream) → 一次性解码
 * 6. recognizer.getResult(stream) → 获取结果
 * 7. 释放资源
 */
class OfflineRecognizer(
    assetManager: AssetManager? = null,
    config: OfflineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    /** 创建离线音频流 */
    fun createStream(): OfflineStream {
        val p = createStream(ptr)
        return OfflineStream(p)
    }

    /**
     * 获取识别结果
     *
     * SenseVoice 的结果比普通 ASR 多了 lang/emotion/event 字段。
     */
    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        val objArray = getResult(stream.ptr)
        return OfflineRecognizerResult(
            text = objArray[0] as String,
            tokens = objArray[1] as Array<String>,
            timestamps = objArray[2] as FloatArray,
            lang = objArray[3] as String,
            emotion = objArray[4] as String,
            event = objArray[5] as String,
        )
    }

    /** 一次性解码（离线模型只需要调用一次） */
    fun decode(stream: OfflineStream) = decode(ptr, stream.ptr)

    // ==================== JNI 方法 ====================

    private external fun delete(ptr: Long)

    private external fun createStream(ptr: Long): Long

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineRecognizerConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineRecognizerConfig,
    ): Long

    private external fun decode(ptr: Long, streamPtr: Long)

    private external fun getResult(streamPtr: Long): Array<Any>

    companion object {
        init {
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}

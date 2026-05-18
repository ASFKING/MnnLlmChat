package com.k2fsa.sherpa.mnn

import android.content.res.AssetManager
import android.util.Log

// ==================== 配置数据类 ====================

/**
 * EndpointRule：端点检测规则
 *
 * 一句话定义：告诉引擎"什么情况下算一句话说完了"。
 * 生活类比：就像对话中的停顿——你停了 2 秒，对方就知道你说完了，可以开始回复。
 *
 * @param mustContainNonSilence 是否必须包含非静音部分（防止把纯静音识别成一句话）
 * @param minTrailingSilence     最小尾部静音时长（秒），停顿这么久算说完
 * @param minUtteranceLength     最小语音长度（秒），太短的忽略
 */
data class EndpointRule(
    var mustContainNonSilence: Boolean,
    var minTrailingSilence: Float,
    var minUtteranceLength: Float,
)

/**
 * EndpointConfig：端点检测配置
 *
 * 三条规则是"或"的关系——任意一条满足就判定为端点。
 * 规则 1：停顿 2.4 秒 → 说完
 * 规则 2：有语音后停顿 1.4 秒 → 说完
 * 规则 3：语音超过 20 秒 → 强制切断（防止一个人说太久）
 */
data class EndpointConfig(
    var rule1: EndpointRule = EndpointRule(false, 2.4f, 0.0f),
    var rule2: EndpointRule = EndpointRule(true, 1.4f, 0.0f),
    var rule3: EndpointRule = EndpointRule(false, 0.0f, 20.0f),
)

// ==================== 模型配置数据类 ====================

/**
 * OnlineTransducerModelConfig：Transducer 模型配置
 *
 * Transducer 是一种流式 ASR 模型架构，由三部分组成：
 * - Encoder：编码器，把音频特征转成高维表示
 * - Decoder：解码器，根据历史 token 预测下一个 token
 * - Joiner：连接器，把 Encoder 和 Decoder 的输出融合，输出最终结果
 *
 * 生活类比：
 * Encoder 像耳朵（听声音），Decoder 像嘴巴（说文字），
 * Joiner 像大脑（把听到的和想说的结合起来）。
 */
data class OnlineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
)

data class OnlineParaformerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
)

data class OnlineZipformer2CtcModelConfig(
    var model: String = "",
)

data class OnlineNeMoCtcModelConfig(
    var model: String = "",
)

/**
 * OnlineModelConfig：ASR 模型总配置
 *
 * @param transducer   Transducer 模型配置（encoder + decoder + joiner）
 * @param paraformer   Paraformer 模型配置（另一种 ASR 架构）
 * @param tokens       词表文件路径（tokens.txt，模型认识的文字列表）
 * @param numThreads   推理线程数（通常 2-4，太多反而慢）
 * @param debug        是否输出调试日志
 * @param provider     推理后端（"cpu" 或 "gpu"）
 * @param modelType    模型类型（"zipformer"、"sensevoice" 等）
 */
data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    var paraformer: OnlineParaformerModelConfig = OnlineParaformerModelConfig(),
    var zipformer2Ctc: OnlineZipformer2CtcModelConfig = OnlineZipformer2CtcModelConfig(),
    var neMoCtc: OnlineNeMoCtcModelConfig = OnlineNeMoCtcModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class OnlineLMConfig(
    var model: String = "",
    var scale: Float = 0.5f,
)

data class OnlineCtcFstDecoderConfig(
    var graph: String = "",
    var maxActive: Int = 3000,
)

/**
 * OnlineRecognizerConfig：识别器总配置
 *
 * 把所有配置打包成一个对象，传给 OnlineRecognizer。
 * 类比：装修房子时，把户型图、材料清单、施工方案装进一个文件夹交给施工队。
 */
data class OnlineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var lmConfig: OnlineLMConfig = OnlineLMConfig(),
    var ctcFstDecoderConfig: OnlineCtcFstDecoderConfig = OnlineCtcFstDecoderConfig(),
    var endpointConfig: EndpointConfig = EndpointConfig(),
    var enableEndpoint: Boolean = true,
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

/**
 * OnlineRecognizerResult：识别结果
 *
 * @param text       识别出的文字
 * @param tokens     识别出的 token 列表
 * @param timestamps 每个 token 对应的时间戳（秒）
 */
data class OnlineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
)

// ==================== 核心识别器 ====================

/**
 * OnlineRecognizer：在线语音识别器
 *
 * 一句话定义：Sherpa-MNN 的 ASR 核心，把音频流转成文字。
 * 生活类比：一个同声传译官——你说话的同时，他就在翻译。
 *
 * 使用流程：
 * 1. 创建 OnlineRecognizerConfig（告诉识别器模型在哪、参数是什么）
 * 2. new OnlineRecognizer(config) → 加载模型
 * 3. recognizer.createStream() → 创建音频流
 * 4. 循环：stream.acceptWaveform(audio) → recognizer.decode(stream)
 * 5. recognizer.getResult(stream) → 获取识别结果
 * 6. stream.release() + recognizer.release() → 释放资源
 *
 * @param assetManager Android AssetManager（从 assets 加载模型时用，通常传 null）
 * @param config       识别器配置
 */
class OnlineRecognizer(
    assetManager: AssetManager? = null,
    val config: OnlineRecognizerConfig,
) {
    /** ptr：native 对象指针，C++ 层的 OnlineRecognizer 实例地址 */
    private var ptr: Long

    init {
        // 根据是否有 AssetManager 选择不同的加载方式
        // newFromAsset：从 APK 的 assets 目录加载（适合小模型打包在 APK 里）
        // newFromFile：从文件系统加载（适合大模型放 sdcard 或 filesDir）
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    /** 析构时释放 native 资源 */
    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    /** 手动释放资源 */
    fun release() = finalize()

    /**
     * 创建音频流
     *
     * 每次识别会话需要一个独立的 stream。
     * 多次识别 = 多个 stream，但共用同一个 recognizer。
     *
     * @param hotwords 热词（提高特定词汇的识别概率），通常为空
     * @return 新创建的 OnlineStream
     */
    fun createStream(hotwords: String = ""): OnlineStream {
        val p = createStream(ptr, hotwords)
        return OnlineStream(p)
    }

    /** 重置 stream（清除已接收的音频数据，开始新的识别会话） */
    fun reset(stream: OnlineStream) = reset(ptr, stream.ptr)

    /**
     * 解码一步
     *
     * 每次调用，引擎处理一小段音频，输出一个 token（或不输出）。
     * 需要在循环中调用，直到 isReady() 返回 false。
     */
    fun decode(stream: OnlineStream) = decode(ptr, stream.ptr)

    /** 检测是否为端点（一句话是否说完） */
    fun isEndpoint(stream: OnlineStream) = isEndpoint(ptr, stream.ptr)

    /** 检查是否还有音频数据需要解码 */
    fun isReady(stream: OnlineStream) = isReady(ptr, stream.ptr)

    /**
     * 获取当前识别结果
     *
     * @return OnlineRecognizerResult 包含 text、tokens、timestamps
     */
    fun getResult(stream: OnlineStream): OnlineRecognizerResult {
        val objArray = getResult(ptr, stream.ptr)
        val text = objArray[0] as String
        val tokens = objArray[1] as Array<String>
        val timestamps = objArray[2] as FloatArray
        return OnlineRecognizerResult(text = text, tokens = tokens, timestamps = timestamps)
    }

    // ==================== JNI 方法（C++ 层实现）====================

    /** 释放 native OnlineRecognizer */
    private external fun delete(ptr: Long)

    /** 从 AssetManager 加载模型（小模型打包在 APK 时用） */
    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OnlineRecognizerConfig,
    ): Long

    /** 从文件系统加载模型（大模型放 sdcard/filesDir 时用） */
    private external fun newFromFile(
        config: OnlineRecognizerConfig,
    ): Long

    /** 创建 native stream，返回指针 */
    private external fun createStream(ptr: Long, hotwords: String): Long

    /** 重置 native stream */
    private external fun reset(ptr: Long, streamPtr: Long)

    /** 解码一步 */
    private external fun decode(ptr: Long, streamPtr: Long)

    /** 检测端点 */
    private external fun isEndpoint(ptr: Long, streamPtr: Long): Boolean

    /** 检查是否就绪 */
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean

    /** 获取识别结果（返回 Object[]：[String, String[], float[]]） */
    private external fun getResult(ptr: Long, streamPtr: Long): Array<Any>

    companion object {
        init {
            // 加载 Sherpa-MNN JNI 动态库
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}

// ==================== 便捷工具函数 ====================

/** ASR 模型目录（全局可配置） */
private var ASR_MODEL_DIR = "/data/local/tmp/asr_models"

/** 设置 ASR 模型目录 */
fun setAsrModelDir(modelDir: String) {
    Log.d("OnlineRecognizer", "Setting ASR model directory to: $modelDir")
    ASR_MODEL_DIR = modelDir
}

/** 获取当前 ASR 模型目录 */
fun getAsrModelDir(): String = ASR_MODEL_DIR

/**
 * 从指定目录获取模型配置（推荐方式）
 *
 * @param modelDir 模型目录完整路径
 * @return OnlineModelConfig 或 null
 */
fun getModelConfigFromDirectory(modelDir: String): OnlineModelConfig? {
    Log.d("OnlineRecognizer", "Getting model config from directory: $modelDir")
    return AsrConfigManager.getModelConfigFromDirectory(modelDir)
}

/** 从指定目录获取 LM 配置 */
fun getOnlineLMConfigFromDirectory(modelDir: String): OnlineLMConfig {
    Log.d("OnlineRecognizer", "Getting LM config from directory: $modelDir")
    return AsrConfigManager.getLmConfigFromDirectory(modelDir)
}

/** 获取默认端点配置 */
fun getEndpointConfig(): EndpointConfig {
    return EndpointConfig(
        rule1 = EndpointRule(false, 2.4f, 0.0f),
        rule2 = EndpointRule(true, 1.4f, 0.0f),
        rule3 = EndpointRule(false, 0.0f, 20.0f),
    )
}

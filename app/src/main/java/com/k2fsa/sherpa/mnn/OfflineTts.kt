package com.k2fsa.sherpa.mnn

import android.content.res.AssetManager

/**
 * GeneratedAudio：TTS 生成的音频数据
 *
 * @param samples    音频样本（FloatArray，范围 -1.0 ~ 1.0）
 * @param sampleRate 采样率（如 44100 或 22050）
 */
class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun save(filename: String) =
        saveImpl(filename = filename, samples = samples, sampleRate = sampleRate)

    private external fun saveImpl(
        filename: String,
        samples: FloatArray,
        sampleRate: Int
    ): Boolean
}

// ==================== TTS 模型配置 ====================

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsMatchaModelConfig(
    var acousticModel: String = "",
    var vocoder: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 1.0f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lexicon: String = "",
    var dictDir: String = "",
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)

// ==================== 核心 TTS 引擎 ====================

/**
 * OfflineTts：离线语音合成引擎
 *
 * 一句话定义：把文字转成语音。
 * 生活类比：就像一个播音员——你给他稿子（文字），他读给你听（语音）。
 *
 * 使用流程：
 * 1. 创建 OfflineTtsConfig（指定模型文件路径）
 * 2. new OfflineTts(config) → 加载模型
 * 3. tts.generate(text) → 一次性合成完整音频
 * 4. 或 tts.generateWithCallback(text, callback) → 流式合成（边合成边播放）
 * 5. 播放 GeneratedAudio.samples
 * 6. tts.release() → 释放资源
 */
class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    /** 获取采样率（用于 AudioTrack 播放） */
    fun sampleRate() = getSampleRate(ptr)

    /** 获取说话人数量（多说话人模型可切换） */
    fun numSpeakers() = getNumSpeakers(ptr)

    /**
     * 一次性合成完整音频
     *
     * @param text 要合成的文字
     * @param sid  说话人 ID（0 = 默认）
     * @param speed 语速（1.0 = 正常，0.5 = 慢一倍，2.0 = 快一倍）
     * @return GeneratedAudio 包含音频数据和采样率
     */
    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio {
        val objArray = generateImpl(ptr, text = text, sid = sid, speed = speed)
        return GeneratedAudio(
            samples = objArray[0] as FloatArray,
            sampleRate = objArray[1] as Int
        )
    }

    /**
     * 流式合成：边合成边回调音频片段
     *
     * 回调函数返回 0 表示继续合成，返回非 0 表示中断。
     *
     * @param text     要合成的文字
     * @param sid      说话人 ID
     * @param speed    语速
     * @param callback 每合成一小段就回调一次（用于边合成边播放）
     * @return 最终的完整音频
     */
    fun generateWithCallback(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio {
        val objArray = generateWithCallbackImpl(
            ptr,
            text = text,
            sid = sid,
            speed = speed,
            callback = callback
        )
        return GeneratedAudio(
            samples = objArray[0] as FloatArray,
            sampleRate = objArray[1] as Int
        )
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    protected fun finalize() = release()

    // ==================== JNI 方法 ====================

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineTtsConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineTtsConfig,
    ): Long

    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int

    private external fun generateImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): Array<Any>

    private external fun generateWithCallbackImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): Array<Any>

    companion object {
        init {
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}

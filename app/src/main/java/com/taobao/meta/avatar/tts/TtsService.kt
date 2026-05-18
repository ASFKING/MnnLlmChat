package com.taobao.meta.avatar.tts

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * TtsService：MNN TTS 原生服务的 JNI 绑定
 *
 * 一句话定义：通过 JNI 调用 libmnn_tts.so 实现文字转语音。
 * 生活类比：这是一个"翻译官"——Kotlin 说"帮我把这段话读出来"，
 *           它翻译成 C++ 能理解的指令，调用底层 TTS 引擎。
 *
 * 为什么需要这个类？
 * Android 用 Kotlin/Java 写，但 TTS 推理引擎是 C++ 写的。
 * JNI（Java Native Interface）是两者之间的桥梁。
 * 这个类声明了所有 native 方法，运行时会自动链接到 libmnn_tts.so 中的 C++ 函数。
 *
 * 与 Sherpa-MNN OfflineTts 的区别：
 * - OfflineTts：支持简单 VITS 模型，返回 FloatArray
 * - TtsService：支持 BertVITS2（含 BERT 编码器），返回 ShortArray（PCM 16-bit）
 *
 * 使用流程：
 * 1. TtsService() → 构造函数自动加载 libmnn_tts.so
 * 2. setLanguage("zh") → 设置语言
 * 3. init(modelDir) → 加载模型目录（异步）
 * 4. waitForInitComplete() → 等待初始化完成
 * 5. process(text, id) → 合成语音，返回 ShortArray
 * 6. destroy() → 释放资源
 */
class TtsService {

    /**
     * ttsServiceNative：C++ 层 TTS 服务的指针（Long 类型）
     *
     * 在 C++ 层，这是一个指向 TTS 对象的指针。
     * Kotlin 无法直接操作 C++ 对象，所以用一个 Long 来保存地址。
     * 后续所有 native 方法都通过这个指针找到对应的 C++ 对象。
     *
     * 类比：就像一个快递单号——你拿着单号就能查到包裹在哪。
     */
    private var ttsServiceNative: Long = 0

    /**
     * isLoaded：模型是否已加载完成
     *
     * @Volatile：保证多线程可见性
     * 因为 init() 在 IO 线程执行，waitForInitComplete() 可能在主线程调用
     * 没有 @Volatile，主线程可能看不到 IO 线程的修改
     */
    @Volatile
    private var isLoaded = false

    /**
     * initDeferred：异步初始化的 Deferred 对象
     *
     * Deferred 是 Kotlin 协程中"未来会返回结果"的承诺。
     * init() 第一次调用时创建 Deferred，后续调用复用同一个。
     * 避免重复初始化。
     */
    private var initDeferred: kotlinx.coroutines.Deferred<Boolean>? = null

    /** currentLanguage：当前语言设置 */
    private var currentLanguage: String = DEFAULT_LANGUAGE

    /**
     * init 块：构造函数执行时自动运行
     *
     * 创建 C++ 层的 TTS 服务实例。
     * nativeCreateTTS 会返回一个指针，保存在 ttsServiceNative 中。
     */
    init {
        ttsServiceNative = nativeCreateTTS(currentLanguage)
    }

    /**
     * 释放资源
     *
     * 调用 C++ 层的销毁函数，释放模型占用的内存。
     * 不调用会导致内存泄漏（Native 内存不受 JVM GC 管理）。
     */
    fun destroy() {
        nativeDestroy(ttsServiceNative)
        ttsServiceNative = 0
    }

    /**
     * 初始化（加载模型）
     *
     * suspend fun：挂起函数，在 IO 线程执行，不阻塞 UI。
     *
     * 工作流程：
     * 1. 如果已加载，直接返回 true
     * 2. 如果是第一次调用，创建 Deferred 异步任务
     * 3. nativeLoadResourcesFromFile 加载模型目录下的所有资源
     * 4. 等待加载完成，设置 isLoaded = true
     *
     * @param modelDir 模型目录路径（包含 config.json 和所有模型文件）
     * @return true = 加载成功
     */
    suspend fun init(modelDir: String): Boolean {
        if (isLoaded) return true
        if (initDeferred == null) {
            initDeferred = CoroutineScope(Dispatchers.IO).async {
                nativeLoadResourcesFromFile(
                    ttsServiceNative,
                    modelDir,
                    "",    // modelName：空字符串 = 使用默认
                    ""     // mmapDir：空字符串 = 不使用 mmap
                )
                true
            }
        }
        val result = initDeferred!!.await()
        if (result) {
            isLoaded = true
        }
        return result
    }

    /**
     * 等待初始化完成
     *
     * 如果正在初始化中，挂起等待直到完成。
     * 如果已经初始化完成，立即返回 true。
     */
    suspend fun waitForInitComplete(): Boolean {
        if (isLoaded) return true
        initDeferred?.let {
            return it.await()
        }
        return isLoaded
    }

    /**
     * 设置当前模型索引（多模型切换用）
     *
     * @param index 模型索引
     */
    fun setCurrentIndex(index: Int) {
        nativeSetCurrentIndex(ttsServiceNative, index)
    }

    /**
     * 合成语音
     *
     * @param text 要合成的文字
     * @param id   说话人 ID（0 = 默认）
     * @return ShortArray PCM 16-bit 音频数据（范围 -32768 ~ 32767）
     */
    fun process(text: String, id: Int): ShortArray {
        return nativeProcess(ttsServiceNative, text, id)
    }

    /**
     * 设置说话人 ID
     *
     * @param speakerId 说话人 ID 字符串
     */
    fun setSpeakerId(speakerId: String) {
        nativeSetSpeakerId(ttsServiceNative, speakerId)
    }

    /**
     * 设置语言
     *
     * 如果语言发生变化，会销毁当前实例并重新创建。
     * 因为不同语言的 TTS 模型可能不同。
     *
     * @param language 语言代码（如 "zh"、"en"）
     */
    fun setLanguage(language: String) {
        if (currentLanguage != language) {
            currentLanguage = language
            if (ttsServiceNative != 0L) {
                destroy()
            }
            ttsServiceNative = nativeCreateTTS(language)
        }
    }

    // ==================== Native 方法声明 ====================
    // 这些方法在 C++ 层实现，运行时由 JNI 自动链接到 libmnn_tts.so

    /** 创建 C++ TTS 服务实例，返回指针 */
    private external fun nativeCreateTTS(language: String): Long

    /** 销毁 C++ TTS 服务实例 */
    private external fun nativeDestroy(nativePtr: Long)

    /**
     * 从文件加载模型资源
     *
     * @param nativePtr  C++ 对象指针
     * @param resourceDir 模型资源目录路径
     * @param modelName   模型名称（空 = 默认）
     * @param mmapDir     mmap 目录（空 = 不使用）
     * @return true = 加载成功
     */
    private external fun nativeLoadResourcesFromFile(
        nativePtr: Long,
        resourceDir: String,
        modelName: String,
        mmapDir: String
    ): Boolean

    /** 设置当前模型索引 */
    private external fun nativeSetCurrentIndex(nativePtr: Long, index: Int)

    /** 设置说话人 ID */
    private external fun nativeSetSpeakerId(nativePtr: Long, speakerId: String)

    /**
     * 合成语音（核心方法）
     *
     * @param nativePtr C++ 对象指针
     * @param text      要合成的文字
     * @param id        说话人 ID
     * @return ShortArray PCM 16-bit 音频数据
     */
    private external fun nativeProcess(nativePtr: Long, text: String, id: Int): ShortArray

    companion object {
        private const val TAG = "TtsService"
        private const val LIBRARY_NAME = "mnn_tts"
        private const val DEFAULT_LANGUAGE = "en"

        /**
         * companion object 的 init 块：类加载时执行一次
         *
         * System.loadLibrary：加载 native 动态库
         * 这会加载 jniLibs/arm64-v8a/libmnn_tts.so
         * 加载后，所有 external 方法才能被调用
         */
        init {
            System.loadLibrary(LIBRARY_NAME)
            android.util.Log.d(TAG, "mnn_tts native library loaded successfully")
        }
    }
}

package com.k2fsa.sherpa.mnn

/**
 * OnlineStream：在线音频流
 *
 * 一句话定义：一个"管道"，把音频数据源源不断地送入 ASR 引擎。
 * 生活类比：就像传送带——你把一箱箱货物（音频数据）放上去，
 *           引擎在另一头不停地处理。传送带不停，处理就不停。
 * 技术细节：封装 Sherpa-MNN 的 native stream 对象，
 *           接受 PCM 音频数据（FloatArray），送入 C++ 层做特征提取和解码。
 *
 * 工作流程：
 * 1. 由 OnlineRecognizer.createStream() 创建
 * 2. 调用 acceptWaveform() 送入音频数据（可以多次调用，边录边送）
 * 3. 调用 inputFinished() 通知"录音结束了"
 * 4. 用完后调用 release() 释放 native 资源
 *
 * @param ptr native 对象的指针（C++ 层分配的内存地址）
 */
class OnlineStream(var ptr: Long = 0) {

    /**
     * 送入音频数据
     *
     * @param samples   音频样本数组（Float 类型，范围 -1.0 ~ 1.0）
     *                  为什么是 Float 而不是 Short？
     *                  AudioRecord 录出来的是 Short（-32768 ~ 32767），
     *                  但 ASR 引擎需要 Float（-1.0 ~ 1.0），
     *                  所以送入前需要除以 32768 做归一化。
     * @param sampleRate 采样率，必须和 FeatureConfig 中的一致（通常 16000）
     */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    /**
     * 通知输入结束
     *
     * 调用后，引擎知道"没有更多音频了"，会做最终的解码和输出。
     * 类比：告诉传送带"最后一批货了，处理完就收工"。
     */
    fun inputFinished() = inputFinished(ptr)

    /**
     * 释放 native 资源
     *
     * 为什么需要手动释放？
     * C++ 层分配的内存不受 Java 垃圾回收管理，
     * 如果不手动释放，会导致内存泄漏。
     * 类比：借了图书馆的书，看完要还，不能等图书馆自动来收。
     */
    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    /**
     * use {}：Kotlin 风格的自动资源管理
     *
     * 用法：stream.use { s -> s.acceptWaveform(...) }
     * 块结束后自动调用 release()，即使块内抛异常也会释放。
     * 类似 Java 的 try-with-resources。
     */
    fun use(block: (OnlineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    // ==================== JNI 方法（C++ 层实现）====================

    /** 送入音频数据到 native stream */
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)

    /** 通知 native 层输入结束 */
    private external fun inputFinished(ptr: Long)

    /** 释放 native stream 对象 */
    private external fun delete(ptr: Long)

    companion object {
        init {
            // 加载 Sherpa-MNN 的 JNI 动态库
            // 对应 jniLibs/arm64-v8a/libsherpa-mnn-jni.so
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}

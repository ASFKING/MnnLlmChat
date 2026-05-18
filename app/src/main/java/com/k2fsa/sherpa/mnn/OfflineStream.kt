package com.k2fsa.sherpa.mnn

/**
 * OfflineStream：离线音频流
 *
 * 和 OnlineStream 不同，OfflineStream 不需要 inputFinished()，
 * 因为离线模型是一次性接收完整音频后才开始识别。
 *
 * @param ptr native 对象指针
 */
class OfflineStream(var ptr: Long) {

    /**
     * 送入音频数据
     *
     * 对于离线识别，通常一次性送入整段音频。
     * 也可以分多次送入（底层会拼接），但最终需要全部送完才调用 decode。
     */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OfflineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}

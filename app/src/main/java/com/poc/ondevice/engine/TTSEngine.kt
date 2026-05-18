package com.poc.ondevice.engine

import android.util.Log
import com.taobao.meta.avatar.tts.TtsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TTSEngine：语音合成引擎（Text-to-Speech）
 *
 * 一句话定义：把文字转成语音音频。
 * 生活类比：就像一个播音员——你给他稿子（文字），他读给你听（语音）。
 *
 * 使用 mnn_tts 框架的 TtsService（支持 BertVITS2 模型）。
 * BertVITS2 支持中英双语，音质自然。
 *
 * 与 Sherpa-MNN OfflineTts 的区别：
 * - OfflineTts：简单 VITS 模型，返回 FloatArray
 * - TtsService：BertVITS2（含 BERT 编码器），返回 ShortArray（PCM 16-bit）
 *
 * 我们内部将 ShortArray 转为 FloatArray，保持 AudioPlayer 接口不变。
 */
class TTSEngine {

    /**
     * ttsService：MNN TTS 服务实例
     *
     * 通过 JNI 调用 libmnn_tts.so，支持 BertVITS2 模型。
     * 与 Sherpa-MNN OfflineTts 不同，TtsService 使用独立的 BERT 编码器处理文本。
     */
    private var ttsService: TtsService? = null

    /** isLoaded：模型是否已加载 */
    val isLoaded: Boolean get() = ttsService != null

    /**
     * sampleRate：合成音频的采样率
     *
     * BertVITS2 模型的默认采样率是 44100Hz。
     * AudioTrack 播放时需要用到这个值。
     */
    val sampleRate: Int = 44100

    /**
     * currentSpeakerId：当前说话人 ID
     *
     * BertVITS2 通过整数 ID 选择不同说话人（不同性别/音色）。
     * 默认 0，用户可通过 setSpeakerId() 切换。
     */
    private var currentSpeakerId: Int = 0

    /**
     * 设置说话人 ID
     *
     * @param id 说话人 ID（整数，0/1/2... 具体取决于模型）
     */
    fun setSpeakerId(id: Int) {
        currentSpeakerId = id
        Log.d(TAG, "Speaker ID set to: $id")
    }

    /**
     * 加载 TTS 模型
     *
     * suspend fun：挂起函数，在 IO 线程执行模型加载，不阻塞 UI。
     *
     * 工作流程：
     * 1. 创建 TtsService 实例（自动加载 libmnn_tts.so）
     * 2. 设置语言为中文
     * 3. 调用 init(modelDir) 加载模型目录
     * 4. 等待初始化完成
     *
     * @param modelDir 模型目录的绝对路径
     *                 目录下应有 config.json 和模型文件
     * @return true = 加载成功
     */
    suspend fun load(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading TTS model from: $modelDir")

            // 创建 TtsService 实例
            val service = TtsService()

            // 设置语言为中文
            // BertVITS2 支持中英双语，设置 "zh" 使用中文模型
            service.setLanguage("zh")

            // init() 加载模型目录下的所有资源
            // TtsService 会自动查找 config.json 和相关模型文件
            val initResult = service.init(modelDir)
            if (!initResult) {
                Log.e(TAG, "TtsService init() returned false")
                service.destroy()
                return@withContext false
            }

            // 等待异步初始化完成
            val ready = service.waitForInitComplete()
            if (!ready) {
                Log.e(TAG, "TtsService waitForInitComplete() returned false")
                service.destroy()
                return@withContext false
            }

            ttsService = service
            Log.d(TAG, "TTS model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TTS model", e)
            false
        }
    }

    /**
     * 一次性合成完整音频
     *
     * suspend fun：挂起函数，在 Default 线程池执行（CPU 密集型）。
     *
     * TtsService.process() 返回 ShortArray（PCM 16-bit），
     * 我们将其转换为 FloatArray（范围 -1.0 ~ 1.0），
     * 保持与 AudioPlayer 接口的兼容性。
     *
     * @param text  要合成的文字
     * @param speed 语速（1.0 = 正常，TtsService 暂不支持调节，保留参数）
     * @return FloatArray 音频数据（范围 -1.0 ~ 1.0）
     */
    suspend fun synthesize(text: String, speed: Float = 1.0f): FloatArray =
        withContext(Dispatchers.Default) {
            ttsService?.let { service ->
                Log.d(TAG, "开始合成语音，文字: ${text.take(50)}... (长度=${text.length})")

                // 先确保初始化完成
                val ready = service.waitForInitComplete()
                Log.d(TAG, "waitForInitComplete=$ready")

                if (!ready) {
                    Log.e(TAG, "TTS 未就绪")
                    return@withContext FloatArray(0)
                }

                // process() 返回 ShortArray，每个样本范围 -32768 ~ 32767
                Log.d(TAG, "调用 service.process()...")
                val shortArray = service.process(text, currentSpeakerId)
                Log.d(TAG, "service.process() 完成，样本数=${shortArray.size}")

                if (shortArray.isEmpty()) {
                    Log.w(TAG, "TTS 返回空音频")
                    return@withContext FloatArray(0)
                }

                // ShortArray → FloatArray 转换
                // 除以 32768f 归一化到 -1.0 ~ 1.0 范围
                // 这是音频处理的标准做法：16-bit PCM 的最大值是 32767
                FloatArray(shortArray.size) { i ->
                    shortArray[i].toFloat() / 32768f
                }
            } ?: FloatArray(0)
        }

    /**
     * 流式合成：分段回调音频数据
     *
     * TtsService 的 process() 是一次性返回全部音频，
     * 不支持真正的流式合成。
     * 这里我们模拟流式：先一次性合成，再分段回调。
     *
     * 虽然不是真正的边合成边播放，但可以减少用户感知到的等待时间——
     * 因为 AudioPlayer 可以在收到第一段数据后就开始播放。
     *
     * @param text       要合成的文字
     * @param speed      语速
     * @param onChunk    每合成一小段音频就回调一次
     * @param onComplete 合成完成回调
     */
    suspend fun synthesizeStream(
        text: String,
        speed: Float = 1.0f,
        onChunk: (FloatArray) -> Unit,
        onComplete: () -> Unit
    ) = withContext(Dispatchers.Default) {
        ttsService?.let { service ->
            // 一次性合成完整音频
            val shortArray = service.process(text, currentSpeakerId)

            // 分段回调（每段约 4096 个样本，约 93ms @44100Hz）
            val chunkSize = 4096
            var offset = 0
            while (offset < shortArray.size) {
                val end = minOf(offset + chunkSize, shortArray.size)
                val chunk = FloatArray(end - offset) { i ->
                    shortArray[offset + i].toFloat() / 32768f
                }
                onChunk(chunk)
                offset = end
            }
        }
        onComplete()
    }

    /**
     * 释放资源
     *
     * 调用 TtsService.destroy() 释放 C++ 层的模型内存。
     * Native 内存不受 JVM GC 管理，必须手动释放。
     */
    fun release() {
        ttsService?.destroy()
        ttsService = null
        Log.d(TAG, "TTSEngine released")
    }

    companion object {
        private const val TAG = "TTSEngine"
    }
}

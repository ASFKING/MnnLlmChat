package com.poc.ondevice.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * AudioRecorder：录音工具
 *
 * 一句话定义：用手机麦克风录制音频，输出 PCM 数据。
 * 生活类比：就像一个录音笔——按下录音键开始录，按下停止键结束录，
 *           录完后给你一盘磁带（PCM 数据）。
 *
 * 技术细节：
 * - 使用 Android 的 AudioRecord API 录制原始 PCM 音频
 * - 采样率 16kHz，单声道，16bit（和 ASR 模型要求一致）
 * - 输出 Flow<FloatArray>，每帧约 100ms 的音频数据
 *
 * 为什么用 AudioRecord 而不是 MediaRecorder？
 * MediaRecorder 输出的是压缩格式（MP3/AAC），需要先解码才能给 ASR。
 * AudioRecord 直接输出原始 PCM 数据，省去解码步骤，延迟更低。
 *
 * @param context Android Context（用于检查录音权限）
 */
class AudioRecorder(private val context: Context) {

    /** audioRecord：Android 录音器实例 */
    private var audioRecord: AudioRecord? = null

    /** sampleRate：采样率，16kHz 是 ASR 的标准采样率 */
    private val sampleRate = 16000

    /** isRecording：是否正在录音 */
    @Volatile
    private var isRecording = false

    /**
     * 开始录音，返回音频数据流
     *
     * Flow<FloatArray>：Kotlin 的异步数据流
     * - 每次 emit() 发射一帧音频数据（约 100ms）
     * - 调用方通过 collect {} 接收数据
     * - stopRecording() 后 Flow 自动结束
     *
     * 类比：Flow 就像一条水管，录音数据是水——
     *       水龙头（AudioRecord）开着，水就一直流；
     *       关掉水龙头（stopRecording），水就停了。
     *
     * @return 音频数据 Flow，每个元素是一帧 FloatArray（范围 -1.0 ~ 1.0）
     */
    fun startRecording(): Flow<FloatArray> = flow {
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return@flow  // 没有权限，直接结束 Flow
        }

        // 计算最小缓冲区大小
        // getMinBufferSize：返回 AudioRecord 能正常工作的最小缓冲区（字节）
        // 参数：采样率、声道配置、音频格式
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,      // 单声道（ASR 不需要立体声）
            AudioFormat.ENCODING_PCM_16BIT     // 16bit PCM（每个样本 2 字节）
        )

        // 创建 AudioRecord 实例
        // AudioSource.MIC：使用手机麦克风
        // 缓冲区取最小值的 2 倍，避免录音时数据溢出丢失
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )

        // 开始录音
        audioRecord?.startRecording()
        isRecording = true
        Log.d(TAG, "Recording started")

        // ShortArray 缓冲区：每次读取一帧
        // 帧大小 = 缓冲区大小 / 2（因为 Short 占 2 字节）
        // 约 100ms 一帧（16000 * 0.1 = 1600 个样本）
        val buffer = ShortArray(minBufferSize / 2)

        // 循环读取音频数据，直到停止录音
        while (isRecording) {
            // read()：从麦克风读取音频数据到 buffer
            // 返回实际读取的样本数（可能小于 buffer 大小）
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                // Short → Float 归一化
                // AudioRecord 输出的 Short 范围是 -32768 ~ 32767
                // ASR 引擎需要 Float 范围是 -1.0 ~ 1.0
                // 所以每个样本除以 32768f
                val floatBuffer = FloatArray(read) { i ->
                    buffer[i].toFloat() / 32768f
                }
                // emit()：向 Flow 发射一帧数据
                // 调用方的 collect {} 会收到这个数据
                emit(floatBuffer)
            }
        }

        Log.d(TAG, "Recording flow ended")
    }.flowOn(Dispatchers.IO)  // flowOn：指定 Flow 的上游在 IO 线程执行

    /**
     * 停止录音
     *
     * 调用后，startRecording() 返回的 Flow 会自然结束。
     * 类比：关掉水龙头，水管里的水流完就没了。
     */
    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Recording stopped")
    }

    /**
     * 检查是否正在录音
     */
    fun isActive(): Boolean = isRecording

    companion object {
        private const val TAG = "AudioRecorder"
    }
}

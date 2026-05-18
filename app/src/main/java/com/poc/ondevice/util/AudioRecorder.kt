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

    /** isRecording：是否正在录音（volatile 保证多线程可见性） */
    @Volatile
    private var isRecording = false

    /** recordingState：录音器当前状态（用于调试） */
    @Volatile
    private var recordingState = "IDLE"

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
        // ============ 第一步：检查权限 ============
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ RECORD_AUDIO permission not granted")
            recordingState = "ERROR_NO_PERMISSION"
            return@flow
        }
        Log.d(TAG, "✅ Permission OK")

        // ============ 第二步：计算缓冲区大小 ============
        // getMinBufferSize：返回 AudioRecord 能正常工作的最小缓冲区（字节）
        // 返回值 > 0 才是有效值，负数表示错误
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,      // 单声道（ASR 不需要立体声）
            AudioFormat.ENCODING_PCM_16BIT     // 16bit PCM（每个样本 2 字节）
        )

        // 检查 getMinBufferSize 返回值是否有效
        if (minBufferSize <= 0) {
            Log.e(TAG, "❌ getMinBufferSize returned invalid value: $minBufferSize")
            recordingState = "ERROR_BAD_BUFFER_SIZE"
            return@flow
        }
        Log.d(TAG, "✅ minBufferSize = $minBufferSize bytes (samples per chunk: ${minBufferSize / 2})")

        // ============ 第三步：创建 AudioRecord 实例 ============
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2  // 缓冲区取最小值的 2 倍，避免数据溢出
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create AudioRecord", e)
            recordingState = "ERROR_CREATE_FAILED"
            return@flow
        }

        // 检查 AudioRecord 是否初始化成功
        // STATE_INITIALIZED 表示录音器就绪，可以开始录音
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ AudioRecord not initialized. State: ${audioRecord?.state}")
            // state 可能是 STATE_UNINITIALIZED (0) 或 STATE_INITIALIZED (1)
            audioRecord?.release()
            audioRecord = null
            recordingState = "ERROR_NOT_INITIALIZED"
            return@flow
        }
        Log.d(TAG, "✅ AudioRecord created and initialized")

        // ============ 第四步：开始录音 ============
        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "❌ startRecording() failed", e)
            audioRecord?.release()
            audioRecord = null
            recordingState = "ERROR_START_FAILED"
            return@flow
        }

        // 检查是否真正开始录音
        // RECORDSTATE_RECORDING (3) 表示正在录音
        // RECORDSTATE_STOPPED (1) 表示已停止
        val recordState = audioRecord?.recordingState
        if (recordState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "❌ startRecording() did not start. recordingState: $recordState")
            audioRecord?.release()
            audioRecord = null
            recordingState = "ERROR_NOT_RECORDING"
            return@flow
        }

        isRecording = true
        recordingState = "RECORDING"
        Log.d(TAG, "🎙️ Recording started successfully (sampleRate=$sampleRate)")

        // ============ 第五步：循环读取音频数据 ============
        // ShortArray 缓冲区：每次读取一帧
        // 帧大小 = 缓冲区大小 / 2（因为 Short 占 2 字节）
        // 约 100ms 一帧（16000 * 0.1 = 1600 个样本）
        val buffer = ShortArray(minBufferSize / 2)
        var totalSamples = 0
        var chunkCount = 0
        var errorCount = 0

        while (isRecording) {
            // read()：从麦克风读取音频数据到 buffer
            // 返回值：
            //   > 0 : 实际读取的样本数（正常）
            //   0   : 未读取到数据（可能是缓冲区问题）
            //   ERROR_INVALID_OPERATION (-3) : AudioRecord 状态不对
            //   ERROR_BAD_VALUE (-2) : 参数错误
            //   ERROR_DEAD_OBJECT (-6) : AudioRecord 已释放
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            when {
                read > 0 -> {
                    // 正常：读取到数据
                    val floatBuffer = FloatArray(read) { i ->
                        buffer[i].toFloat() / 32768f
                    }
                    totalSamples += read
                    chunkCount++
                    emit(floatBuffer)
                }
                read == 0 -> {
                    // read() 返回 0：未读取到数据，但不是错误
                    // 继续循环，下次可能就有数据了
                    errorCount++
                    if (errorCount <= 3) {
                        Log.w(TAG, "⚠️ read() returned 0 (attempt $errorCount)")
                    }
                }
                read < 0 -> {
                    // read() 返回负数：发生错误
                    Log.e(TAG, "❌ read() returned error code: $read (total collected: $totalSamples samples in $chunkCount chunks)")
                    errorCount++
                    // 连续错误超过 3 次就放弃
                    if (errorCount > 3) {
                        Log.e(TAG, "❌ Too many read errors, stopping recording")
                        break
                    }
                }
            }
        }

        Log.d(TAG, "📊 Recording flow ended: $totalSamples samples in $chunkCount chunks (errors: $errorCount)")
        recordingState = "STOPPED"

    }.flowOn(Dispatchers.IO)  // flowOn：指定 Flow 的上游在 IO 线程执行

    /**
     * 停止录音
     *
     * 调用后，startRecording() 返回的 Flow 会自然结束。
     * 类比：关掉水龙头，水管里的水流完就没了。
     */
    fun stopRecording() {
        Log.d(TAG, "stopRecording() called, current state: $recordingState")
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop() failed", e)
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.release() failed", e)
        }
        audioRecord = null
        recordingState = "IDLE"
        Log.d(TAG, "Recording stopped and released")
    }

    /**
     * 检查是否正在录音
     */
    fun isActive(): Boolean = isRecording

    /**
     * 获取当前录音状态（用于调试）
     */
    fun getState(): String = recordingState

    companion object {
        private const val TAG = "AudioRecorder"
    }
}

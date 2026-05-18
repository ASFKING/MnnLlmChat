package com.poc.ondevice.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AudioPlayer：PCM 音频播放工具
 *
 * 一句话定义：播放原始 PCM 音频数据（TTS 合成的输出）。
 * 生活类比：就像一个音箱——把音频数据接进来，就能听到声音。
 *
 * 为什么用 AudioTrack 而不是 MediaPlayer？
 * MediaPlayer 只能播放文件/URL（MP3、WAV 等格式），
 * AudioTrack 可以直接播放内存中的原始 PCM 数据，不需要先写文件。
 * TTS 引擎输出的是 FloatArray PCM 数据，用 AudioTrack 最直接。
 *
 * 技术细节：
 * - 使用 AudioTrack（Android 最底层的音频播放 API）
 * - 支持 Float PCM 格式（和 TTS 输出一致）
 * - 支持阻塞播放（播放完再继续）和异步播放
 */
class AudioPlayer {

    /** audioTrack：Android 音频播放器实例 */
    private var audioTrack: AudioTrack? = null

    /** isPlaying：是否正在播放 */
    @Volatile
    private var isPlaying = false

    /**
     * 播放音频数据（阻塞方式）
     *
     * 调用后会阻塞当前线程，直到音频播放完毕。
     * 所以要在协程（Dispatchers.Default）中调用，避免阻塞主线程。
     *
     * 类比：就像放磁带——按下播放键，磁带转完才停。
     *
     * @param samples    音频样本（FloatArray，范围 -1.0 ~ 1.0）
     * @param sampleRate 采样率（TTS 引擎返回的，如 44100）
     */
    suspend fun playBlocking(samples: FloatArray, sampleRate: Int) =
        withContext(Dispatchers.Default) {
            if (samples.isEmpty()) {
                Log.w(TAG, "Empty samples, skip playing")
                return@withContext
            }

            try {
                // 创建 AudioTrack 实例
                // AudioAttributes：描述音频用途（MEDIA = 音乐/语音）
                // AudioFormat：描述音频格式（PCM Float、单声道、采样率）
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(bufferSize, samples.size * 4))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                // 写入音频数据并播放
                audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                audioTrack?.play()
                isPlaying = true

                Log.d(TAG, "Playing audio: ${samples.size} samples, ${sampleRate}Hz")

                // 等待播放完成
                // getPlaybackHeadPosition 返回已播放的帧数
                while (isPlaying && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val position = audioTrack?.playbackHeadPosition ?: 0
                    if (position >= samples.size) {
                        break
                    }
                    // 每 50ms 检查一次
                    Thread.sleep(50)
                }

                Log.d(TAG, "Playback finished")
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
            } finally {
                release()
            }
        }

    /**
     * 流式播放：边接收音频数据边播放
     *
     * 适合 TTS 流式合成的场景——每合成一小段就播放，不用等全部合成完。
     * 类比：电台直播——主播说一句，你听一句，不用等他说完。
     *
     * @param sampleRate 采样率
     * @param onReady    准备好接收数据的回调，返回一个 push 函数
     */
    fun startStreamingPlayback(sampleRate: Int, onReady: (push: (FloatArray) -> Unit) -> Unit) {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)  // 流式模式
                .build()

            audioTrack?.play()
            isPlaying = true

            // 提供 push 函数，调用方可以用它推送音频数据
            onReady { chunk ->
                if (isPlaying) {
                    audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Streaming playback setup failed", e)
        }
    }

    /**
     * 停止播放
     */
    fun stop() {
        isPlaying = false
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.stop() failed", e)
        }
        release()
    }

    /**
     * 释放资源
     */
    private fun release() {
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.release() failed", e)
        }
        audioTrack = null
        isPlaying = false
    }

    companion object {
        private const val TAG = "AudioPlayer"
    }
}

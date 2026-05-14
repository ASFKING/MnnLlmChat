package com.alibaba.mnnllm.android.llm

/**
 * AudioDataListener：音频数据回调接口
 *
 * 一句话：Omni 模型生成音频数据时，通过这个接口通知调用方。
 * 生活类比：就像音乐播放器的"边下边播"——下载一段就播放一段。
 * 技术细节：MNN 的 Omni 模型（如 Qwen2-Audio）在生成文本的同时，
 *           可以生成对应的语音数据。这个接口用于接收这些 PCM 音频数据。
 *
 * 用途：
 * - 接收模型生成的音频波形数据（PCM float 格式）
 * - 实时播放（流式传输给 AudioTrack）
 * - isEnd=true 表示音频生成完毕
 */
interface AudioDataListener {

    /**
     * onAudioData：接收到音频数据时被调用
     *
     * @param data PCM 音频数据（float 格式，范围 -1.0 到 1.0）
     * @param isEnd 是否是最后一块数据
     * @return true = 停止生成音频，false = 继续
     */
    fun onAudioData(data: FloatArray, isEnd: Boolean): Boolean
}

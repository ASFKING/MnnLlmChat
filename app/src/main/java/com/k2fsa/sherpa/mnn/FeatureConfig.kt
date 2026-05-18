package com.k2fsa.sherpa.mnn

/**
 * FeatureConfig：音频特征配置
 *
 * 一句话定义：告诉 ASR 引擎"我给你的音频长什么样"。
 * 生活类比：就像你告诉翻译官"我说话的速度是正常语速，用中文"——
 *           翻译官根据这些信息调整自己的接收方式。
 * 技术细节：配置 Mel 滤波器组的参数，决定音频如何被转成特征向量。
 *
 * @param sampleRate 采样率（每秒采集多少个音频样本点），默认 16000（16kHz）
 *                   为什么是 16kHz？人声的主要频率在 300Hz-3400Hz，
 *                   根据奈奎斯特定理，采样率需要 >= 最高频率的 2 倍，
 *                   16kHz 足以覆盖人声频率范围。
 * @param featureDim 特征维度，默认 80
 *                   每帧音频被转成 80 维的特征向量（Mel 频谱系数）
 */
data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
)

/**
 * 获取特征配置的便捷函数
 *
 * @param sampleRate 采样率
 * @param featureDim 特征维度
 * @return FeatureConfig 实例
 */
fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}

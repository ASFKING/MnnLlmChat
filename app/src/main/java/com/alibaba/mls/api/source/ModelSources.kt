package com.alibaba.mls.api.source

/**
 * ModelSources：模型来源常量
 *
 * 一句话：定义了模型的各个来源标识符。
 * 生活类比：就像快递公司的标识——顺丰、京东、中通，每家的快递单号格式不同。
 * 技术细节：MNN 支持从多个来源下载模型，每个来源的 API 和路径格式不同。
 */
object ModelSources {
    const val sourceModelScope = "ModelScope"     // 阿里云 ModelScope（国内首选）
    const val sourceHuffingFace = "HuggingFace"   // HuggingFace（海外首选）
    const val sourceModelers = "Modelers"          // 魔搭 Modelers
}

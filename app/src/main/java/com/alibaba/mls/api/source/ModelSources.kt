package com.alibaba.mls.api.source

/**
 * ModelSources：模型来源常量
 *
 * 原始 MNN 项目中，定义了模型的各个来源标识符。
 * PoC 阶段我们主要用 ModelScope 作为模型源。
 */
object ModelSources {
    const val sourceModelScope = "ModelScope"
    const val sourceHuffingFace = "HuggingFace"
    const val sourceModelers = "Modelers"
}

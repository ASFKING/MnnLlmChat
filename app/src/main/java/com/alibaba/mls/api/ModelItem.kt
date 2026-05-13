package com.alibaba.mls.api

/**
 * ModelItem：模型信息的数据类
 *
 * 原始 MNN 项目中，描述一个可下载模型的元信息。
 * PoC 阶段我们用自己的 ModelEntry 替代，这个类只为了让原始代码编译通过。
 */
data class ModelItem(
    val modelId: String = "",
    val modelName: String = "",
    val modelPath: String = "",
    val tags: List<String> = emptyList()
)

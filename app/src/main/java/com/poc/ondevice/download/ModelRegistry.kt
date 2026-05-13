package com.poc.ondevice.download

/**
 * ModelRegistry：模型注册表
 *
 * 存储所有可下载的模型列表
 * 类比：就像餐厅菜单——列出了所有可以点的菜（模型）
 *
 * 为什么单独建一个注册表：
 * - 集中管理模型列表，方便增删改
 * - HomeFragment 只需要读取这个列表，不需要知道模型细节
 * - 后续可以扩展为从服务器动态拉取模型列表
 */
object ModelRegistry {

    /**
     * 默认模型列表
     *
     * 每个 ModelEntry 对应一个 ModelScope 上的 MNN 模型仓库
     * modelId 格式：MNN/{modelName}，对应 ModelScope 的仓库路径
     */
    val defaultModels = listOf(
        ModelEntry(
            modelId = "MNN/Qwen3-1.7B-MNN",
            displayName = "Qwen3-1.7B",
            description = "通义千问3 1.7B 参数，适合 8GB 设备，支持深度思考",
            sizeGB = 1.7,
            modelDirName = "Qwen3-1.7B-MNN"
        ),
        ModelEntry(
            modelId = "MNN/Qwen3-0.6B-MNN",
            displayName = "Qwen3-0.6B",
            description = "通义千问3 0.6B 参数，超轻量，适合低配设备",
            sizeGB = 0.6,
            modelDirName = "Qwen3-0.6B-MNN"
        ),
        ModelEntry(
            modelId = "MNN/Qwen3-4B-MNN",
            displayName = "Qwen3-4B",
            description = "通义千问3 4B 参数，效果更好，需要 12GB+ 设备",
            sizeGB = 4.0,
            modelDirName = "Qwen3-4B-MNN"
        )
    )
}

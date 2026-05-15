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
// object：Kotlin 单例声明
// 整个 App 只有一个 ModelRegistry 实例，内存中只存在一份
// 类比：菜单只有一份，所有服务员共用
object ModelRegistry {

    /**
     * 默认模型列表
     *
     * listOf()：创建不可变列表（创建后不能添加/删除元素）
     * 每个 ModelEntry 对应一个可下载的模型
     *
     * 两种下载模式：
     * 1. ModelScope 模式：modelId = "MNN/Qwen3-1.7B-MNN"，directFiles = null
     *    → 通过 ModelScope API 下载整个仓库
     * 2. 直接 URL 模式：directFiles = listOf(DirectFile(...))
     *    → 从指定 URL 逐个下载文件（适用于 HuggingFace 等非 ModelScope 来源）
     */
    val defaultModels = listOf(
        // ===== LLM 推理模型（ModelScope 来源）=====
        ModelEntry(
            modelId = "MNN/Qwen3-1.7B-MNN",       // ModelScope 仓库路径
            displayName = "Qwen3-1.7B",              // UI 显示名称
            description = "通义千问3 1.7B 参数，适合 8GB 设备，支持深度思考",
            sizeGB = 1.7,                            // 模型大小，用于预估下载时间
            modelDirName = "Qwen3-1.7B-MNN"          // 本地存储目录名
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
        ),

        // ===== 文本嵌入模型（ModelScope 来源，复用 LLM 框架）=====
        // bge-large-zh-mnn：阿里巴巴 MNN 团队用 llmexport.py 导出的 BERT 嵌入模型
        // 4-bit 量化，复用 libmnnllmapp.so 加载，不需要额外 JNI 或 ONNX Runtime
        // llm_config.json 中 prompt_template: "[CLS]%s[SEP]"，key_value_shape: []（无 KV Cache）
        ModelEntry(
            modelId = "MNN/bge-large-zh-mnn",     // ModelScope 仓库路径
            displayName = "bge-large-zh (嵌入模型)",
            description = "中文文本嵌入模型，1024 维输出，4-bit 量化，用于 RAG 文档检索",
            sizeGB = 0.22,                          // ~173MB embedding.mnn + ~43MB embeddings_bf16.bin ≈ 216MB
            modelDirName = "bge-large-zh-mnn"
        )
    )
}

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

        // ===== 文本嵌入模型（HuggingFace 来源，直接 URL 下载）=====
        // bge-small-zh-v1.5：智源研究院的中文文本嵌入模型
        // 输出 512 维向量，用于 RAG 的文档检索
        // ModelScope 上只有 PyTorch/safetensors 格式，ONNX 格式只在 HuggingFace 上有
        // 所以用 directFiles 模式从 HuggingFace 直接下载
        ModelEntry(
            modelId = "BAAI/bge-small-zh-v1.5",      // 仅用于标识，实际不走 ModelScope API
            displayName = "bge-small-zh (嵌入模型)",
            description = "中文文本嵌入模型，512 维输出，用于 RAG 文档检索",
            sizeGB = 0.1,                             // ~93MB ONNX + ~439KB tokenizer ≈ 0.1GB
            modelDirName = "bge-small-zh-v1.5",
            directFiles = listOf(
                DirectFile(
                    // HuggingFace 上 bge-small-zh-v1.5 的 ONNX 模型文件
                    // 格式：https://huggingface.co/{repo}/resolve/main/{path}
                    url = "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/onnx/model.onnx",
                    fileName = "model.onnx",          // 本地保存文件名
                    fileSize = 93674400                // ~93MB（字节）
                ),
                DirectFile(
                    // HuggingFace 上 bge-small-zh-v1.5 的分词器配置
                    url = "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/tokenizer.json",
                    fileName = "tokenizer.json",       // 本地保存文件名
                    fileSize = 439125                  // ~439KB（字节）
                )
            )
        )
    )
}

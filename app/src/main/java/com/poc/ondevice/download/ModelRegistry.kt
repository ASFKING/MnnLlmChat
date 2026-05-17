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

        // ===== 文本嵌入模型（hf-mirror 直接下载 ONNX 格式）=====
        // bge-base-zh-v1.5 ONNX 版本，来源：hf-mirror.com/moyangzhan/bge-base-zh-v1.5-onnx
        // 使用 QInt8 量化版本，体积更小，推理更快
        // 下载后由 ONNX Runtime 加载，用于文本嵌入和 RAG 检索
        ModelEntry(
            modelId = "moyangzhan/bge-base-zh-v1.5-onnx",  // 仅用于标识，不走 ModelScope API
            displayName = "bge-base-zh (嵌入模型)",
            description = "中文文本嵌入模型，768 维输出，QInt8 量化，用于 RAG 文档检索",
            sizeGB = 0.1,                                   // ~100MB
            modelDirName = "bge-base-zh-v1.5-onnx",         // 本地存储目录名
            directFiles = listOf(
                // 模型文件：QInt8 量化版（约 67MB）
                // hf-mirror 下载地址格式：/resolve/main/{filename}
                DirectFile(
                    url = "https://hf-mirror.com/moyangzhan/bge-base-zh-v1.5-onnx/resolve/main/model_opt2_QInt8.onnx",
                    fileName = "model.onnx",                // 本地统一命名为 model.onnx
                    fileSize = 67_000_000                   // 约 67MB（QInt8 量化）
                ),
                // 分词器文件
                DirectFile(
                    url = "https://hf-mirror.com/moyangzhan/bge-base-zh-v1.5-onnx/resolve/main/tokenizer.json",
                    fileName = "tokenizer.json",
                    fileSize = 500_000                      // 约 500KB
                )
            )
        )
    )
}

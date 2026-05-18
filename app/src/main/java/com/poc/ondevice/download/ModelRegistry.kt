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

        // ===== 多模态视觉模型（ModelScope 来源）=====
        // Qwen3.5-2B-MNN：Qwen3.5 系列是多模态模型，同时支持文字推理和图片理解
        // tags: ["Think", "Vision"] — 阿里官方模型市场确认支持图像理解
        // 2B 参数，约 2GB，适合 8GB 设备
        ModelEntry(
            modelId = "MNN/Qwen3.5-2B-MNN",              // ModelScope 仓库路径
            displayName = "Qwen3.5-2B (多模态)",           // UI 显示名称
            description = "通义千问3.5 2B 多模态模型，支持深度思考 + 图片理解，适合 8GB 设备",
            sizeGB = 2.0,                                   // 模型大小，约 2GB
            modelDirName = "Qwen3.5-2B-MNN"                // 本地存储目录名
        ),

        // ===== 语音识别模型（ModelScope 来源，MNN 格式）=====
        // 官方 MnnLlmChat 使用的 ASR 模型：流式 Zipformer，中英双语
        // ModelScope: MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20
        // 使用 OnlineRecognizer（流式识别），不是 OfflineRecognizer
        ModelEntry(
            modelId = "MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20",
            displayName = "Zipformer ASR (中英双语)",
            description = "流式语音识别，中英双语，~295MB，MNN 格式",
            sizeGB = 0.3,
            modelDirName = "sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20"
        ),

        // ===== 语音合成模型（ModelScope 来源，MNN 格式）=====
        // 官方 MnnLlmChat 使用的 TTS 模型
        ModelEntry(
            modelId = "MNN/bert-vits2-MNN",
            displayName = "BertVITS2 TTS (中英)",
            description = "中英双语语音合成，音质自然，~1.4GB，MNN 格式",
            sizeGB = 1.4,
            modelDirName = "bert-vits2-MNN"
        ),
        ModelEntry(
            modelId = "MNN/supertonic-tts-mnn",
            displayName = "SuperTonic TTS (英文)",
            description = "英文语音合成，轻量快速，~464MB，MNN 格式",
            sizeGB = 0.5,
            modelDirName = "supertonic-tts-mnn"
        ),

        // ===== 文本嵌入模型（hf-mirror 直接下载 ONNX 格式）=====
        // bge-small-zh-v1.5 ONNX 版本，来源：hf-mirror.com/onnx-community/bge-small-zh-v1.5-ONNX
        // onnx-community 是 HuggingFace 官方 ONNX 转换组织
        // 注意：该模型使用外部数据格式，需要下载 model.onnx（结构）+ model.onnx_data（权重）两个文件
        ModelEntry(
            modelId = "onnx-community/bge-small-zh-v1.5-ONNX",  // 仅用于标识
            displayName = "bge-small-zh (嵌入模型)",
            description = "中文文本嵌入模型，512 维输出，用于 RAG 文档检索",
            sizeGB = 0.1,                                      // ~95MB（结构+权重）
            modelDirName = "bge-small-zh-v1.5-onnx",            // 本地存储目录名
            directFiles = listOf(
                // 模型结构文件（约 42KB）
                DirectFile(
                    url = "https://hf-mirror.com/onnx-community/bge-small-zh-v1.5-ONNX/resolve/main/onnx/model.onnx",
                    fileName = "model.onnx",
                    fileSize = 42_000
                ),
                // 模型权重文件（约 90MB）— 必须下载，否则 ONNX Runtime 无法正确加载
                DirectFile(
                    url = "https://hf-mirror.com/onnx-community/bge-small-zh-v1.5-ONNX/resolve/main/onnx/model.onnx_data",
                    fileName = "model.onnx_data",
                    fileSize = 95_000_000
                ),
                // 分词器文件
                DirectFile(
                    url = "https://hf-mirror.com/onnx-community/bge-small-zh-v1.5-ONNX/resolve/main/tokenizer.json",
                    fileName = "tokenizer.json",
                    fileSize = 363_000
                )
            )
        )
    )
}

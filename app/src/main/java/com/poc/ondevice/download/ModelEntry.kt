package com.poc.ondevice.download

/**
 * ModelEntry：模型配置条目
 *
 * 定义一个可下载模型的基本信息
 * 类比：就像图书馆的索引卡——记录了书名、作者、位置
 *
 * 支持两种下载模式：
 * 1. ModelScope 仓库模式：通过 modelId（"owner/repo"）调用 ModelScope API 下载整个仓库
 * 2. 直接 URL 模式：通过 directFiles 列表，逐个下载指定 URL 的文件
 *
 * @param modelId      模型 ID，格式为 "owner/repo"，用于 ModelScope API 调用
 * @param displayName  显示名称，给用户看的
 * @param description  模型描述
 * @param sizeGB       模型大小（GB），用于 UI 显示预估下载时间
 * @param modelDirName 模型在本地存储的目录名
 * @param directFiles  直接下载文件列表（不通过 ModelScope API，而是直接从 URL 下载）
 */
// data class：Kotlin 数据类
// 自动生成 equals()、hashCode()、toString()、copy() 方法
// 适合用来表示"纯数据"的对象（没有复杂业务逻辑）
data class ModelEntry(
    val modelId: String,      // ModelScope 仓库 ID，如 "MNN/Qwen3-1.7B-MNN"
    val displayName: String,   // UI 显示名，如 "Qwen3-1.7B"
    val description: String,   // 模型描述文字
    val sizeGB: Double,        // 模型大小（GB），用于下载前预估
    val modelDirName: String,  // 本地目录名，如 "Qwen3-1.7B-MNN"
    // directFiles：直接下载文件列表
    // null 或空列表 → 使用 ModelScope API 按 modelId 下载整个仓库
    // 非空 → 逐个下载这些文件（每个文件有独立的 URL）
    // 这样设计的好处：同一个 ModelEntry 可以支持两种下载方式，不需要子类
    val directFiles: List<DirectFile>? = null
)

/**
 * DirectFile：直接下载文件配置
 *
 * 当模型不在 ModelScope 上（或只有部分文件在 ModelScope）时，
 * 用这个类指定每个文件的下载 URL 和本地文件名。
 *
 * 例如 bge-small-zh-v1.5 的 ONNX 模型只在 HuggingFace 上有，
 * 就用 DirectFile 指定 HuggingFace 的下载地址。
 *
 * @param url       文件的完整下载 URL
 * @param fileName  下载后保存的文件名（如 "model.onnx"）
 * @param fileSize  文件大小（字节），用于进度显示（可选，0 表示未知）
 */
data class DirectFile(
    val url: String,       // 完整下载 URL，如 "https://huggingface.co/.../model.onnx"
    val fileName: String,  // 本地保存文件名，如 "model.onnx"
    val fileSize: Long = 0 // 文件大小（字节），0 表示未知
)

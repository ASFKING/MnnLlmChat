package com.poc.ondevice.download

/**
 * ModelEntry：模型配置条目
 *
 * 定义一个可下载模型的基本信息
 * 类比：就像图书馆的索引卡——记录了书名、作者、位置
 *
 * @param modelId    模型 ID，格式为 "owner/repo"，用于 ModelScope API 调用
 * @param displayName 显示名称，给用户看的
 * @param description 模型描述
 * @param sizeGB     模型大小（GB），用于 UI 显示预估下载时间
 * @param modelDirName 模型在本地存储的目录名
 */
data class ModelEntry(
    val modelId: String,
    val displayName: String,
    val description: String,
    val sizeGB: Double,
    val modelDirName: String
)

package com.poc.ondevice.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * DocumentStore：文档持久化存储
 *
 * 职责：
 * - 把用户上传的文档保存到本地 JSON 文件
 * - 支持加载、列出、删除文档
 *
 * 为什么用 JSON 文件而不是 Room 数据库？
 * PoC 阶段数据量小，JSON 文件足够简单。
 * 生产阶段再迁移到 Room + SQLite。
 *
 * 存储位置：/data/data/com.poc.ondevice/files/docs/{id}.json
 */
class DocumentStore(private val context: Context) {

    /**
     * docsDir：文档存储目录
     * .apply { mkdirs() } 是 Kotlin 的作用域函数，
     * 在创建 File 对象的同时自动创建目录（如果不存在的话）
     */
    private val docsDir = File(context.filesDir, "docs").apply { mkdirs() }

    /** Gson 实例，用于 JSON 序列化/反序列化 */
    private val gson = Gson()

    /**
     * 保存文档到本地文件
     *
     * @param doc 要保存的文档数据
     */
    fun save(doc: DocumentData) {
        // File(dir, name) 构造文件路径
        val file = File(docsDir, "${doc.id}.json")
        // gson.toJson() 把对象转成 JSON 字符串
        // writeText() 把字符串写入文件（Kotlin 扩展函数）
        file.writeText(gson.toJson(doc))
        Log.d(TAG, "文档已保存: ${doc.id} - ${doc.title}")
    }

    /**
     * 按 ID 加载单个文档
     *
     * @param id 文档 ID
     * @return 文档数据，不存在则返回 null
     */
    fun load(id: String): DocumentData? {
        val file = File(docsDir, "$id.json")
        return if (file.exists()) {
            try {
                // readText() 读取文件全部内容（Kotlin 扩展函数）
                // gson.fromJson() 把 JSON 字符串转回对象
                gson.fromJson(file.readText(), DocumentData::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "加载文档失败: $id", e)
                null
            }
        } else null
    }

    /**
     * 加载所有文档，按创建时间降序排列（最新的在前）
     */
    fun loadAll(): List<DocumentData> {
        return docsDir.listFiles()
            ?.filter { it.name.endsWith(".json") }            // 只要 .json 文件
            ?.mapNotNull { file ->
                try {
                    gson.fromJson(file.readText(), DocumentData::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "解析文档失败: ${file.name}", e)
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }              // 按创建时间降序
            ?: emptyList()
    }

    /**
     * 删除文档
     */
    fun delete(id: String) {
        val file = File(docsDir, "$id.json")
        if (file.exists()) {
            file.delete()
            Log.d(TAG, "文档已删除: $id")
        }
    }

    companion object {
        private const val TAG = "DocumentStore"
    }
}

/**
 * DocumentData：文档数据模型
 *
 * @param id 唯一标识（自动生成 UUID）
 * @param title 文档标题
 * @param content 文档全文内容
 * @param chunkCount 分块数量（索引后填入）
 * @param createdAt 创建时间戳（毫秒）
 */
data class DocumentData(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val chunkCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

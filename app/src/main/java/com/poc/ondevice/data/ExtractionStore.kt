package com.poc.ondevice.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * ExtractionStore - 结构化提取记录的持久化存储
 *
 * 使用 SharedPreferences 存储提取历史
 * SharedPreferences 适合存储少量键值对数据（< 1MB）
 * 这里用它存储提取记录的 JSON 数组
 *
 * 存储位置：/data/data/com.poc.ondevice/shared_prefs/extractions.xml
 */
class ExtractionStore(context: Context) {

    // getSharedPreferences() 获取或创建一个 SharedPreferences 文件
    // MODE_PRIVATE 表示只有本 App 可以访问
    private val prefs = context.getSharedPreferences("extractions", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_RECORDS = "records"  // 存储的 key 名
        private const val MAX_RECORDS = 200        // 最多保存 200 条记录
    }

    /**
     * 保存一条提取记录
     *
     * @param inputText  原始输入文本
     * @param outputJson 提取出的 JSON
     * @param schemaName 使用的 schema 名称
     */
    fun save(inputText: String, outputJson: String, schemaName: String = "default") {
        val records = loadAll().toMutableList()
        records.add(0, ExtractionRecord(  // 插入到列表头部（最新的在前）
            inputText = inputText,
            outputJson = outputJson,
            schemaName = schemaName
        ))

        // 超过上限时删除最旧的
        if (records.size > MAX_RECORDS) {
            records.removeAt(records.size - 1)
        }

        // 序列化为 JSON 并保存
        prefs.edit()
            .putString(KEY_RECORDS, gson.toJson(records))
            .apply()  // apply() 异步写入，不阻塞主线程
    }

    /**
     * 加载所有提取记录
     *
     * @return 记录列表（最新的在前）
     */
    fun loadAll(): List<ExtractionRecord> {
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        // Gson 反序列化：JSON 字符串 → List<ExtractionRecord>
        val type = object : TypeToken<List<ExtractionRecord>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 获取统计信息
     */
    fun getStats(): ExtractionStats {
        val records = loadAll()
        val successCount = records.count { it.outputJson.isNotBlank() }
        return ExtractionStats(
            total = records.size,
            success = successCount,
            successRate = if (records.isNotEmpty()) successCount.toFloat() / records.size else 0f
        )
    }

    /**
     * 清空所有记录
     */
    fun clear() {
        prefs.edit().remove(KEY_RECORDS).apply()
    }
}

/**
 * ExtractionRecord - 单条提取记录
 *
 * data class 自动生成 equals()、hashCode()、toString()、copy()
 */
data class ExtractionRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val inputText: String,
    val outputJson: String,
    val schemaName: String = "default",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * ExtractionStats - 提取统计
 */
data class ExtractionStats(
    val total: Int,
    val success: Int,
    val successRate: Float
)

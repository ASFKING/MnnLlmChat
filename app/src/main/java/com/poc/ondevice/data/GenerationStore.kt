package com.poc.ondevice.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * GenerationStore - 文档生成记录的持久化存储
 *
 * 与 ExtractionStore 结构相同，用 SharedPreferences 存储生成历史。
 * 存储位置：/data/data/com.poc.ondevice/shared_prefs/generations.xml
 */
class GenerationStore(context: Context) {

    private val prefs = context.getSharedPreferences("generations", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 100
    }

    /**
     * 保存一条生成记录
     *
     * @param docType       文档类型（如"通知"、"报告"）
     * @param inputData     用户输入的关键信息
     * @param outputContent LLM 生成的文档内容
     */
    fun save(docType: String, inputData: String, outputContent: String) {
        val records = loadAll().toMutableList()
        records.add(0, GenerationRecord(
            docType = docType,
            inputData = inputData,
            outputContent = outputContent
        ))
        if (records.size > MAX_RECORDS) {
            records.removeAt(records.size - 1)
        }
        prefs.edit().putString(KEY_RECORDS, gson.toJson(records)).apply()
    }

    /**
     * 加载所有生成记录
     */
    fun loadAll(): List<GenerationRecord> {
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        val type = object : TypeToken<List<GenerationRecord>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 获取统计信息
     */
    fun getStats(): GenerationStats {
        val records = loadAll()
        return GenerationStats(total = records.size)
    }

    fun clear() {
        prefs.edit().remove(KEY_RECORDS).apply()
    }
}

/**
 * GenerationRecord - 单条文档生成记录
 */
data class GenerationRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val docType: String,
    val inputData: String,
    val outputContent: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * GenerationStats - 生成统计
 */
data class GenerationStats(
    val total: Int
)

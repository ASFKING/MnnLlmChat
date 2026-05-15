package com.poc.ondevice.util

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * PerformanceTracker：性能追踪工具
 *
 * 职责：
 * - 记录各个操作环节的耗时（模型加载、推理首 token、生成速度等）
 * - 统计 token 生成速度（tok/s）
 * - 提供格式化的性能报告
 *
 * 为什么需要这个？
 * 我们的 PoC 有明确的性能指标要验证：
 * - TTFT（首 token 延迟）< 2s
 * - tok/s（生成速度）> 15 tok/s
 * - ASR 延迟 < 1s
 * - TTS 延迟 < 2s
 * - RAG 端到端延迟 < 8s
 *
 * 手动掐表不现实，PerformanceTracker 就像一个自动秒表——
 * 开始时按一下，结束时按一下，自动算出耗时。
 *
 * 为什么用 ConcurrentHashMap？
 * 因为性能数据可能从多个线程写入（IO 线程、Default 线程等）
 * ConcurrentHashMap 是线程安全的 HashMap，多线程同时读写不会出问题
 * 类比：一个有多把锁的公共笔记本，每个人用自己的锁写自己的页
 */
class PerformanceTracker {

    // ==================== 数据结构 ====================

    /**
     * MetricEntry：单次计时记录
     *
     * @param label 操作名称（如 "LLM 加载"、"bge 编码"）
     * @param startTimeMs 开始时间戳（毫秒）
     * @param endTimeMs 结束时间戳（毫秒），-1 表示还未结束
     * @param extraInfo 额外信息（如 token 数量、文本长度等）
     */
    data class MetricEntry(
        val label: String,
        val startTimeMs: Long,
        var endTimeMs: Long = -1L,
        var extraInfo: String = ""
    ) {
        /**
         * 耗时（毫秒）
         *
         * 计算方式：结束时间 - 开始时间
         * 如果还未结束（endTimeMs = -1），返回 -1
         *
         * 为什么用 getter 而不是在构造时计算？
         * 因为 endTimeMs 是 var，可能在创建后被修改
         * 每次访问 durationMs 时重新计算，保证值是最新的
         */
        val durationMs: Long
            get() = if (endTimeMs > 0) endTimeMs - startTimeMs else -1L
    }

    /**
     * TokenStats：token 生成统计
     *
     * 用于计算 tok/s（每秒生成 token 数）
     *
     * @param totalTokens 总 token 数（这里用字符数近似）
     * @param totalTimeMs 总耗时（毫秒）
     * @param ttftMs 首 token 延迟（Time To First Token）
     */
    data class TokenStats(
        val totalTokens: Int,
        val totalTimeMs: Long,
        val ttftMs: Long
    ) {
        /**
         * 每秒 token 数
         *
         * 计算公式：总 token 数 / 总耗时（秒）
         * 为什么 totalTimeMs 要除以 1000？
         * 因为 totalTimeMs 是毫秒，tok/s 的"每秒"需要转成秒
         */
        val tokensPerSecond: Double
            get() = if (totalTimeMs > 0) totalTokens * 1000.0 / totalTimeMs else 0.0
    }

    // ==================== 存储 ====================

    // completedMetrics：已完成的计时记录
    // ConcurrentHashMap：线程安全的 Map，多线程同时读写不会出问题
    // 为什么用 String 作 key？因为每个操作有唯一的 label
    private val completedMetrics = ConcurrentHashMap<String, MetricEntry>()

    // activeTimers：正在进行的计时器
    // startTimer() 时添加，stopTimer() 时移除到 completedMetrics
    private val activeTimers = ConcurrentHashMap<String, MetricEntry>()

    // tokenStatsList：token 生成统计列表
    // 每次推理完成后添加一条记录
    private val tokenStatsList = mutableListOf<TokenStats>()

    // ==================== 计时操作 ====================

    /**
     * 开始计时
     *
     * 使用方法：
     * ```
     * tracker.startTimer("模型加载")
     * // ... 执行加载操作 ...
     * tracker.stopTimer("模型加载")
     * ```
     *
     * System.currentTimeMillis()：获取当前时间戳（毫秒）
     * 精度约为 10-15ms（取决于操作系统），对于我们的场景足够了
     *
     * @param label 操作名称（唯一标识）
     */
    fun startTimer(label: String) {
        // MetricEntry(label, startTimeMs)：创建一个新的计时记录
        // 此时 endTimeMs = -1（默认值），表示计时中
        activeTimers[label] = MetricEntry(label, System.currentTimeMillis())
        Log.d(TAG, "⏱ 开始计时: $label")
    }

    /**
     * 停止计时
     *
     * @param label 操作名称（必须与 startTimer 的 label 一致）
     * @param extraInfo 额外信息（可选，如 "生成了 150 个字符"）
     * @return 耗时（毫秒），如果找不到对应的开始计时则返回 -1
     */
    fun stopTimer(label: String, extraInfo: String = ""): Long {
        // remove()：从 activeTimers 中移除并返回，如果不存在返回 null
        val entry = activeTimers.remove(label) ?: run {
            // run {} 是 Kotlin 的作用域函数，这里用作"如果前面为 null 则执行"
            Log.w(TAG, "⚠ 找不到计时器: $label，是否忘记调用 startTimer()？")
            return -1L
        }

        // 记录结束时间
        entry.endTimeMs = System.currentTimeMillis()
        entry.extraInfo = extraInfo

        // 存入已完成列表
        completedMetrics[label] = entry

        val duration = entry.durationMs
        Log.d(TAG, "✅ 完成计时: $label = ${duration}ms" +
                if (extraInfo.isNotEmpty()) " ($extraInfo)" else "")

        return duration
    }

    /**
     * 记录 token 生成统计
     *
     * 在 LLM 推理完成后调用，记录本次推理的 token 数量和耗时
     *
     * @param totalTokens 本次推理生成的总 token 数（或字符数）
     * @param totalTimeMs 本次推理的总耗时（毫秒）
     * @param ttftMs 首 token 延迟（毫秒）
     */
    fun recordTokenStats(totalTokens: Int, totalTimeMs: Long, ttftMs: Long) {
        val stats = TokenStats(totalTokens, totalTimeMs, ttftMs)
        // synchronized：同步块，确保同一时刻只有一个线程能修改列表
        // 为什么？因为 tokenStatsList 是普通 MutableList，不是线程安全的
        synchronized(tokenStatsList) {
            tokenStatsList.add(stats)
        }
        Log.d(TAG, "📊 Token 统计: ${totalTokens}字符, " +
                "${totalTimeMs}ms, TTFT=${ttftMs}ms, " +
                "%.1f tok/s".format(stats.tokensPerSecond))
    }

    // ==================== 查询操作 ====================

    /**
     * 获取指定操作的耗时
     *
     * @param label 操作名称
     * @return 耗时（毫秒），如果未记录返回 -1
     */
    fun getDuration(label: String): Long {
        return completedMetrics[label]?.durationMs ?: -1L
    }

    /**
     * 获取所有已完成的计时记录
     *
     * toMap()：创建一个 Map 的副本
     * 为什么要复制？避免外部代码直接修改我们的内部数据
     * 类比：给别人看照片时给副本，底片自己留着
     */
    fun getAllMetrics(): Map<String, MetricEntry> {
        return completedMetrics.toMap()
    }

    /**
     * 获取 token 统计的平均值
     *
     * 如果有多次推理记录，计算平均的 tok/s 和 TTFT
     */
    fun getAverageTokenStats(): TokenStats? {
        synchronized(tokenStatsList) {
            if (tokenStatsList.isEmpty()) return null

            val totalTokens = tokenStatsList.sumOf { it.totalTokens }
            val totalTimeMs = tokenStatsList.sumOf { it.totalTimeMs }
            val avgTtft = tokenStatsList.map { it.ttftMs }.average().toLong()

            return TokenStats(totalTokens, totalTimeMs, avgTtft)
        }
    }

    // ==================== 报告生成 ====================

    /**
     * 生成格式化的性能报告
     *
     * 输出示例：
     * ```
     * ⚡ 性能测试报告
     * ═══════════════════════════
     * ⏱ 操作耗时
     *   模型加载: 2345ms
     *   bge 编码: 12ms
     *   RAG 端到端: 5678ms
     *
     * 🚀 Token 生成统计
     *   平均速度: 18.5 tok/s
     *   平均 TTFT: 1200ms
     *   总推理次数: 5
     * ```
     */
    fun generateReport(): String {
        return buildString {
            appendLine("⚡ 性能测试报告")
            appendLine("═══════════════════════════")

            // 操作耗时部分
            if (completedMetrics.isNotEmpty()) {
                appendLine("⏱ 操作耗时")
                // forEach {}：遍历 Map 的每个键值对
                // (label, entry) 是解构声明，分别取出 key 和 value
                for ((label, entry) in completedMetrics) {
                    // "%,d"：数字格式化，加千位分隔符（如 1,234）
                    appendLine("  $label: ${"%,d".format(entry.durationMs)}ms" +
                            if (entry.extraInfo.isNotEmpty()) " (${entry.extraInfo})" else "")
                }
                appendLine()
            }

            // Token 统计部分
            val avgStats = getAverageTokenStats()
            if (avgStats != null) {
                appendLine("🚀 Token 生成统计")
                appendLine("  平均速度: %.1f tok/s".format(avgStats.tokensPerSecond))
                appendLine("  平均 TTFT: ${avgStats.ttftMs}ms")
                appendLine("  总推理次数: ${tokenStatsList.size}")
                appendLine("  总字符数: ${avgStats.totalTokens}")
            } else {
                appendLine("🚀 Token 生成统计: 暂无数据")
            }
        }
    }

    /**
     * 生成简短的性能摘要（一行文字）
     *
     * 适合在状态栏中显示
     * 例如："TTFT: 1.2s | 18.5 tok/s | RAG: 5.7s"
     */
    fun getBriefSummary(): String {
        val parts = mutableListOf<String>()

        // 如果有 TTFT 数据
        val ttft = getDuration("首 Token")
        if (ttft > 0) {
            // "%.1f"：保留一位小数的浮点数
            // ttft / 1000.0：毫秒 → 秒
            parts.add("TTFT: %.1fs".format(ttft / 1000.0))
        }

        // 如果有 token 统计
        val avgStats = getAverageTokenStats()
        if (avgStats != null) {
            parts.add("%.1f tok/s".format(avgStats.tokensPerSecond))
        }

        // 如果有 RAG 耗时
        val ragDuration = getDuration("RAG 端到端")
        if (ragDuration > 0) {
            parts.add("RAG: %.1fs".format(ragDuration / 1000.0))
        }

        return if (parts.isNotEmpty()) parts.joinToString(" | ") else "暂无性能数据"
    }

    /**
     * 清空所有记录
     *
     * 什么时候用？
     * - 重新开始一轮测试前
     * - 切换模型后（之前的统计数据不再适用）
     */
    fun clear() {
        completedMetrics.clear()
        activeTimers.clear()
        synchronized(tokenStatsList) {
            tokenStatsList.clear()
        }
        Log.d(TAG, "🗑 已清空所有性能记录")
    }

    companion object {
        private const val TAG = "PerfTracker"

        /**
         * 单例实例
         *
         * 为什么用单例？
         * 性能数据是全局的，LLMEngine、RAGEngine、ASREngine 等都会往里面写数据
         * 用单例保证所有引擎共享同一个 tracker
         *
         * lazy {}：Kotlin 的属性委托
         * 第一次访问时才创建实例，之后都返回同一个实例
         * 默认是线程安全的（LazyThreadSafetyMode.SYNCHRONIZED）
         */
        val instance: PerformanceTracker by lazy { PerformanceTracker() }
    }
}

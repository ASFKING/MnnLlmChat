package com.poc.ondevice.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log

/**
 * MemoryMonitor：内存监控工具
 *
 * 职责：
 * - 采集 App 的全部内存段信息（Java 堆、Native 堆、总 PSS）
 * - 提供格式化的内存报告，供 HomeFragment 显示
 * - 提供内存预警判断，帮助 ModelManager 决定是否释放大模型
 *
 * 为什么需要这个？
 * 只看 JVM 堆内存（Runtime.getRuntime()）是不够的，因为：
 * 1. MNN 加载模型用的是 Native 内存（JNI 层 C++ 分配），JVM GC 管不到
 * 2. 如果只看 JVM 内存，会误以为"内存充裕"，但 Native 层已经快 OOM 了
 * 3. 我们需要一个能同时监控 JVM + Native + 总内存的工具
 *
 * 类比：
 * JVM 堆内存 = 你租的那间房的用电量
 * Native 内存 = 公共区域（电梯、走廊）的用电量
 * 总 PSS = 整栋楼分摊给你的总用电量
 * MemoryMonitor 就像一个能看所有电表的总控面板
 */
class MemoryMonitor(private val context: Context) {

    // ==================== 常量定义 ====================

    companion object {
        private const val TAG = "MemoryMonitor"

        // 内存预警阈值（单位：MB）
        // 当可用内存低于这个值时，建议释放大模型
        const val LOW_MEMORY_THRESHOLD_MB = 500

        // 严重内存不足阈值
        const val CRITICAL_MEMORY_THRESHOLD_MB = 200
    }

    // ==================== 内存数据模型 ====================

    /**
     * MemoryInfo：内存信息数据类
     *
     * data class 是 Kotlin 的数据类，自动生成 equals()、hashCode()、toString() 等方法
     * 比普通 class 更适合用来"装数据"
     *
     * 各字段含义：
     * - javaHeapUsedMB：JVM 堆已用内存（Kotlin/Java 对象占用的空间）
     * - javaHeapMaxMB：JVM 堆最大可用内存（超过就 OOM）
     * - nativeHeapUsedMB：Native 堆已用内存（MNN 模型权重、JNI 分配的内存）
     * - nativeHeapAllocatedMB：Native 内存 PSS（按共享比例分摊的物理内存）
     * - totalPssMB：总 PSS（Proportional Set Size，按比例分摊的物理内存）
     * - availableMB：系统可用内存（可供新分配的内存）
     * - isLowMemory：是否处于低内存状态
     */
    data class MemoryInfo(
        val javaHeapUsedMB: Long,
        val javaHeapMaxMB: Long,
        val nativeHeapUsedMB: Long,
        val nativeHeapAllocatedMB: Long,
        val totalPssMB: Long,
        val availableMB: Long,
        val isLowMemory: Boolean
    )

    // ==================== 核心方法 ====================

    /**
     * 获取当前内存信息
     *
     * 这个方法会同时采集 JVM 堆、Native 堆、系统可用内存三个维度的数据
     *
     * Debug.getMemoryInfo()：Android 提供的内存调试 API
     * 返回的 Debug.MemoryInfo 包含：
     * - dalvikPrivateDirty：Dalvik 虚拟机私有脏页（JVM 堆实际占用）
     * - nativePrivateDirty：Native 私有脏页（JNI/C++ 实际占用）
     * - getTotalPss()：总 PSS（按共享比例分摊的物理内存）
     *
     * 为什么用 Debug.MemoryInfo 而不是 Runtime.getRuntime()？
     * Runtime 只能看到 JVM 堆内存，看不到 Native 内存
     * Debug.MemoryInfo 能看到所有内存段
     */
    fun getMemoryInfo(): MemoryInfo {
        // ---- 1. JVM 堆内存 ----
        // Runtime.getRuntime()：获取 Java 运行时实例
        // totalMemory()：JVM 当前已分配的堆内存（会随使用增长）
        // freeMemory()：JVM 当前空闲的堆内存
        // maxMemory()：JVM 堆的最大值（超过就 OOM）
        val runtime = Runtime.getRuntime()
        val javaHeapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val javaHeapMax = runtime.maxMemory() / (1024 * 1024)

        // ---- 2. Debug.MemoryInfo（JVM + Native + PSS）----
        // Debug.getMemoryInfo()：获取当前进程的详细内存信息
        // 这是一个同步调用，耗时很短（< 1ms）
        val debugMemoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemoryInfo)

        // dalvikPrivateDirty：Dalvik/ART 虚拟机的私有脏页大小（单位：KB）
        // "脏页"是指被修改过的内存页，必须保留在 RAM 中
        // "私有"是指只有这个进程在用的页（不与其他进程共享）
        val nativeHeapUsed = debugMemoryInfo.nativePrivateDirty.toLong() / 1024  // KB → MB

        // nativePss：Native 内存的 PSS（按共享比例分摊的物理内存）
        // Debug.MemoryInfo 没有 nativeHeapAllocatedSize 字段
        // 用 nativePss 作为 Native 内存占用的合理近似
        val nativeHeapAllocated = debugMemoryInfo.nativePss.toLong() / 1024

        // getTotalPss()：总 PSS（Proportional Set Size）
        // PSS = 私有内存 + 共享内存按比例分摊
        // 比如一个 10MB 的共享库被 2 个进程共享，每个进程的 PSS 中算 5MB
        // PSS 是衡量进程实际内存占用的最佳指标
        val totalPss = debugMemoryInfo.totalPss.toLong() / 1024  // KB → MB

        // ---- 3. 系统可用内存 ----
        // ActivityManager：Android 系统服务，管理 App 的运行状态
        // getMemoryInfo()：获取系统级内存信息
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val systemMemoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemMemoryInfo)

        // availMem：系统当前可用内存（单位：字节）
        // 这是系统视角的"空闲内存"，包含缓存等可回收的部分
        val availableMB = systemMemoryInfo.availMem / (1024 * 1024)

        // lowMemory：系统是否处于低内存状态
        // 当 availMem < threshold 时，系统会设这个标志为 true
        // App 收到 onTrimMemory() 回调时，这个值通常也是 true
        val isLowMemory = systemMemoryInfo.lowMemory

        return MemoryInfo(
            javaHeapUsedMB = javaHeapUsed,
            javaHeapMaxMB = javaHeapMax,
            nativeHeapUsedMB = nativeHeapUsed,
            nativeHeapAllocatedMB = nativeHeapAllocated,
            totalPssMB = totalPss,
            availableMB = availableMB,
            isLowMemory = isLowMemory
        )
    }

    /**
     * 获取格式化的内存报告（用于 UI 显示）
     *
     * buildString {}：Kotlin 的字符串构建器
     * 比用 + 拼接字符串更高效，也更可读
     * appendLine()：追加一行文字（自动加换行符）
     */
    fun getFormattedReport(): String {
        val info = getMemoryInfo()
        return buildString {
            // appendLine()：追加一行，等价于 append(str + "\n")
            appendLine("📊 内存使用详情")
            appendLine("─────────────────────")
            appendLine("☕ JVM 堆内存")
            appendLine("   已用: ${info.javaHeapUsedMB}MB / ${info.javaHeapMaxMB}MB")
            appendLine("🔧 Native 内存（MNN 模型等）")
            appendLine("   私有: ${info.nativeHeapUsedMB}MB")
            appendLine("   已分配: ${info.nativeHeapAllocatedMB}MB")
            appendLine("📈 总 PSS: ${info.totalPssMB}MB")
            appendLine("💾 系统可用: ${info.availableMB}MB")
            if (info.isLowMemory) {
                appendLine("⚠️ 系统低内存警告！建议释放大模型")
            }
        }
    }

    // ==================== 内存预警判断 ====================

    /**
     * 检查是否应该释放大模型
     *
     * 当系统可用内存低于阈值时，返回 true
     * ModelManager 可以根据这个结果决定是否释放 LLM（~1.5GB）或多模态（~2.5GB）
     *
     * @return true = 建议释放大模型
     */
    fun shouldReleaseLargeModel(): Boolean {
        val info = getMemoryInfo()
        return info.availableMB < LOW_MEMORY_THRESHOLD_MB
    }

    /**
     * 检查是否处于严重内存不足状态
     *
     * 当可用内存极低时，应该释放所有非必要的模型
     * 只保留 bge（150MB）+ SenseVoice（300MB）≈ 450MB 常驻
     */
    fun isCriticalMemory(): Boolean {
        val info = getMemoryInfo()
        return info.availableMB < CRITICAL_MEMORY_THRESHOLD_MB
    }

    /**
     * 获取模型加载前后的内存差值
     *
     * 用法：
     * 1. 调用 snapshot() 获取加载前的内存快照
     * 2. 加载模型
     * 3. 调用 diff(before) 获取内存增量
     *
     * 这样就能精确知道"加载这个模型用了多少内存"
     *
     * @return 当前内存快照（MB）
     */
    fun snapshot(): Long {
        val info = getMemoryInfo()
        return info.totalPssMB
    }

    /**
     * 计算内存增量
     *
     * @param beforeSnapshot 加载前的快照值
     * @return 增量（MB），正数表示增加了
     */
    fun diff(beforeSnapshot: Long): Long {
        return snapshot() - beforeSnapshot
    }

    /**
     * 获取简短的状态摘要（一行文字）
     *
     * 适合在 HomeFragment 的状态栏中显示
     * 例如："内存: 1.2GB / 4GB | Native: 800MB | 可用: 2.1GB"
     */
    fun getBriefStatus(): String {
        val info = getMemoryInfo()
        return "内存: ${info.javaHeapUsedMB}MB(JVM) + ${info.nativeHeapUsedMB}MB(Native) | " +
                "可用: ${info.availableMB}MB" +
                if (info.isLowMemory) " ⚠️" else ""
    }
}

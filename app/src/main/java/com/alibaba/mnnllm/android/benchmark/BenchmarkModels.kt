package com.alibaba.mnnllm.android.benchmark

/**
 * Benchmark 相关数据类（桩实现）
 *
 * 原始 MNN 项目中，这些类用于性能基准测试。
 * PoC 阶段我们用自己的 PerformanceTracker 做性能统计，
 * 这些桩类只为了让 LlmSession 编译通过。
 *
 * 如果后续需要跑 MNN 官方 benchmark，再补充完整实现。
 */

data class CommandParameters(
    val backend: Int = 0,
    val threads: Int = 4,
    val useMmap: Boolean = false,
    val power: Int = 0,
    val precision: Int = 0,
    val memory: Int = 0,
    val dynamicOption: Int = 0,
    val nPrompt: Int = 0,
    val nGenerate: Int = 0,
    val nRepeat: Int = 1,
    val kvCache: String = "false"
)

data class TestInstance(
    val modelId: String = "",
    val modelName: String = ""
)

interface BenchmarkCallback {
    fun onResult(result: BenchmarkResult)
    fun onError(error: String)
}

data class BenchmarkResult(
    val testInstance: TestInstance? = null,
    val success: Boolean = false,
    val prefillTimeMs: Long = 0,
    val decodeTimeMs: Long = 0,
    val prefillSpeed: Float = 0f,
    val decodeSpeed: Float = 0f,
    val peakMemoryMb: Long = 0,
    val errorMessage: String? = null
)

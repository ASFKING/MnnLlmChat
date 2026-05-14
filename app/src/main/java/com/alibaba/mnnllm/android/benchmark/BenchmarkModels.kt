package com.alibaba.mnnllm.android.benchmark

/**
 * Benchmark 相关数据类（桩实现）
 *
 * 一句话：定义了 MNN 性能基准测试的参数和结果数据结构。
 * 生活类比：就像汽车的性能测试报告——测试百公里加速、最高速度、油耗等指标。
 *
 * PoC 阶段我们用自己的 PerformanceTracker 做性能统计，
 * 这些桩类只为了让 LlmSession 编译通过。
 */

/**
 * CommandParameters：benchmark 命令参数
 *
 * 控制 benchmark 的执行方式：
 * - backend：推理后端（0=CPU, 1=OpenCL, 2=Vulkan...）
 * - threads：CPU 线程数
 * - nPrompt/nGenerate/nRepeat：测试的 prompt 长度、生成长度、重复次数
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

/**
 * TestInstance：被测试的模型实例
 */
data class TestInstance(
    val modelId: String = "",
    val modelName: String = ""
)

/**
 * BenchmarkCallback：benchmark 结果回调接口
 */
interface BenchmarkCallback {
    fun onResult(result: BenchmarkResult)
    fun onError(error: String)
}

/**
 * BenchmarkResult：benchmark 测试结果
 *
 * @param prefillSpeed prefill 速度（tokens/s）——越高越好
 * @param decodeSpeed decode 速度（tokens/s）——越高越好
 * @param peakMemoryMb 峰值内存（MB）——越低越好
 */
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

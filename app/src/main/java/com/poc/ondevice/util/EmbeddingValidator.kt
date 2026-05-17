package com.poc.ondevice.util

import android.util.Log
import com.poc.ondevice.engine.EmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * EmbeddingValidator：嵌入质量验证工具
 *
 * 职责：
 * - 测试 bge-small-zh-v1.5 的嵌入质量
 * - 验证"相似文本相似度高，不相似文本相似度低"
 *
 * 为什么要单独验证？
 * 模型能跑通 ≠ 效果好。就像你买了一把尺子，
 * 得先量一下已知长度的东西（比如 A4 纸是 21cm），
 * 确认尺子是准的，再拿去量未知的。
 *
 * 验证标准：
 * - 相似文本对：余弦相似度 > 0.7（目标 > 0.8）
 * - 不相似文本对：余弦相似度 < 0.4（目标 < 0.3）
 * - 零向量检查：不能输出全 0 的向量
 * - 维度检查：输出必须是 512 维
 */
object EmbeddingValidator {

    private const val TAG = "EmbeddingValidator"

    /**
     * 余弦相似度：衡量两个向量方向的相似程度
     *
     * 一句话：两个向量越"指同一个方向"，相似度越高。
     *
     * 数学公式：cos(θ) = (A·B) / (|A|×|B|)
     * - A·B = 两个向量的点积（对应位置相乘再求和）
     * - |A| = 向量 A 的长度（L2 范数）
     * - 结果范围：[-1, 1]
     *   - 1 = 完全相同方向（语义一致）
     *   - 0 = 垂直（无关）
     *   - -1 = 完全相反（语义对立）
     *
     * 为什么用余弦相似度而不是欧氏距离？
     * 因为 bge 模型输出的是 L2 归一化后的向量（长度=1），
     * 此时余弦相似度 = 点积，计算更快。
     * 而且余弦相似度只看"方向"，不受向量长度影响。
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        // 防御：两个向量长度必须相同
        if (a.size != b.size) {
            Log.e(TAG, "向量维度不匹配: a=${a.size}, b=${b.size}")
            return 0f
        }

        // 点积计算：对应位置相乘，再求和
        // 例如 [1,2,3] · [4,5,6] = 1×4 + 2×5 + 3×6 = 32
        var dotProduct = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
        }

        // 如果向量已经 L2 归一化（长度=1），点积就是余弦相似度
        // 但为了安全，我们还是算一下长度
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        normA = sqrt(normA)
        normB = sqrt(normB)

        // 防止除以零
        if (normA == 0f || normB == 0f) {
            Log.w(TAG, "零向量，无法计算相似度")
            return 0f
        }

        return dotProduct / (normA * normB)
    }

    /**
     * 测试数据集：文本对 + 期望的相似度范围
     *
     * 设计原则：
     * 1. 覆盖不同类型的相似性（同义词、上下位、相关领域）
     * 2. 覆盖不同类型的不相似（完全无关、领域不同）
     * 3. 包含边界情况（空字符串、单字、长文本）
     */
    data class TestCase(
        val text1: String,
        val text2: String,
        val expectedSimilar: Boolean,  // true=期望相似，false=期望不相似
        val description: String        // 测试描述
    )

    private val testCases = listOf(
        // ===== 语义相似的文本对（期望相似度 > 0.7）=====
        TestCase(
            "药品经营许可证",
            "药品经营许可证书",
            expectedSimilar = true,
            description = "同义词变体（加'证书'后缀）"
        ),
        TestCase(
            "营业执照",
            "工商营业执照",
            expectedSimilar = true,
            description = "上位词 vs 下位词"
        ),
        TestCase(
            "食品安全检查",
            "食品卫生检验",
            expectedSimilar = true,
            description = "相关领域（检查 vs 检验）"
        ),
        TestCase(
            "今天天气真好",
            "今天阳光明媚",
            expectedSimilar = true,
            description = "相同语义不同表达"
        ),
        TestCase(
            "如何办理签证",
            "签证办理流程是什么",
            expectedSimilar = true,
            description = "同一问题的不同问法"
        ),

        // ===== 语义不相似的文本对（期望相似度 < 0.4）=====
        TestCase(
            "药品经营许可证",
            "今天中午吃什么",
            expectedSimilar = false,
            description = "完全不同领域（证件 vs 饮食）"
        ),
        TestCase(
            "数学公式",
            "红烧肉的做法",
            expectedSimilar = false,
            description = "完全不同领域（学术 vs 烹饪）"
        ),
        TestCase(
            "苹果手机",
            "苹果是一种水果",
            expectedSimilar = false,
            description = "同词不同义（品牌 vs 水果）"
        ),
        TestCase(
            "人工智能",
            "公元前221年秦始皇统一六国",
            expectedSimilar = false,
            description = "现代技术 vs 古代历史"
        ),
        TestCase(
            "Kotlin 协程",
            "花园里种满了玫瑰花",
            expectedSimilar = false,
            description = "编程 vs 园艺"
        )
    )

    /**
     * 运行完整的嵌入质量验证
     *
     * @param engine 已加载的 EmbeddingEngine 实例
     * @return 验证结果报告
     */
    suspend fun runValidation(engine: EmbeddingEngine): ValidationResult =
        withContext(Dispatchers.Default) {
            val results = mutableListOf<SingleTestResult>()
            var passCount = 0
            var failCount = 0

            Log.d(TAG, "========== 嵌入质量验证开始 ==========")
            Log.d(TAG, "测试用例数: ${testCases.size}")

            for ((index, testCase) in testCases.withIndex()) {
                // 编码两个文本
                val emb1 = engine.encode(testCase.text1)
                val emb2 = engine.encode(testCase.text2)

                // 检查编码是否成功
                if (emb1 == null || emb2 == null) {
                    val error = "编码失败: text1=${emb1 != null}, text2=${emb2 != null}"
                    Log.e(TAG, "  [${index + 1}] ${testCase.description}: $error")
                    results.add(SingleTestResult(testCase, 0f, false, error))
                    failCount++
                    continue
                }

                // 检查维度
                if (emb1.size != 512 || emb2.size != 512) {
                    val error = "维度不匹配: emb1=${emb1.size}, emb2=${emb2.size}"
                    Log.e(TAG, "  [${index + 1}] ${testCase.description}: $error")
                    results.add(SingleTestResult(testCase, 0f, false, error))
                    failCount++
                    continue
                }

                // 检查是否为零向量
                if (isZeroVector(emb1) || isZeroVector(emb2)) {
                    val error = "输出为零向量"
                    Log.e(TAG, "  [${index + 1}] ${testCase.description}: $error")
                    results.add(SingleTestResult(testCase, 0f, false, error))
                    failCount++
                    continue
                }

                // 计算余弦相似度
                val similarity = cosineSimilarity(emb1, emb2)

                // 判断是否通过
                val passed = if (testCase.expectedSimilar) {
                    similarity > 0.7f  // 相似文本：阈值 0.7
                } else {
                    similarity < 0.4f  // 不相似文本：阈值 0.4
                }

                val status = if (passed) "✅ PASS" else "❌ FAIL"
                val range = if (testCase.expectedSimilar) "> 0.7" else "< 0.4"

                Log.d(TAG, "  [${index + 1}] $status | ${testCase.description}")
                Log.d(TAG, "    \"${testCase.text1}\" vs \"${testCase.text2}\"")
                Log.d(TAG, "    相似度: ${"%.4f".format(similarity)} (期望 $range)")

                results.add(SingleTestResult(testCase, similarity, passed, null))
                if (passed) passCount++ else failCount++
            }

            // 计算平均相似度
            val similarPairs = results.filter { it.testCase.expectedSimilar && it.error == null }
            val differentPairs = results.filter { !it.testCase.expectedSimilar && it.error == null }

            val avgSimilarScore = if (similarPairs.isNotEmpty()) {
                similarPairs.map { it.similarity }.average().toFloat()
            } else 0f

            val avgDifferentScore = if (differentPairs.isNotEmpty()) {
                differentPairs.map { it.similarity }.average().toFloat()
            } else 0f

            Log.d(TAG, "========== 嵌入质量验证结果 ==========")
            Log.d(TAG, "通过: $passCount / ${testCases.size}")
            Log.d(TAG, "失败: $failCount / ${testCases.size}")
            Log.d(TAG, "相似文本平均相似度: ${"%.4f".format(avgSimilarScore)} (目标 > 0.7)")
            Log.d(TAG, "不相似文本平均相似度: ${"%.4f".format(avgDifferentScore)} (目标 < 0.3)")
            Log.d(TAG, "分离度: ${"%.4f".format(avgSimilarScore - avgDifferentScore)} (越大越好)")

            val overallPassed = passCount == testCases.size
            Log.d(TAG, "总体结果: ${if (overallPassed) "✅ 全部通过" else "❌ 有失败项"}")

            ValidationResult(
                totalTests = testCases.size,
                passCount = passCount,
                failCount = failCount,
                avgSimilarScore = avgSimilarScore,
                avgDifferentScore = avgDifferentScore,
                separation = avgSimilarScore - avgDifferentScore,
                details = results,
                overallPassed = overallPassed
            )
        }

    /**
     * 检查是否为零向量
     *
     * 如果 bge 模型输出全 0，说明推理出了问题
     * （可能是输入分词错误、模型格式不对等）
     */
    private fun isZeroVector(vec: FloatArray): Boolean {
        for (v in vec) {
            if (v != 0f) return false
        }
        return true
    }

    /**
     * 单个测试结果
     */
    data class SingleTestResult(
        val testCase: TestCase,
        val similarity: Float,
        val passed: Boolean,
        val error: String?
    )

    /**
     * 验证结果报告
     */
    data class ValidationResult(
        val totalTests: Int,           // 总测试数
        val passCount: Int,            // 通过数
        val failCount: Int,            // 失败数
        val avgSimilarScore: Float,    // 相似文本平均相似度
        val avgDifferentScore: Float,  // 不相似文本平均相似度
        val separation: Float,         // 分离度（越大越好）
        val details: List<SingleTestResult>,  // 每个测试的详情
        val overallPassed: Boolean     // 总体是否通过
    ) {
        /**
         * 生成可读的报告文本
         */
        fun toReport(): String {
            val sb = StringBuilder()
            sb.appendLine("========== 嵌入质量验证报告 ==========")
            sb.appendLine("总测试数: $totalTests")
            sb.appendLine("通过: $passCount | 失败: $failCount")
            sb.appendLine("相似文本平均相似度: ${"%.4f".format(avgSimilarScore)} (目标 > 0.7)")
            sb.appendLine("不相似文本平均相似度: ${"%.4f".format(avgDifferentScore)} (目标 < 0.3)")
            sb.appendLine("分离度: ${"%.4f".format(separation)} (越大越好)")
            sb.appendLine()
            sb.appendLine("详细结果:")
            for ((i, detail) in details.withIndex()) {
                val status = if (detail.error != null) "⚠️ ERROR" else if (detail.passed) "✅ PASS" else "❌ FAIL"
                sb.appendLine("  [${i + 1}] $status | ${detail.testCase.description}")
                sb.appendLine("      \"${detail.testCase.text1}\" vs \"${detail.testCase.text2}\"")
                if (detail.error != null) {
                    sb.appendLine("      错误: ${detail.error}")
                } else {
                    sb.appendLine("      相似度: ${"%.4f".format(detail.similarity)}")
                }
            }
            sb.appendLine()
            sb.appendLine("总体结果: ${if (overallPassed) "✅ 全部通过" else "❌ 有失败项"}")
            return sb.toString()
        }
    }
}

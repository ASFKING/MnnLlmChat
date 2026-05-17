package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.poc.ondevice.databinding.FragmentHomeBinding
import com.poc.ondevice.download.ModelDownloader
import com.poc.ondevice.download.ModelEntry
import com.poc.ondevice.download.ModelRegistry
import com.poc.ondevice.engine.EmbeddingEngine
import com.poc.ondevice.util.MemoryMonitor
import com.poc.ondevice.util.PerformanceTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HomeFragment：系统状态页 + 模型下载
 *
 * 生命周期说明（Fragment 的关键回调）：
 * 1. onCreateView → 创建 View（加载 XML 布局）
 * 2. onViewCreated → View 创建完毕，初始化 UI
 * 3. onDestroyView → View 销毁，清理 binding
 *
 * lifecycleScope：绑定 Fragment 生命周期的协程作用域
 * - Fragment 销毁时自动取消所有协程，防止内存泄漏
 * - 类比：自动关闭的水龙头——人走了水自动关
 */
class HomeFragment : Fragment() {

    // _binding 和 binding 的双变量模式
    // _binding 是可空的（Fragment 销毁后设为 null，避免内存泄漏）
    // binding 是非空的（通过 get() 访问时断言非空，方便使用）
    // 这是 Google 官方推荐的 Fragment ViewBinding 写法
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // 模型下载器（懒加载：第一次访问时才创建）
    // lazy {} 是 Kotlin 的属性委托，线程安全，只初始化一次
    // requireContext()：获取 Fragment 关联的 Context，如果 Fragment 未附加到 Activity 则抛异常
    private val modelDownloader by lazy { ModelDownloader(requireContext()) }

    // 内存监控器（懒加载）
    // MemoryMonitor 需要 Context 来获取 ActivityManager 系统服务
    private val memoryMonitor by lazy { MemoryMonitor(requireContext()) }

    // 性能追踪器（全局单例）
    // 所有引擎（LLM、RAG、ASR 等）共享同一个 tracker
    private val perfTracker by lazy { PerformanceTracker.instance }

    // 嵌入引擎（懒加载）
    private val embeddingEngine by lazy { EmbeddingEngine() }

    /**
     * onCreateView：创建 Fragment 的 View
     *
     * @param inflater 布局填充器，把 XML 转成 View
     * @param container 父容器（ViewGroup），Fragment 的 View 会被添加到这个容器中
     * @param savedInstanceState 保存的状态数据（旋转屏幕等重建时有值）
     * @return Fragment 的根 View
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // inflate(inflater, container, false)
        // inflater：布局填充器
        // container：父容器（用于计算 LayoutParams）
        // false：不立即添加到 container（FragmentTransaction 会处理）
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root  // .root 获取 Binding 的根 View
    }

    /**
     * onViewCreated：View 创建完毕，可以初始化 UI 组件
     *
     * 这个回调在 onCreateView 之后调用，此时 View 已经创建好，
     * 可以安全地访问 binding 中的各个 View 组件
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateMemoryInfo()           // 显示内存使用信息
        updatePerfInfo()             // 显示性能数据
        setupModelDownloadButtons()  // 设置模型下载按钮
        setupEmbeddingTest()         // 设置嵌入测试按钮
        startPeriodicRefresh()       // 启动定时刷新（每 3 秒更新内存和性能数据）
    }

    /**
     * 启动定时刷新
     *
     * 为什么要定时刷新？
     * 内存使用是动态变化的——模型加载后内存会飙升，释放后会下降
     * 如果只在 onViewCreated 时刷新一次，用户看到的永远是初始数据
     *
     * lifecycleScope.launch：启动一个与 Fragment 生命周期绑定的协程
     * delay(3000)：挂起 3 秒（不阻塞线程，只是暂停当前协程）
     * while (true)：无限循环，直到 Fragment 销毁（lifecycleScope 自动取消）
     */
    private fun startPeriodicRefresh() {
        // viewLifecycleOwner：Fragment 的 View 生命周期所有者
        // 当 View 销毁时（onDestroyView），lifecycleScope 会自动取消所有协程
        viewLifecycleOwner.lifecycleScope.launch {
            // isAdded：Fragment 是否已附加到 Activity
            // 在循环中检查是因为协程可能在 delay 期间被取消，
            // 但取消前可能还会执行一小段代码
            while (isAdded) {
                delay(3000)  // 挂起 3 秒，不阻塞线程
                updateMemoryInfo()
                updatePerfInfo()
            }
        }
    }

    /**
     * 设置模型下载按钮
     *
     * 遍历 ModelRegistry 中的模型列表，为每个模型创建一个下载按钮
     * 动态添加到 layout_model_buttons 容器中
     */
    private fun setupModelDownloadButtons() {
        val container = binding.layoutModelButtons

        // for-in 循环：遍历列表中的每个元素
        // ModelRegistry.defaultModels 是我们定义的模型列表
        for (model in ModelRegistry.defaultModels) {
            // Button(requireContext())：创建一个按钮
            // .apply {} 是 Kotlin 的作用域函数，在对象上执行 lambda
            // 在 lambda 内部，this 指向 Button 实例，可以调用其方法
            val button = Button(requireContext()).apply {
                // 根据下载状态显示不同文字
                text = buildButtonText(model)
                textSize = 14f  // f 后缀表示 Float 类型

                // 设置点击事件
                // setOnClickListener：设置按钮点击回调
                // 当用户点击按钮时，lambda 中的代码被触发
                setOnClickListener {
                    onModelButtonClick(model)
                }
            }
            // addView(button)：把按钮添加到 LinearLayout 容器中
            // 按钮会出现在容器的末尾
            container.addView(button)
        }
    }

    /**
     * 构建按钮文字（显示模型名 + 大小 + 下载状态）
     *
     * buildString {}：Kotlin 的字符串构建器
     * 比直接用 + 拼接字符串更高效（避免创建大量临时 String 对象）
     */
    private fun buildButtonText(model: ModelEntry): String {
        // 三元表达式的 Kotlin 写法：if ... else ... 可以作为表达式返回值
        val status = if (modelDownloader.isModelDownloaded(model.modelDirName)) {
            " ✅ 已下载"
        } else {
            ""
        }
        // 字符串模板：${变量名} 会被替换成变量的值
        return "下载 ${model.displayName} (${model.sizeGB}GB)$status"
    }

    /**
     * 模型按钮点击处理
     *
     * 如果已下载 → 提示已下载
     * 如果未下载 → 开始下载
     */
    private fun onModelButtonClick(model: ModelEntry) {
        // 如果已下载，显示 Toast 提示并返回
        // Toast.makeText()：创建一个短暂的消息提示
        // Toast.LENGTH_SHORT：显示约 2 秒
        if (modelDownloader.isModelDownloaded(model.modelDirName)) {
            Toast.makeText(requireContext(), "${model.displayName} 已下载", Toast.LENGTH_SHORT).show()
            return  // 提前返回，不执行后续下载逻辑
        }

        // 启动协程执行下载
        // lifecycleScope.launch {}：在生命周期感知的协程中执行
        // 这里不指定 Dispatcher，默认在主线程，但 downloadModel 内部会切换到 IO 线程
        lifecycleScope.launch {
            // 显示进度条
            // View.VISIBLE：View 可见
            binding.progressDownload.visibility = View.VISIBLE
            binding.progressDownload.progress = 0
            binding.tvDownloadStatus.text = "正在下载 ${model.displayName}..."

            // 禁用所有下载按钮（防止重复下载）
            setButtonsEnabled(false)

            // 调用下载器执行下载
            modelDownloader.downloadModel(
                model = model,
                // onProgress 回调：下载过程中不断调用，更新进度
                onProgress = { downloaded, total, currentFile ->
                    // 计算下载百分比
                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    // 字节 → MB 的换算
                    val downloadedMB = downloaded / 1024 / 1024
                    val totalMB = total / 1024 / 1024

                    // runOnUiThread：在主线程更新 UI
                    // 因为 onProgress 回调可能来自 IO 线程
                    // Android 规定只有主线程才能修改 UI 组件
                    activity?.runOnUiThread {
                        binding.progressDownload.progress = percent
                        binding.tvDownloadStatus.text =
                            "下载中: ${downloadedMB}MB / ${totalMB}MB ($percent%)\n$currentFile"
                    }
                },
                // onComplete 回调：下载完成
                onComplete = { modelPath ->
                    activity?.runOnUiThread {
                        binding.progressDownload.visibility = View.GONE  // 隐藏进度条
                        binding.tvDownloadStatus.text =
                            "✅ ${model.displayName} 下载完成\n路径: ${modelPath.absolutePath}"
                        refreshButtons()  // 刷新按钮状态
                        Toast.makeText(
                            requireContext(),
                            "${model.displayName} 下载完成！",
                            Toast.LENGTH_LONG  // 长时间显示
                        ).show()
                    }
                },
                // onError 回调：下载失败
                onError = { error ->
                    activity?.runOnUiThread {
                        binding.progressDownload.visibility = View.GONE
                        binding.tvDownloadStatus.text = "❌ 下载失败: $error"
                        refreshButtons()
                        Toast.makeText(requireContext(), "下载失败: $error", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    /**
     * 刷新所有按钮状态
     */
    private fun refreshButtons() {
        val container = binding.layoutModelButtons
        // childCount：子 View 数量
        // getChildAt(index)：获取第 index 个子 View
        for (i in 0 until container.childCount) {
            // as? Button：安全类型转换，如果不是 Button 返回 null
            val button = container.getChildAt(i) as? Button
            // getOrNull(i)：安全索引访问，越界时返回 null（比 list[i] 更安全）
            val model = ModelRegistry.defaultModels.getOrNull(i)
            if (button != null && model != null) {
                button.text = buildButtonText(model)
            }
        }
        setButtonsEnabled(true)
    }

    /**
     * 启用/禁用所有下载按钮
     */
    private fun setButtonsEnabled(enabled: Boolean) {
        val container = binding.layoutModelButtons
        for (i in 0 until container.childCount) {
            // (view as? Button)?.isEnabled = enabled
            // 安全类型转换 + 安全调用，如果不是 Button 就跳过
            (container.getChildAt(i) as? Button)?.isEnabled = enabled
        }
    }

    /**
     * 更新内存信息显示
     *
     * 现在使用 MemoryMonitor 工具类，能同时显示 JVM 堆、Native 堆、系统可用内存
     * 比之前的 Runtime.getRuntime() 方案更全面
     *
     * MemoryMonitor 通过 Debug.getMemoryInfo() 获取全部内存段信息
     * 包括 MNN 模型加载的 Native 内存（之前看不到的那部分）
     */
    private fun updateMemoryInfo() {
        // getFormattedReport()：返回格式化的多行内存报告
        // 包含 JVM 堆、Native 堆、总 PSS、系统可用内存等信息
        binding.tvMemoryInfo.text = memoryMonitor.getFormattedReport()
    }

    /**
     * 更新性能数据
     *
     * 从 PerformanceTracker 获取所有已记录的性能指标
     * 包括各操作耗时、TTFT、tok/s 等
     */
    private fun updatePerfInfo() {
        // getBriefSummary()：返回一行简短的性能摘要
        // 例如："TTFT: 1.2s | 18.5 tok/s | RAG: 5.7s"
        // 如果没有数据，返回 "暂无性能数据"
        binding.tvPerfInfo.text = perfTracker.getBriefSummary()

        // generateReport()：返回详细的性能报告（多行）
        // 只有当有数据时才显示详细报告
        val fullReport = perfTracker.generateReport()
        if (fullReport.contains("暂无数据").not()) {
            binding.tvPerfInfo.text = fullReport
        }
    }

    /**
     * 设置嵌入测试按钮
     */
    private fun setupEmbeddingTest() {
        binding.btnTestEmbedding.setOnClickListener {
            runEmbeddingTest()
        }
    }

    /**
     * 运行嵌入质量测试
     *
     * 测试逻辑：
     * 1. 加载 bge-small-zh-v1.5 模型
     * 2. 对几组文本对计算嵌入向量
     * 3. 计算余弦相似度（已 L2 归一化，所以用点积）
     * 4. 验证：相似文本 > 0.8，不相似文本 < 0.3
     *
     * 防御性设计：每一步都有详细错误输出，不会闪退
     */
    private fun runEmbeddingTest() {
        val resultView = binding.tvEmbeddingTestResult
        resultView.visibility = View.VISIBLE
        resultView.text = "⏳ 正在加载嵌入模型..."

        lifecycleScope.launch {
            try {
                // ===== Step 1: 检查模型目录 =====
                val modelDir = ModelDownloader(requireContext())
                    .getModelPath("bge-small-zh-v1.5-onnx")

                val sb = StringBuilder()

                if (!modelDir.exists()) {
                    resultView.text = "❌ 模型目录不存在！\n路径: ${modelDir.absolutePath}\n请先下载 bge-small-zh 嵌入模型"
                    return@launch
                }

                // 列出模型目录内容（调试用）
                sb.appendLine("📂 模型目录: ${modelDir.name}")
                modelDir.listFiles()?.forEach { file ->
                    sb.appendLine("  ${file.name} (${file.length()} bytes)")
                }
                sb.appendLine()
                resultView.text = sb.toString()

                // ===== Step 2: 加载模型 =====
                sb.appendLine("⏳ 正在加载模型和分词器...")
                resultView.text = sb.toString()

                val loaded = embeddingEngine.load(modelDir.absolutePath)
                if (!loaded) {
                    sb.appendLine("❌ 模型加载失败！")
                    sb.appendLine("  输入名称: ${embeddingEngine.inputNames}")
                    sb.appendLine("  输出名称: ${embeddingEngine.outputNames}")
                    sb.appendLine("  请查看 Logcat 搜索 'EmbeddingEngine' 了解详情")
                    resultView.text = sb.toString()
                    return@launch
                }

                sb.appendLine("✅ 模型加载成功！")
                sb.appendLine("  输入: ${embeddingEngine.inputNames}")
                sb.appendLine("  输出: ${embeddingEngine.outputNames}")
                sb.appendLine()
                resultView.text = sb.toString()

                // ===== Step 3: 测试分词 =====
                sb.appendLine("⏳ 测试分词...")
                resultView.text = sb.toString()

                val testText = "药品经营许可证"
                val tokens = withContext(Dispatchers.Default) {
                    // 直接调用 encode 来测试整个流程
                    embeddingEngine.encode(testText)
                }

                if (tokens == null) {
                    sb.appendLine("❌ encode() 返回 null！")
                    sb.appendLine("  错误: ${embeddingEngine.lastError}")
                    sb.appendLine("  输出形状: ${embeddingEngine.outputShape}")
                    resultView.text = sb.toString()
                    return@launch
                }

                sb.appendLine("✅ encode() 成功！")
                sb.appendLine("  输入: \"$testText\"")
                sb.appendLine("  输出维度: ${tokens.size}")
                sb.appendLine("  输出形状: ${embeddingEngine.outputShape}")
                sb.appendLine("  前 5 个值: ${tokens.take(5).map { "%.4f".format(it) }}")
                sb.appendLine()
                resultView.text = sb.toString()

                // ===== Step 4: 相似度测试 =====
                val testCases = listOf(
                    Triple("药品经营许可证", "药品经营许可办理流程", "今天天气真不错"),
                    Triple("如何申请营业执照", "营业执照申请指南", "周末去哪里玩"),
                    Triple("医疗机构执业许可", "医院执业许可证办理", "炒菜放多少盐")
                )

                for ((index, testCase) in testCases.withIndex()) {
                    val (textA, textB, textC) = testCase

                    sb.appendLine("【测试 ${index + 1}】")
                    sb.appendLine("  A: \"$textA\"")
                    sb.appendLine("  B: \"$textB\"")
                    sb.appendLine("  C: \"$textC\"")
                    resultView.text = sb.toString()

                    val result = withContext(Dispatchers.Default) {
                        val vecA = embeddingEngine.encode(textA)
                        val vecB = embeddingEngine.encode(textB)
                        val vecC = embeddingEngine.encode(textC)

                        if (vecA == null || vecB == null || vecC == null) {
                            return@withContext null
                        }

                        val simAB = dotProduct(vecA, vecB)
                        val simAC = dotProduct(vecA, vecC)
                        Pair(simAB, simAC)
                    }

                    if (result == null) {
                        sb.appendLine("  ❌ encode 失败: ${embeddingEngine.lastError}")
                        sb.appendLine()
                        resultView.text = sb.toString()
                        continue
                    }

                    val (simAB, simAC) = result
                    val passAB = simAB > 0.8f
                    val passAC = simAC < 0.3f
                    val iconAB = if (passAB) "✅" else "❌"
                    val iconAC = if (passAC) "✅" else "❌"

                    sb.appendLine("  sim(A,B) = ${"%.4f".format(simAB)} $iconAB (目标 > 0.8)")
                    sb.appendLine("  sim(A,C) = ${"%.4f".format(simAC)} $iconAC (目标 < 0.3)")
                    sb.appendLine()
                    resultView.text = sb.toString()
                }

                // ===== Step 5: 性能测试 =====
                sb.appendLine("【性能测试】")
                resultView.text = sb.toString()

                val perfResult = withContext(Dispatchers.Default) {
                    val perfText = "药品经营许可证办理流程"
                    val times = mutableListOf<Long>()

                    // 预热一次
                    embeddingEngine.encode(perfText)

                    // 正式测试 5 次取平均
                    repeat(5) {
                        val start = System.currentTimeMillis()
                        embeddingEngine.encode(perfText)
                        times.add(System.currentTimeMillis() - start)
                    }
                    times
                }

                val avgMs = perfResult.average()
                sb.appendLine("  单条编码耗时: ${"%.1f".format(avgMs)}ms (5 次平均)")
                sb.appendLine("  范围: ${perfResult.min()}ms ~ ${perfResult.max()}ms")
                sb.appendLine()
                sb.appendLine("✅ 测试完成！")
                resultView.text = sb.toString()

                embeddingEngine.release()

            } catch (e: Exception) {
                // 最外层 catch：捕获所有未预期的异常
                val errorMsg = StringBuilder()
                errorMsg.appendLine("❌ 测试异常:")
                errorMsg.appendLine("  类型: ${e.javaClass.simpleName}")
                errorMsg.appendLine("  消息: ${e.message}")
                errorMsg.appendLine()
                errorMsg.appendLine("堆栈:")
                errorMsg.append(e.stackTraceToString().take(500))
                resultView.text = errorMsg.toString()
                embeddingEngine.release()
            }
        }
    }

    /**
     * 点积计算（两个已归一化向量的点积 = 余弦相似度）
     */
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /**
     * onDestroyView：View 销毁时调用
     *
     * 为什么要把 _binding 设为 null？
     * Fragment 的生命周期比 View 长——Fragment 可能还在，但 View 已经销毁了
     * 如果不设为 null，binding 会持有已销毁的 View 引用，导致内存泄漏
     * 类比：搬走后要把旧钥匙扔掉，否则旧房子没法被回收
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

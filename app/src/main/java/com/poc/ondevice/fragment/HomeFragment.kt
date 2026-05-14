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
import kotlinx.coroutines.launch

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
        setupModelDownloadButtons()  // 设置模型下载按钮
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
     * Runtime.getRuntime()：获取 Java 运行时实例
     * - totalMemory()：JVM 当前已分配的内存
     * - freeMemory()：JVM 当前空闲的内存
     * - maxMemory()：JVM 最大可用内存
     *
     * 注意：这里只能看到 JVM 堆内存，Native 内存（MNN 模型加载的内存）需要另外监控
     */
    private fun updateMemoryInfo() {
        val runtime = Runtime.getRuntime()
        // 已用内存 = 已分配 - 空闲
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val totalMB = runtime.totalMemory() / 1024 / 1024
        val maxMB = runtime.maxMemory() / 1024 / 1024

        // buildString {}：构建多行字符串
        // appendLine()：追加一行文字（自动加换行符）
        binding.tvMemoryInfo.text = buildString {
            appendLine("JVM 已用: ${usedMB}MB")
            appendLine("JVM 已分配: ${totalMB}MB")
            appendLine("JVM 最大可用: ${maxMB}MB")
            appendLine("（Native 内存需加载模型后通过 Debug API 获取）")
        }
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

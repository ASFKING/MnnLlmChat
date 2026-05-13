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

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // 模型下载器（懒加载：第一次访问时才创建）
    // lazy {} 是 Kotlin 的属性委托，线程安全，只初始化一次
    private val modelDownloader by lazy { ModelDownloader(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateMemoryInfo()
        setupModelDownloadButtons()
    }

    /**
     * 设置模型下载按钮
     *
     * 遍历 ModelRegistry 中的模型列表，为每个模型创建一个下载按钮
     * 动态添加到 layout_model_buttons 容器中
     */
    private fun setupModelDownloadButtons() {
        val container = binding.layoutModelButtons

        // 遍历模型列表，为每个模型创建按钮
        for (model in ModelRegistry.defaultModels) {
            val button = Button(requireContext()).apply {
                // 根据下载状态显示不同文字
                text = buildButtonText(model)
                textSize = 14f

                // 设置点击事件
                setOnClickListener {
                    onModelButtonClick(model)
                }
            }
            container.addView(button)
        }
    }

    /**
     * 构建按钮文字（显示模型名 + 大小 + 下载状态）
     */
    private fun buildButtonText(model: ModelEntry): String {
        val status = if (modelDownloader.isModelDownloaded(model.modelDirName)) {
            " ✅ 已下载"
        } else {
            ""
        }
        return "下载 ${model.displayName} (${model.sizeGB}GB)$status"
    }

    /**
     * 模型按钮点击处理
     *
     * 如果已下载 → 提示已下载
     * 如果未下载 → 开始下载
     */
    private fun onModelButtonClick(model: ModelEntry) {
        if (modelDownloader.isModelDownloaded(model.modelDirName)) {
            Toast.makeText(requireContext(), "${model.displayName} 已下载", Toast.LENGTH_SHORT).show()
            return
        }

        // 启动协程执行下载
        // lifecycleScope.launch {}：在生命周期感知的协程中执行
        // 这里不指定 Dispatcher，默认在主线程，但 downloadModel 内部会切换到 IO 线程
        lifecycleScope.launch {
            // 显示进度条
            binding.progressDownload.visibility = View.VISIBLE
            binding.progressDownload.progress = 0
            binding.tvDownloadStatus.text = "正在下载 ${model.displayName}..."

            // 禁用所有下载按钮（防止重复下载）
            setButtonsEnabled(false)

            modelDownloader.downloadModel(
                model = model,
                onProgress = { downloaded, total, currentFile ->
                    // 更新进度（百分比）
                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
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
                onComplete = { modelPath ->
                    activity?.runOnUiThread {
                        binding.progressDownload.visibility = View.GONE
                        binding.tvDownloadStatus.text =
                            "✅ ${model.displayName} 下载完成\n路径: ${modelPath.absolutePath}"
                        refreshButtons()
                        Toast.makeText(
                            requireContext(),
                            "${model.displayName} 下载完成！",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
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
        for (i in 0 until container.childCount) {
            val button = container.getChildAt(i) as? Button
            val model = ModelRegistry.defaultModels.getOrNull(i)
            if (button != null && model != null) {
                button.text = buildButtonText(model)
            }
        }
        setButtonsEnabled(true)
    }

    /**
     * 启用/禁用所有下载按钮
     *
     * childCount：子 View 数量
     * getChildAt(index)：获取第 index 个子 View
     * as? Button：安全类型转换，如果不是 Button 返回 null
     */
    private fun setButtonsEnabled(enabled: Boolean) {
        val container = binding.layoutModelButtons
        for (i in 0 until container.childCount) {
            (container.getChildAt(i) as? Button)?.isEnabled = enabled
        }
    }

    private fun updateMemoryInfo() {
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val totalMB = runtime.totalMemory() / 1024 / 1024
        val maxMB = runtime.maxMemory() / 1024 / 1024

        binding.tvMemoryInfo.text = buildString {
            appendLine("JVM 已用: ${usedMB}MB")
            appendLine("JVM 已分配: ${totalMB}MB")
            appendLine("JVM 最大可用: ${maxMB}MB")
            appendLine("（Native 内存需加载模型后通过 Debug API 获取）")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

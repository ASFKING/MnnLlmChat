package com.poc.ondevice.fragment

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.poc.ondevice.App
import com.poc.ondevice.databinding.FragmentVisionBinding
import com.poc.ondevice.download.ModelDownloader
import com.poc.ondevice.download.ModelRegistry
import kotlinx.coroutines.launch
import java.io.File

/**
 * VisionFragment：图片理解页
 *
 * 多模态理解是什么（三层解释法）：
 * 一句话：让 AI 同时"看"图片和"读"文字，理解两者的关系。
 * 生活类比：就像你给朋友看一张照片，然后问"这是哪里？"
 *           ——朋友同时用眼睛看和耳朵听，综合理解后回答。
 * 技术细节：图片经过视觉编码器（Vision Encoder）转成向量，
 *           和文字向量一起送入 LLM，LLM 综合理解后生成回答。
 *
 * 职责：
 * - 图片选择和预览
 * - 图片 + 文本联合理解
 * - 流式输出结果
 *
 * 当前状态：Day 6 实现，接入 VisionEngine
 */
class VisionFragment : Fragment() {

    // ==================== ViewBinding ====================
    // _binding：Fragment 的视图绑定引用，onDestroyView 时置空防内存泄漏
    private var _binding: FragmentVisionBinding? = null
    // binding：非空访问器，Fragment 生命周期内安全使用
    private val binding get() = _binding!!

    // ==================== 引擎 ====================
    // 通过 App 级别获取全局共享的引擎实例（lazy 初始化，只创建一次）
    private val llmEngine get() = (requireActivity().application as App).llmEngine
    private val visionEngine get() = (requireActivity().application as App).visionEngine

    // modelDownloader：模型下载器，用于获取模型本地路径
    private val modelDownloader by lazy { ModelDownloader(requireContext()) }

    // ==================== 状态 ====================

    /** selectedImagePath：当前选中图片的绝对路径 */
    private var selectedImagePath: String? = null

    /** isGenerating：是否正在生成中（防止重复提交） */
    private var isGenerating = false

    /** isModelLoaded：VL 模型是否已加载成功 */
    private var isModelLoaded = false

    // ==================== 图片选择器 ====================

    /**
     * imagePicker：系统图片选择器
     *
     * ActivityResultContracts.GetContent()：Android 推荐的"获取内容"方式
     * 参数 image 表示只选择图片类型
     *
     * registerForActivityResult() 注册一个回调：
     * 用户选完图片后，系统会回调 lambda，传入选中图片的 Uri
     *
     * 为什么用 ActivityResultContracts 而不是 onActivityResult？
     * 因为 onActivityResult 是旧 API，Android 13+ 已废弃。
     * ActivityResultContracts 是 Google 推荐的新方式，更安全、更简洁。
     */
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // uri 可能为 null（用户取消选择），用 ?.let 安全处理
        uri?.let { onImageSelected(it) }
    }

    // ==================== 生命周期 ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // inflate()：从 XML 布局文件创建视图对象
        // FragmentVisionBinding 是 ViewBinding 自动生成的绑定类
        _binding = FragmentVisionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 视图创建完成后：设置按钮监听 + 加载模型
        setupButtons()
        loadModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 置空 binding，避免 Fragment 销毁后仍持有视图引用导致内存泄漏
        _binding = null
    }

    // ==================== UI 初始化 ====================

    private fun setupButtons() {
        // 选择图片按钮：启动系统图片选择器
        binding.btnSelectImage.setOnClickListener {
            // launch() 启动系统图片选择器
            // 用户选完后，imagePicker 的回调会被调用
            imagePicker.launch("image/*")
        }

        // 提问按钮：把图片 + 问题发给 VisionEngine
        binding.btnVisionAsk.setOnClickListener {
            val question = binding.etVisionInput.text.toString().trim()
            if (question.isNotBlank()) {
                askAboutImage(question)
            }
        }
    }

    // ==================== 模型加载 ====================

    /**
     * 加载多模态 VL 模型
     *
     * 关键点：必须加载 Qwen2.5-VL-3B（多模态模型），不能加载 Qwen3（纯文本模型）。
     * 因为只有 VL 模型才能处理 <img> 标签，理解图片内容。
     *
     * 为什么不在 App 启动时就加载 VL 模型？
     * 因为 VL 模型约 2.5GB，内存占用大。按需加载、空闲释放更合理。
     */
    private fun loadModel() {
        binding.tvVisionResult.text = "正在加载多模态模型..."

        lifecycleScope.launch {
            try {
                // 如果 LLM 已经加载了（可能是从其他 Tab 切过来的），先释放
                // 释放旧模型腾出内存给 VL 模型
                if (llmEngine.isLoaded) {
                    Log.d(TAG, "释放已有 LLM 模型，为 VL 模型腾出内存")
                    llmEngine.release()
                }

                // 从 ModelRegistry 中查找 VL 模型（displayName 包含 "VL" 或 "多模态"）
                // find()：遍历列表，返回第一个满足条件的元素，找不到返回 null
                val vlModelEntry = ModelRegistry.defaultModels.find { entry ->
                    // contains()：字符串包含检查
                    entry.displayName.contains("VL", ignoreCase = true) ||
                    entry.displayName.contains("多模态")
                }

                if (vlModelEntry == null) {
                    binding.tvVisionResult.text = "错误：未找到多模态模型，请先下载 Qwen2.5-VL-3B"
                    return@launch
                }

                // getModelPath()：获取模型在本地的存储路径
                val modelDir = modelDownloader.getModelPath(vlModelEntry.modelDirName)

                if (!modelDir.exists()) {
                    binding.tvVisionResult.text = "请先在首页下载 ${vlModelEntry.displayName} 模型"
                    return@launch
                }

                // 加载 VL 模型
                Log.d(TAG, "开始加载 VL 模型: ${modelDir.absolutePath}")
                isModelLoaded = llmEngine.load(modelDir.absolutePath)

                if (isModelLoaded) {
                    binding.tvVisionResult.text = "多模态模型已就绪，请选择图片并提问"
                    Log.d(TAG, "VL 模型加载成功")
                } else {
                    binding.tvVisionResult.text = "模型加载失败，请检查模型文件是否完整"
                    Log.e(TAG, "VL 模型加载失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载模型异常", e)
                binding.tvVisionResult.text = "加载模型出错: ${e.message}"
            }
        }
    }

    // ==================== 图片选择 ====================

    /**
     * 用户选中图片后的处理
     *
     * @param uri 选中图片的 content:// URI
     *
     * 为什么需要保存到私有目录？
     * Android 图片选择器返回 content:// URI（内容提供者地址），
     * MNN 的 C++ 层需要绝对文件路径才能加载图片。
     * 所以需要先复制到 App 的 filesDir。
     */
    private fun onImageSelected(uri: Uri) {
        lifecycleScope.launch {
            try {
                // 1. 显示图片预览
                // setImageURI()：ImageView 的便捷方法，自动解码并显示 URI 对应的图片
                binding.ivPreview.setImageURI(uri)
                // 隐藏占位文字
                binding.tvImagePlaceholder.visibility = View.GONE

                // 2. 把 content:// URI 的图片复制到临时文件
                // contentResolver.openInputStream()：打开 URI 的输入流
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Toast.makeText(requireContext(), "无法读取图片", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 先写入 cacheDir 临时文件
                val tempFile = File(requireContext().cacheDir, "temp_image.jpg")
                // use {}：Kotlin 的扩展函数，自动在块结束后关闭流（类似 Java try-with-resources）
                tempFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()

                // 3. VisionEngine 处理图片（缩放 + 保存到私有目录）
                val savedPath = visionEngine.saveImageToPrivateDir(tempFile, requireContext())
                // 用完删除临时文件
                tempFile.delete()

                if (savedPath != null) {
                    selectedImagePath = savedPath
                    binding.tvVisionResult.text = "图片已选择，请输入问题"
                    Log.d(TAG, "图片已保存: $savedPath")
                } else {
                    Toast.makeText(requireContext(), "图片保存失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理图片失败", e)
                Toast.makeText(requireContext(), "处理图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 图片理解 ====================

    /**
     * 提问：把图片路径和问题一起发给 VisionEngine
     *
     * VisionEngine.understand() 内部构建 <img>path</img>question 格式的 prompt，
     * MNN 的 C++ 层检测到 <img> 标签后自动走多模态推理路径。
     *
     * @param question 用户的问题（如"这是什么？"）
     */
    private fun askAboutImage(question: String) {
        // 防重复提交
        if (isGenerating) {
            Toast.makeText(requireContext(), "请等待当前回答完成", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查图片是否已选择
        val imagePath = selectedImagePath
        if (imagePath == null) {
            Toast.makeText(requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查模型是否已加载
        if (!isModelLoaded) {
            Toast.makeText(requireContext(), "模型尚未加载", Toast.LENGTH_SHORT).show()
            return
        }

        // 更新 UI 状态：进入生成中
        isGenerating = true
        binding.btnVisionAsk.isEnabled = false
        binding.btnVisionAsk.text = "生成中..."
        binding.tvVisionResult.text = ""

        lifecycleScope.launch {
            try {
                // VisionEngine.understand()：构建多模态 prompt 并流式生成
                // collect {}：Flow 的终端操作，逐个接收并处理每个 token
                visionEngine.understand(imagePath, question).collect { token ->
                    // 流式追加 token 到结果显示区（实时显示，体验更好）
                    binding.tvVisionResult.append(token)
                }
            } catch (e: Exception) {
                Log.e(TAG, "图片理解失败", e)
                binding.tvVisionResult.text = "生成出错: ${e.message}"
            } finally {
                // finally：无论成功还是失败都会执行，恢复 UI 状态
                isGenerating = false
                binding.btnVisionAsk.isEnabled = true
                binding.btnVisionAsk.text = "提问"
            }
        }
    }

    companion object {
        private const val TAG = "VisionFragment"
    }
}

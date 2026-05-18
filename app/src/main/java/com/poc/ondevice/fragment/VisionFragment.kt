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
import com.poc.ondevice.engine.LLMEngine
import com.poc.ondevice.engine.VisionEngine
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

    private var _binding: FragmentVisionBinding? = null
    private val binding get() = _binding!!

    // ==================== 引擎 ====================

    private val llmEngine: LLMEngine
        get() = (requireActivity().application as App).llmEngine
    private val visionEngine: VisionEngine
        get() = (requireActivity().application as App).visionEngine

    private val modelDownloader by lazy { ModelDownloader(requireContext()) }

    // ==================== 状态 ====================

    /** selectedImagePath：当前选中图片的绝对路径 */
    private var selectedImagePath: String? = null

    /** isGenerating：是否正在生成中 */
    private var isGenerating = false

    /** isModelLoaded：VL 模型是否已加载 */
    private var isModelLoaded = false

    // ==================== 图片选择器 ====================

    /**
     * imagePicker：系统图片选择器
     *
     * ActivityResultContracts.GetContent()：Android 推荐的"获取内容"方式
     * "image/*"：只选择图片类型
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
        uri?.let { onImageSelected(it) }
    }

    // ==================== 生命周期 ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVisionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupButtons()
        loadModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== UI 初始化 ====================

    private fun setupButtons() {
        // 选择图片按钮
        binding.btnSelectImage.setOnClickListener {
            // launch() 启动系统图片选择器
            // 用户选完后，imagePicker 的回调会被调用
            imagePicker.launch("image/*")
        }

        // 提问按钮
        binding.btnVisionAsk.setOnClickListener {
            val question = binding.etVisionInput.text.toString().trim()
            if (question.isNotBlank()) {
                askAboutImage(question)
            }
        }
    }

    // ==================== 模型加载 ====================

    /**
     * 加载 LLM 模型（多模态复用 LLMEngine）
     *
     * 注意：PoC 阶段，多模态和文本推理共用同一个 LLMEngine。
     * 如果要同时支持文本推理和图片理解，需要加载 Qwen2.5-VL 模型。
     * 目前先用 Qwen3-1.7B 验证流程，后续可以切换到 VL 模型。
     */
    private fun loadModel() {
        binding.tvVisionResult.text = "正在加载模型..."

        lifecycleScope.launch {
            // 检查 LLM 是否已加载（可能在 ChatFragment 已经加载了）
            if (llmEngine.isLoaded) {
                isModelLoaded = true
                binding.tvVisionResult.text = "模型已就绪，请选择图片并提问"
                return@launch
            }

            // 如果还没加载，尝试加载
            val modelEntry = ModelRegistry.defaultModels.first()
            val modelDir = modelDownloader.getModelPath(modelEntry.modelDirName)
            if (modelDir.exists()) {
                isModelLoaded = llmEngine.load(modelDir.absolutePath)
                if (isModelLoaded) {
                    binding.tvVisionResult.text = "模型已就绪，请选择图片并提问"
                } else {
                    binding.tvVisionResult.text = "模型加载失败"
                }
            } else {
                binding.tvVisionResult.text = "请先在首页下载模型"
            }
        }
    }

    // ==================== 图片选择 ====================

    /**
     * 用户选中图片后的处理
     *
     * @param uri 选中图片的 content:// URI
     */
    private fun onImageSelected(uri: Uri) {
        // 在 IO 线程中读取和保存图片
        lifecycleScope.launch {
            try {
                // 1. 显示预览
                binding.ivPreview.setImageURI(uri)
                binding.tvImagePlaceholder.visibility = View.GONE

                // 2. 把图片复制到 App 私有目录（获取绝对路径）
                // contentResolver.openInputStream()：打开 content:// URI 的输入流
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Toast.makeText(requireContext(), "无法读取图片", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 先保存到临时文件
                val tempFile = File(requireContext().cacheDir, "temp_image.jpg")
                tempFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()

                // 再从临时文件复制到私有目录（VisionEngine 会处理缩放）
                val savedPath = visionEngine.saveImageToPrivateDir(tempFile, requireContext())
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
     * @param question 用户的问题
     */
    private fun askAboutImage(question: String) {
        if (isGenerating) {
            Toast.makeText(requireContext(), "请等待当前回答完成", Toast.LENGTH_SHORT).show()
            return
        }

        val imagePath = selectedImagePath
        if (imagePath == null) {
            Toast.makeText(requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isModelLoaded) {
            Toast.makeText(requireContext(), "模型尚未加载", Toast.LENGTH_SHORT).show()
            return
        }

        isGenerating = true
        binding.btnVisionAsk.isEnabled = false
        binding.btnVisionAsk.text = "生成中..."
        binding.tvVisionResult.text = ""

        lifecycleScope.launch {
            try {
                // VisionEngine.understand() 内部构建 <img>path</img>question 格式的 prompt
                // 然后调用 LLMEngine.generateStream()，C++ 层自动处理图片
                visionEngine.understand(imagePath, question).collect { token ->
                    // 流式追加 token 到结果显示区
                    binding.tvVisionResult.append(token)
                }
            } catch (e: Exception) {
                Log.e(TAG, "图片理解失败", e)
                binding.tvVisionResult.text = "生成出错: ${e.message}"
            } finally {
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

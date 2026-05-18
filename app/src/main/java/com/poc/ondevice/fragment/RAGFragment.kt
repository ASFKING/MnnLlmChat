package com.poc.ondevice.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.poc.ondevice.App
import com.poc.ondevice.data.DocumentData
import com.poc.ondevice.data.DocumentStore
import com.poc.ondevice.databinding.FragmentRagBinding
import com.poc.ondevice.download.ModelDownloader
import com.poc.ondevice.download.ModelRegistry
import com.poc.ondevice.engine.EmbeddingEngine
import com.poc.ondevice.engine.LLMEngine
import com.poc.ondevice.engine.RAGEngine
import com.poc.ondevice.engine.VectorStore
import kotlinx.coroutines.launch

/**
 * RAGFragment：RAG 问答页
 *
 * RAG 是什么（三层解释法）：
 * 一句话：检索增强生成——先从文档库找到相关内容，再用 LLM 基于这些内容回答问题。
 * 生活类比：就像开卷考试——先翻书找到相关段落，再用自己的话回答。
 * 技术细节：用户提问 → EmbeddingEngine 把问题转成向量 → VectorStore 搜索相似文档
 *           → 把检索到的文档拼进 prompt → LLM 基于文档生成回答
 *
 * 职责：
 * - 文档上传和管理（添加/删除文档）
 * - 知识库状态显示（向量库大小、模型状态）
 * - 基于文档的问答对话（流式输出）
 *
 * 当前状态：Day 4 实现，接入完整 RAG 流程
 */
class RAGFragment : Fragment() {

    // ==================== ViewBinding ====================

    private var _binding: FragmentRagBinding? = null
    private val binding get() = _binding!!

    // ==================== 引擎（从 App 获取共享实例）====================

    /**
     * 从 App 获取共享的引擎实例
     *
     * 为什么用 get() 属性而不是 by lazy？
     * 因为这些引擎已经在 App.onCreate() 中 lazy 初始化了，
     * 这里只需要引用，不需要再包装一层 lazy。
     */
    private val llmEngine: LLMEngine
        get() = (requireActivity().application as App).llmEngine
    private val embeddingEngine: EmbeddingEngine
        get() = (requireActivity().application as App).embeddingEngine
    private val vectorStore: VectorStore
        get() = (requireActivity().application as App).vectorStore
    private val ragEngine: RAGEngine
        get() = (requireActivity().application as App).ragEngine

    /** documentStore：文档持久化存储 */
    private val documentStore by lazy { DocumentStore(requireContext()) }

    /** modelDownloader：获取模型路径 */
    private val modelDownloader by lazy { ModelDownloader(requireContext()) }

    // ==================== 对话状态 ====================

    /** messages：RAG 对话消息列表 */
    private val messages = mutableListOf<ChatMessage>()

    /** adapter：消息列表适配器 */
    private lateinit var adapter: ChatMessageAdapter

    /** isGenerating：是否正在生成中 */
    private var isGenerating = false

    /** isEmbeddingLoaded：嵌入模型是否已加载 */
    private var isEmbeddingLoaded = false

    /** isLlmLoaded：LLM 模型是否已加载 */
    private var isLlmLoaded = false

    // ==================== 生命周期 ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRagBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupButtons()
        loadModels()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== UI 初始化 ====================

    /**
     * 初始化 RecyclerView（复用 ChatFragment 的 ChatMessageAdapter 模式）
     */
    private fun setupRecyclerView() {
        adapter = ChatMessageAdapter(messages)
        binding.rvRagMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRagMessages.adapter = adapter
    }

    /**
     * 设置按钮点击事件
     */
    private fun setupButtons() {
        // 添加文档按钮
        binding.btnAddDoc.setOnClickListener {
            showAddDocDialog()
        }

        // 提问按钮
        binding.btnRagSend.setOnClickListener {
            val input = binding.etRagInput.text.toString().trim()
            if (input.isNotBlank()) {
                askQuestion(input)
            }
        }
    }

    // ==================== 模型加载 ====================

    /**
     * 加载嵌入模型和 LLM 模型
     *
     * 加载顺序：
     * 1. 先加载嵌入模型（bge，~150MB，快）
     * 2. 再加载 LLM 模型（Qwen3，~1.5GB，慢）
     *
     * 为什么分开加载？
     * 嵌入模型是 RAG 检索必需的，LLM 是生成回答必需的。
     * 分开加载可以让用户先看到嵌入模型就绪的状态。
     */
    private fun loadModels() {
        binding.tvRagStatus.text = "正在加载模型..."

        lifecycleScope.launch {
            // Step 1: 加载嵌入模型
            val bgeEntry = ModelRegistry.defaultModels.find {
                it.modelDirName.contains("bge", ignoreCase = true)
            }
            if (bgeEntry != null) {
                val bgeDir = modelDownloader.getModelPath(bgeEntry.modelDirName)
                if (bgeDir.exists()) {
                    Log.d(TAG, "加载嵌入模型: ${bgeDir.absolutePath}")
                    isEmbeddingLoaded = embeddingEngine.load(bgeDir.absolutePath)
                    if (isEmbeddingLoaded) {
                        Log.d(TAG, "嵌入模型加载成功")
                    } else {
                        Log.e(TAG, "嵌入模型加载失败: ${embeddingEngine.lastError}")
                    }
                } else {
                    Log.e(TAG, "嵌入模型目录不存在: ${bgeDir.absolutePath}")
                }
            }

            // Step 2: 加载 LLM 模型
            val llmEntry = ModelRegistry.defaultModels.first()
            val llmDir = modelDownloader.getModelPath(llmEntry.modelDirName)
            if (llmDir.exists()) {
                Log.d(TAG, "加载 LLM 模型: ${llmDir.absolutePath}")
                isLlmLoaded = llmEngine.load(llmDir.absolutePath)
                if (isLlmLoaded) {
                    Log.d(TAG, "LLM 模型加载成功")
                } else {
                    Log.e(TAG, "LLM 模型加载失败")
                }
            } else {
                Log.e(TAG, "LLM 模型目录不存在: ${llmDir.absolutePath}")
            }

            // 更新 UI 状态
            updateStatus()
        }
    }

    /**
     * 更新状态显示
     */
    private fun updateStatus() {
        val embeddingStatus = if (isEmbeddingLoaded) "✅ 已加载" else "❌ 未加载"
        val llmStatus = if (isLlmLoaded) "✅ 已加载" else "❌ 未加载"
        val docCount = documentStore.loadAll().size
        val vectorCount = vectorStore.size

        binding.tvRagStatus.text = "嵌入模型: $embeddingStatus | LLM: $llmStatus"
        binding.tvDocCount.text = "📚 已索引文档: $docCount 篇 | 向量库: $vectorCount 条"
    }

    // ==================== 文档管理 ====================

    /**
     * 显示添加文档的对话框
     *
     * AlertDialog.Builder 的链式调用：
     * setTitle() → setMessage() → setView() → setPositiveButton() → create() → show()
     * 每个方法都返回 Builder 自身，所以可以一直 . 下去
     */
    private fun showAddDocDialog() {
        // 检查嵌入模型是否已加载
        if (!isEmbeddingLoaded) {
            Toast.makeText(requireContext(), "嵌入模型尚未加载，请等待", Toast.LENGTH_SHORT).show()
            return
        }

        // 创建输入框
        // LinearLayout(orientation=VERTICAL) 垂直排列两个 EditText
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val etTitle = EditText(requireContext()).apply {
            hint = "文档标题（如：药品管理法）"
        }
        val etContent = EditText(requireContext()).apply {
            hint = "文档内容（粘贴全文）"
            minLines = 5
            maxLines = 15
            // gravity = TOP：文字从顶部开始（默认是从中间）
            gravity = android.view.Gravity.TOP
        }

        layout.addView(etTitle)
        layout.addView(etContent)

        AlertDialog.Builder(requireContext())
            .setTitle("添加文档到知识库")
            .setView(layout)
            .setPositiveButton("索引") { _, _ ->
                val title = etTitle.text.toString().trim()
                val content = etContent.text.toString().trim()
                if (title.isNotBlank() && content.isNotBlank()) {
                    indexDocument(title, content)
                } else {
                    Toast.makeText(requireContext(), "标题和内容不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 索引文档：保存到本地 → 分块 → 嵌入 → 存入向量库
     *
     * @param title 文档标题
     * @param content 文档内容
     */
    private fun indexDocument(title: String, content: String) {
        binding.tvRagStatus.text = "正在索引: $title..."

        lifecycleScope.launch {
            try {
                // 保存文档到本地
                val doc = DocumentData(title = title, content = content)
                documentStore.save(doc)

                // 调用 RAGEngine 索引
                val chunkCount = ragEngine.indexDocument(title, content)

                // 更新文档的分块数
                documentStore.save(doc.copy(chunkCount = chunkCount))

                // 更新 UI
                updateStatus()
                Toast.makeText(
                    requireContext(),
                    "索引完成: $title ($chunkCount 个分块)",
                    Toast.LENGTH_SHORT
                ).show()

                // 添加系统消息到对话
                addMessage("system", "📄 已索引文档「$title」，分为 $chunkCount 个片段")
            } catch (e: Exception) {
                Log.e(TAG, "索引文档失败", e)
                Toast.makeText(requireContext(), "索引失败: ${e.message}", Toast.LENGTH_LONG).show()
                binding.tvRagStatus.text = "索引失败: ${e.message}"
            }
        }
    }

    // ==================== RAG 问答 ====================

    /**
     * RAG 问答：检索 → 组装 prompt → LLM 流式生成
     *
     * @param question 用户的问题
     */
    private fun askQuestion(question: String) {
        if (isGenerating) {
            Toast.makeText(requireContext(), "请等待当前回答完成", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isEmbeddingLoaded || !isLlmLoaded) {
            Toast.makeText(requireContext(), "模型尚未加载完成", Toast.LENGTH_SHORT).show()
            return
        }

        // 添加用户消息
        addMessage("user", question)
        binding.etRagInput.text?.clear()

        // 添加空的 AI 消息（占位）
        addMessage("assistant", "")
        isGenerating = true
        binding.btnRagSend.isEnabled = false
        binding.btnRagSend.text = "生成中..."

        lifecycleScope.launch {
            try {
                // ragEngine.ask() 内部会：
                // 1. 把问题编码为向量
                // 2. 在向量库中搜索相关文档
                // 3. 组装 prompt（系统指令 + 参考文档 + 问题）
                // 4. 调用 LLM 流式生成
                ragEngine.ask(question).collect { token ->
                    val lastIndex = messages.size - 1
                    val lastMessage = messages[lastIndex]
                    val newContent = lastMessage.content + token

                    // 清理空的 <think> 标签
                    val cleaned = newContent.replace(Regex("<think>\\s*</think>"), "")
                    messages[lastIndex] = lastMessage.copy(content = cleaned)

                    adapter.notifyItemChanged(lastIndex, "update_text")
                    binding.rvRagMessages.scrollToPosition(lastIndex)
                }
            } catch (e: Exception) {
                Log.e(TAG, "RAG 问答失败", e)
                val lastIndex = messages.size - 1
                messages[lastIndex] = messages[lastIndex].copy(
                    content = "生成出错: ${e.message}"
                )
                adapter.notifyItemChanged(lastIndex)
            } finally {
                isGenerating = false
                binding.btnRagSend.isEnabled = true
                binding.btnRagSend.text = "提问"
            }
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 添加消息到列表并通知 RecyclerView
     */
    private fun addMessage(sender: String, content: String) {
        messages.add(ChatMessage(sender, content))
        adapter.notifyItemInserted(messages.size - 1)
        binding.rvRagMessages.scrollToPosition(messages.size - 1)
    }

    companion object {
        private const val TAG = "RAGFragment"
    }
}


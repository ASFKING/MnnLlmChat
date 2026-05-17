package com.poc.ondevice.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.poc.ondevice.R
import com.poc.ondevice.databinding.FragmentChatBinding
import com.poc.ondevice.download.ModelDownloader
import com.poc.ondevice.download.ModelRegistry
import com.poc.ondevice.engine.LLMEngine
import kotlinx.coroutines.launch
import java.io.File

/**
 * ChatFragment：文本对话页
 *
 * 职责：
 * - 多轮对话（用户输入 → LLM 流式回答）
 * - 结构化提取（Day 5 实现）
 * - 文档生成（Day 5 实现）
 *
 * 当前状态：已接入 LLMEngine，支持流式对话
 */

// data class：Kotlin 数据类，自动生成 equals()、hashCode()、toString()、copy()
// 参数用 val（只读）或 var（可变）声明
// 这里用 val，因为消息创建后不应该被修改
data class ChatMessage(
    val sender: String,    // "user" 或 "assistant"
    val content: String    // 消息文本
)

/**
 * ChatFragment：文本对话页面
 *
 * 在我们的项目中：
 * - 用户在这里输入文字，发送给 LLM 模型
 * - LLM 的流式回答实时显示在 RecyclerView 消息列表中
 * - 后续会接入结构化提取（JSON 输出）和文档生成功能
 */
class ChatFragment : Fragment() {

    // _binding 和 binding 的双变量模式（Fragment ViewBinding 标准写法）
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    // mutableListOf()：创建一个可变的空列表
    // 与 listOf() 的区别：mutableListOf 可以后续添加/删除元素
    // 这里存储所有聊天消息
    private val messages = mutableListOf<ChatMessage>()

    // lateinit：延迟初始化，声明时不赋值，但保证在使用前一定会初始化
    // 这里因为 Adapter 需要在 onViewCreated 中创建（需要 context）
    // 而不是在声明时（声明时 context 可能还没准备好）
    private lateinit var adapter: ChatMessageAdapter

    /**
     * llmEngine：LLM 推理引擎
     *
     * 为什么用 by lazy？
     * 1. 延迟创建：只有第一次访问时才创建 LLMEngine 实例
     * 2. 线程安全：lazy 默认是线程安全的（LazyThreadSafetyMode.SYNCHRONIZED）
     * 3. 只初始化一次：后续访问返回同一个实例
     *
     * 类比：就像你家的热水器——你不会24小时开着，而是需要时才启动
     */
    private val llmEngine by lazy { LLMEngine() }

    /**
     * modelDownloader：模型下载器（用于获取模型路径）
     *
     * 为什么用 by lazy？
     * 需要 context 来初始化，而 context 在 onCreateView 之后才可用
     */
    private val modelDownloader by lazy { ModelDownloader(requireContext()) }

    /**
     * isGenerating：是否正在生成中
     *
     * 为什么需要这个标志？
     * 1. 防止重复发送：用户在生成过程中又点了发送，会导致两个推理任务同时进行
     * 2. 更新 UI 状态：生成中时禁用发送按钮，显示"停止"按钮
     * 3. 管理协程：停止生成时需要取消正在运行的协程
     */
    private var isGenerating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * onViewCreated：View 创建完毕，初始化 UI 组件
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化 RecyclerView
        setupRecyclerView()

        // 加载模型
        loadModel()

        // 发送按钮点击事件
        binding.btnSend.setOnClickListener {
            val input = binding.etInput.text.toString().trim()
            if (input.isNotBlank()) {
                sendMessage(input)
            }
        }
    }

    /**
     * 加载 LLM 模型
     *
     * 为什么在 onViewCreated 中调用？
     * 因为 View 创建完毕后，我们就可以安全地更新 UI（显示加载状态）
     *
     * 模型路径说明：
     * /data/data/com.poc.ondevice/files/models/qwen3-1.7b/
     * 这是 Android App 的私有目录，只有本 App 可以访问
     * 模型文件需要通过 adb push 推送到这个目录
     */
    private fun loadModel() {
        // 显示加载状态
        binding.tvStatus.text = "正在加载模型..."
        binding.tvStatus.visibility = View.VISIBLE
        binding.btnSend.isEnabled = false

        // 从 ModelRegistry 获取第一个模型（Qwen3-1.7B）的目录名
        val modelEntry = ModelRegistry.defaultModels.first()
        // 使用 ModelDownloader 获取正确的模型路径
        // 路径格式：/data/data/com.poc.ondevice/files/models/Qwen3-1.7B-MNN
        val modelDir = modelDownloader.getModelPath(modelEntry.modelDirName)

        // ===== 调试信息：检查模型目录是否存在 =====
        Log.d("ChatFragment", "模型目录: ${modelDir.absolutePath}")
        Log.d("ChatFragment", "目录是否存在: ${modelDir.exists()}")
        if (modelDir.exists()) {
            val files = modelDir.listFiles()
            Log.d("ChatFragment", "目录内文件数: ${files?.size ?: 0}")
            files?.forEach { file ->
                Log.d("ChatFragment", "  - ${file.name} (${file.length() / 1024 / 1024}MB)")
            }
        } else {
            Log.e("ChatFragment", "模型目录不存在！请先下载模型")
            binding.tvStatus.text = "请先在首页下载 Qwen3-1.7B 模型"
            binding.btnSend.isEnabled = false
            return
        }

        // lifecycleScope.launch：在生命周期感知的协程中执行
        // 当 Fragment 销毁时，协程自动取消，防止内存泄漏
        lifecycleScope.launch {
            try {
                // 调用引擎加载模型
                Log.d("ChatFragment", "开始加载模型...")
                val success = llmEngine.load(modelDir.absolutePath)
                Log.d("ChatFragment", "模型加载结果: $success")

                // 更新 UI（lifecycleScope 默认在主线程，可以安全更新 UI）
                if (success) {
                    binding.tvStatus.text = "模型已就绪"
                    binding.tvStatus.visibility = View.GONE
                    binding.btnSend.isEnabled = true
                    Toast.makeText(requireContext(), "模型加载成功！", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvStatus.text = "模型加载失败，请检查 Logcat 日志"
                    binding.btnSend.isEnabled = false
                    Toast.makeText(requireContext(), "模型加载失败", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ChatFragment", "模型加载异常", e)
                binding.tvStatus.text = "加载异常: ${e.message}"
                binding.btnSend.isEnabled = false
                Toast.makeText(requireContext(), "加载异常: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 初始化 RecyclerView
     *
     * RecyclerView 需要两个东西才能工作：
     * 1. LayoutManager：决定 item 怎么排列（线性/网格/瀑布流）
     * 2. Adapter：负责把数据绑定到 item 的 View 上
     */
    private fun setupRecyclerView() {
        // 创建 Adapter，传入消息列表
        adapter = ChatMessageAdapter(messages)
        // LinearLayoutManager：线性排列，垂直滚动
        // 类似 ScrollView 里的 LinearLayout，但只渲染可见 item
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        // 把 Adapter 设置给 RecyclerView
        binding.rvMessages.adapter = adapter
    }

    /**
     * 发送消息并获取 LLM 流式回答
     *
     * 流程：
     * 1. 添加用户消息到列表
     * 2. 创建一个空的 AI 消息（占位）
     * 3. 调用 LLMEngine.generateStream() 获取 Flow
     * 4. collect Flow，每收到一个 token 就更新 AI 消息
     * 5. 全部完成后，恢复 UI 状态
     *
     * @param text 用户输入的文本
     */
    private fun sendMessage(text: String) {
        // 如果正在生成中，不允许发送新消息
        // 这是一个简单的并发控制——避免多个推理任务同时进行
        if (isGenerating) {
            Toast.makeText(requireContext(), "请等待当前回答完成", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查模型是否已加载
        if (!llmEngine.isLoaded) {
            Toast.makeText(requireContext(), "模型尚未加载，请等待", Toast.LENGTH_SHORT).show()
            return
        }

        // 添加用户消息
        messages.add(ChatMessage("user", text))
        // notifyItemInserted()：通知 RecyclerView 有新 item 插入
        // 比 notifyDataSetChanged() 更高效（只更新新增的 item，不刷新整个列表）
        adapter.notifyItemInserted(messages.size - 1)
        // 滚动到底部，让用户看到新消息
        // smoothScrollToPosition：平滑滚动（有动画效果）
        binding.rvMessages.smoothScrollToPosition(messages.size - 1)

        // 清空输入框
        // .text 是 Editable? 类型，所以用 ?. 安全调用
        binding.etInput.text?.clear()

        // 添加一个空的 AI 消息（占位）
        // 后续流式更新时，会不断修改这个消息的内容
        messages.add(ChatMessage("assistant", ""))
        adapter.notifyItemInserted(messages.size - 1)

        // 设置生成状态
        isGenerating = true
        binding.btnSend.isEnabled = false
        binding.btnSend.text = "生成中..."

        // 启动协程，执行流式生成
        lifecycleScope.launch {
            try {
                // generateStream() 返回一个 Flow<String>
                // collect 会逐个接收 Flow 发出的 token
                llmEngine.generateStream(text).collect { token ->
                    // 获取最后一条消息（AI 的回复）
                    val lastIndex = messages.size - 1
                    val lastMessage = messages[lastIndex]

                    // 更新消息内容：把新 token 追加到现有内容后面
                    // copy() 创建一个新的 data class 实例（因为 val 属性不能修改）
                    var newContent = lastMessage.content + token

                    // 清理空的 <think> 标签
                    // Qwen3 的 thinking tokens 不通过 onProgress 回调发送，
                    // 但 <think> 和 </think> 标签会发过来，导致显示 <think></think> 空标签
                    // 用正则移除：匹配 <think> 后紧跟 <think> 或 <think>（中间只有空白）
                    newContent = newContent.replace(Regex("<think>\\s*</think>"), "")

                    messages[lastIndex] = lastMessage.copy(content = newContent)

                    // 通知 RecyclerView 局部更新（带 payload）
                    // 传入 payload（任意非空对象）后，RecyclerView 只调用
                    // onBindViewHolder(holder, position, payloads) 版本
                    // 而不是完整重绘，避免闪烁
                    adapter.notifyItemChanged(lastIndex, "update_text")

                    // 立即滚动到底部（无动画，避免 smoothScroll 的动画叠加导致抖动）
                    // scrollToPosition 是瞬间跳转，smoothScrollToPosition 是平滑动画
                    binding.rvMessages.scrollToPosition(lastIndex)
                }
            } catch (e: Exception) {
                // 如果生成过程中出错，显示错误信息
                val lastIndex = messages.size - 1
                messages[lastIndex] = messages[lastIndex].copy(
                    content = "生成出错: ${e.message}"
                )
                adapter.notifyItemChanged(lastIndex)
            } finally {
                // finally 块：无论成功还是失败，都会执行
                // 恢复 UI 状态
                isGenerating = false
                binding.btnSend.isEnabled = true
                binding.btnSend.text = "发送"
            }
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

/**
 * ChatMessageAdapter：消息列表的适配器
 *
 * RecyclerView.Adapter 的工作原理：
 * 1. onCreateViewHolder()：创建 ViewHolder（相当于"模具"）
 * 2. onBindViewHolder()：把数据"浇注"到 ViewHolder 中
 * 3. getItemCount()：告诉 RecyclerView 一共有多少条数据
 *
 * 泛型 <ChatMessageAdapter.ViewHolder>：指定这个 Adapter 使用的 ViewHolder 类型
 *
 * 为什么需要 Adapter？
 * RecyclerView 不直接管理 View，它只管"可见区域有几个 View"
 * Adapter 负责：数据 → View 的映射关系
 */
class ChatMessageAdapter(
    private val messages: List<ChatMessage>  // 构造函数参数，只读消息列表
) : RecyclerView.Adapter<ChatMessageAdapter.ViewHolder>() {

    /**
     * ViewHolder：缓存 item 的 View 引用
     *
     * 为什么要缓存？
     * findViewById() 是 O(n) 操作（遍历 View 树），每次调用都比较耗时
     * ViewHolder 在创建时一次性找到所有 View，之后直接通过属性访问，O(1)
     *
     * itemView：item 的根 View（这里就是 item_chat_message.xml 的根 LinearLayout）
     */
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // findViewById<T>()：在 itemView 中查找指定 id 的 View，并转换为类型 T
        val tvSender: TextView = itemView.findViewById(R.id.tv_sender)
        val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
    }

    /**
     * onCreateViewHolder：创建 ViewHolder
     *
     * 调用时机：RecyclerView 需要一个新的 item View 时（首次显示或回收池为空时）
     *
     * @param parent RecyclerView 本身（用来获取 context 和 layout params）
     * @param viewType item 类型（用于多种 item 样式的场景，这里只有一种）
     * @return 一个新的 ViewHolder
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // LayoutInflater.from(context)：从 parent 的 context 获取布局填充器
        // inflate(layoutId, parent, false)：加载 XML 布局
        //   layoutId：要加载的布局资源
        //   parent：用于计算 layout params 的父容器
        //   false：不立即添加到 parent（RecyclerView 自己管理）
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(view)
    }

    /**
     * onBindViewHolder：绑定数据到 ViewHolder（完整绑定，无 payload 时调用）
     *
     * 调用时机：
     * 1. ViewHolder 首次绑定数据
     * 2. 调用 notifyItemChanged(position) 不带 payload 时
     *
     * @param holder 要绑定数据的 ViewHolder
     * @param position 数据在列表中的位置（从 0 开始）
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        holder.tvSender.text = if (message.sender == "user") "你" else "AI"
        holder.tvMessage.text = message.content
    }

    /**
     * onBindViewHolder：带 payload 的局部绑定（流式更新专用）
     *
     * 为什么需要这个重载？
     * 当我们调用 notifyItemChanged(position, payload) 时，
     * RecyclerView 会调用这个版本而不是上面的完整版本。
     * 这样只会更新文字内容，不会重绘背景、重新布局等，避免闪烁。
     *
     * 生活类比：上面的版本是"把整块黑板擦掉重写"，
     * 这个版本是"只擦掉最后一行字，写上新内容"。
     *
     * @param payloads 变更标记列表（我们传的是 "update_text"）
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty()) {
            // 有 payload → 局部更新，只修改文字
            val message = messages[position]
            holder.tvMessage.text = message.content
        } else {
            // 无 payload → 走完整绑定
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    /**
     * getItemCount：返回数据总量
     *
     * RecyclerView 根据这个值来决定：
     * - 滚动范围有多大
     * - 需要创建多少个 ViewHolder
     */
    override fun getItemCount(): Int = messages.size
    // = messages.size 是单表达式函数
    // 等价于 { return messages.size }
}

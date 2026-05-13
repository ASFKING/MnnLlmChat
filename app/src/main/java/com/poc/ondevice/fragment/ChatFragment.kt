package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.poc.ondevice.R
import com.poc.ondevice.databinding.FragmentChatBinding

/**
 * ChatFragment：文本对话页
 *
 * 职责：
 * - 多轮对话（用户输入 → LLM 流式回答）
 * - 结构化提取（Day 5 实现）
 * - 文档生成（Day 5 实现）
 *
 * 当前状态：UI 骨架就绪，LLM 推理逻辑待 Day 1 完成后接入
 */

// data class：Kotlin 数据类，自动生成 equals()、hashCode()、toString()、copy()
// 参数用 val（只读）或 var（可变）声明
// 这里用 val，因为消息创建后不应该被修改
data class ChatMessage(
    val sender: String,    // "user" 或 "assistant"
    val content: String    // 消息文本
)

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    // mutableListOf()：创建一个可变的空列表
    // 与 listOf() 的区别：mutableListOf 可以后续添加/删除元素
    private val messages = mutableListOf<ChatMessage>()

    // lateinit：延迟初始化，声明时不赋值，但保证在使用前一定会初始化
    // 这里因为 Adapter 需要在 onViewCreated 中创建（需要 context）
    // 而不是在声明时（声明时 context 可能还没准备好）
    private lateinit var adapter: ChatMessageAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化 RecyclerView
        setupRecyclerView()

        // 发送按钮点击事件
        // setOnClickListener：设置点击回调
        // lambda 表达式 { ... } 是回调函数
        binding.btnSend.setOnClickListener {
            val input = binding.etInput.text.toString().trim()
            // isBlank()：检查字符串是否为空或只包含空白字符
            // 与 isEmpty() 的区别：isEmpty 只检查长度，isBlank 还检查空白
            if (input.isNotBlank()) {
                sendMessage(input)
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
        adapter = ChatMessageAdapter(messages)
        // LinearLayoutManager：线性排列，垂直滚动
        // 类似 ScrollView 里的 LinearLayout，但只渲染可见 item
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = adapter
    }

    /**
     * 发送消息
     *
     * 当前只是本地模拟，LLM 推理逻辑待后续接入
     */
    private fun sendMessage(text: String) {
        // 添加用户消息
        messages.add(ChatMessage("user", text))
        // notifyItemInserted()：通知 RecyclerView 有新 item 插入
        // 比 notifyDataSetChanged() 更高效（只更新新增的 item，不刷新整个列表）
        adapter.notifyItemInserted(messages.size - 1)
        // 滚动到底部，让用户看到新消息
        binding.rvMessages.smoothScrollToPosition(messages.size - 1)

        // 清空输入框
        binding.etInput.text?.clear()

        // TODO: 这里后续接入 LLMEngine，发送到模型并流式显示回复
        // 目前先用模拟回复，验证 UI 流程
        messages.add(ChatMessage("assistant", "（LLM 推理尚未接入，这是占位回复）"))
        adapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.smoothScrollToPosition(messages.size - 1)
    }

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
     * 参数：
     * - parent：RecyclerView 本身（用来获取 context 和 layout params）
     * - viewType：item 类型（用于多种 item 样式的场景，这里只有一种）
     *
     * 返回：一个新的 ViewHolder
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
     * onBindViewHolder：绑定数据到 ViewHolder
     *
     * 调用时机：RecyclerView 需要把某个 position 的数据绑定到 ViewHolder 时
     * 这里也包括 ViewHolder 被回收后复用时（position 变了，View 要更新内容）
     *
     * 参数：
     * - holder：要绑定数据的 ViewHolder
     * - position：数据在列表中的位置（从 0 开始）
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        // 根据 sender 类型设置不同的显示样式
        holder.tvSender.text = if (message.sender == "user") "你" else "AI"
        holder.tvMessage.text = message.content
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

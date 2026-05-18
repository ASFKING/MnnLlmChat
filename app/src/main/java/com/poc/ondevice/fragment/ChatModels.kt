package com.poc.ondevice.fragment

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.poc.ondevice.R

/**
 * ChatMessage：对话消息数据类
 *
 * 为什么单独建文件？
 * ChatFragment 和 RAGFragment 都需要这个数据类，
 * 提取到独立文件避免重复定义，也方便未来扩展（比如加时间戳、消息类型等字段）
 */
data class ChatMessage(
    val sender: String,    // "user"、"assistant" 或 "system"
    val content: String
)

/**
 * ChatMessageAdapter：消息列表适配器
 *
 * 支持三种 sender 类型：user / assistant / system
 * 使用 payload 机制实现流式更新（局部刷新，避免闪烁）
 */
class ChatMessageAdapter(
    private val messages: List<ChatMessage>
) : RecyclerView.Adapter<ChatMessageAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvSender: TextView = itemView.findViewById(R.id.tv_sender)
        val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        holder.tvSender.text = when (message.sender) {
            "user" -> "你"
            "assistant" -> "AI"
            "system" -> "📢"
            else -> message.sender
        }
        holder.tvMessage.text = message.content
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty()) {
            val message = messages[position]
            holder.tvMessage.text = message.content
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = messages.size
}

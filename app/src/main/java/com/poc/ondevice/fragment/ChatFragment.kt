package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class ChatFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return TextView(requireContext()).apply {
            text = "💬 文本对话页\n\n多轮对话 + 结构化提取 + 文档生成\n\n（Day 2 搭建完成 ✅）"
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
    }
}

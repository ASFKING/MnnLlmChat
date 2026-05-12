package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class RAGFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return TextView(requireContext()).apply {
            text = "🔍 RAG 问答页\n\n文档上传 + 知识库管理 + 对话问答\n\n（Day 2 搭建完成 ✅）"
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
    }
}

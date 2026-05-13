package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.poc.ondevice.databinding.FragmentRagBinding

/**
 * RAGFragment：RAG 问答页
 *
 * 职责：
 * - 文档上传和管理（添加/删除文档）
 * - 知识库状态显示
 * - 基于文档的问答对话
 *
 * 当前状态：UI 骨架就绪，RAGEngine 待 Day 4 实现
 */
class RAGFragment : Fragment() {

    private var _binding: FragmentRagBinding? = null
    private val binding get() = _binding!!

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

        // 添加文档按钮点击事件
        // TODO: Day 4 实现文档上传和索引
        binding.btnAddDoc.setOnClickListener {
            // 后续会弹出文档输入对话框
        }

        // 提问按钮点击事件
        // TODO: Day 4 接入 RAGEngine
        binding.btnRagSend.setOnClickListener {
            val input = binding.etRagInput.text.toString().trim()
            if (input.isNotBlank()) {
                // 后续接入 RAGEngine.ask()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

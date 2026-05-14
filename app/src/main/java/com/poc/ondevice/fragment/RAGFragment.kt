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
 * RAG 是什么（三层解释法）：
 * 一句话：检索增强生成——先从文档库找到相关内容，再用 LLM 基于这些内容回答问题。
 * 生活类比：就像开卷考试——先翻书找到相关段落，再用自己的话回答。
 * 技术细节：用户提问 → EmbeddingEngine 把问题转成向量 → VectorStore 搜索相似文档
 *           → 把检索到的文档拼进 prompt → LLM 基于文档生成回答
 *
 * 职责：
 * - 文档上传和管理（添加/删除文档）
 * - 知识库状态显示
 * - 基于文档的问答对话
 *
 * 当前状态：UI 骨架就绪，RAGEngine 待 Day 4 实现
 */
class RAGFragment : Fragment() {

    // Fragment ViewBinding 的标准双变量模式
    private var _binding: FragmentRagBinding? = null
    // binding.get()：通过自定义 getter 返回非空版本
    // !! 是非空断言操作符——如果 _binding 为 null 会抛异常
    // 因为我们只在 onCreateView 和 onDestroyView 之间使用 binding，所以是安全的
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // inflate：把 XML 布局文件转换成 View 对象
        _binding = FragmentRagBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 添加文档按钮点击事件
        // TODO: Day 4 实现文档上传和索引
        // 后续流程：弹出输入框 → 用户输入文档标题和内容 → RAGEngine.indexDocument()
        binding.btnAddDoc.setOnClickListener {
            // 后续会弹出文档输入对话框
        }

        // 提问按钮点击事件
        // TODO: Day 4 接入 RAGEngine
        // 后续流程：用户输入问题 → RAGEngine.ask(question) → 流式显示回答
        binding.btnRagSend.setOnClickListener {
            // 获取输入框的文本并去除首尾空白
            val input = binding.etRagInput.text.toString().trim()
            if (input.isNotBlank()) {
                // 后续接入 RAGEngine.ask()
            }
        }
    }

    /**
     * onDestroyView：View 销毁时清理 binding 引用
     * 防止内存泄漏（Fragment 可能比 View 活得更久）
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.poc.ondevice.databinding.FragmentVisionBinding

/**
 * VisionFragment：图片理解页
 *
 * 多模态理解是什么（三层解释法）：
 * 一句话：让 AI 同时"看"图片和"读"文字，理解两者的关系。
 * 生活类比：就像你给朋友看一张照片，然后问"这是哪里？"——朋友同时用眼睛看和耳朵听。
 * 技术细节：图片经过视觉编码器（Vision Encoder）转成向量，和文字向量一起送入 LLM，
 *           LLM 综合理解后生成回答。这里用 Qwen2.5-VL-3B 模型。
 *
 * 职责：
 * - 图片选择和预览
 * - 图片 + 文本联合理解
 * - 结果展示
 *
 * 当前状态：UI 骨架就绪，VisionEngine 待 Day 6 实现
 */
class VisionFragment : Fragment() {

    private var _binding: FragmentVisionBinding? = null
    private val binding get() = _binding!!

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

        // 选择图片按钮
        // TODO: Day 6 实现图片选择器
        // 后续流程：
        // 1. 用 Intent(ACTION_PICK) 打开系统图片选择器
        // 2. 用户选完图片后，onActivityResult 回调拿到图片 Uri
        // 3. 用 ContentResolver 读取图片，显示在 iv_preview 中
        binding.btnSelectImage.setOnClickListener {
            // 后续会打开系统图片选择器
        }

        // 提问按钮
        // TODO: Day 6 接入 VisionEngine
        // 后续流程：
        // 1. 获取用户输入的问题文本
        // 2. 调用 VisionEngine.infer(imageBitmap, question)
        // 3. 流式显示结果到 tv_vision_result
        binding.btnVisionAsk.setOnClickListener {
            val input = binding.etVisionInput.text.toString().trim()
            if (input.isNotBlank()) {
                // 后续接入 VisionEngine
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

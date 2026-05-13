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
        // TODO: Day 6 实现图片选择器（ACTION_PICK + ContentResolver）
        binding.btnSelectImage.setOnClickListener {
            // 后续会打开系统图片选择器
        }

        // 提问按钮
        // TODO: Day 6 接入 VisionEngine
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

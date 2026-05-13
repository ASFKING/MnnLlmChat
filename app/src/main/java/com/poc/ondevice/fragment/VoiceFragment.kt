package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.poc.ondevice.databinding.FragmentVoiceBinding

/**
 * VoiceFragment：语音对话页
 *
 * 职责：
 * - 按住说话 → ASR 语音识别
 * - LLM 流式回答
 * - TTS 语音播报
 * - 全链路 ASR → LLM → TTS 串联
 *
 * 当前状态：UI 骨架就绪，语音引擎待 Day 7-8 实现
 */
class VoiceFragment : Fragment() {

    private var _binding: FragmentVoiceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVoiceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 按住说话按钮
        // TODO: Day 7 接入 ASREngine + AudioRecorder
        // 后续实现：
        // - ACTION_DOWN → 开始录音 + ASR 流式识别
        // - ACTION_UP → 停止录音 → LLM 推理 → TTS 播放
        binding.btnTalk.setOnClickListener {
            // 后续接入语音对话引擎
        }

        // 停止按钮
        // TODO: Day 8 实现停止逻辑
        binding.btnStop.setOnClickListener {
            // 后续停止 ASR/LLM/TTS
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

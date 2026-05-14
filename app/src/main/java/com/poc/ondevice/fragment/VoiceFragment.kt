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
 * 全链路语音对话是什么（三层解释法）：
 * 一句话：用户说话 → AI 听懂 → AI 思考 → AI 说出来。
 * 生活类比：就像和朋友打电话——你说，他听，他想，他说。
 * 技术细节：AudioRecorder 录音 → ASREngine 语音识别 → LLMEngine 文本推理
 *           → TTSEngine 语音合成 → AudioPlayer 播放
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
        // - ACTION_DOWN（按下）→ 开始录音 + ASR 流式识别
        // - ACTION_UP（抬起）→ 停止录音 → LLM 推理 → TTS 播放
        // setOnClickListener：只响应点击（按下+抬起）
        // 后续需要改成 setOnTouchListener 来区分按下和抬起
        binding.btnTalk.setOnClickListener {
            // 后续接入语音对话引擎
        }

        // 停止按钮
        // TODO: Day 8 实现停止逻辑
        // 后续实现：停止 ASR/LLM/TTS 三个引擎的当前任务
        binding.btnStop.setOnClickListener {
            // 后续停止 ASR/LLM/TTS
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

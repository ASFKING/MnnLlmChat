package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class VoiceFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return TextView(requireContext()).apply {
            text = "🎤 语音对话页\n\n按住说话 + ASR 识别 + LLM 回答 + TTS 播放\n\n（Day 2 搭建完成 ✅）"
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
    }
}

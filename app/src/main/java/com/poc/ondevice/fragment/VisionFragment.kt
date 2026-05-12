package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class VisionFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return TextView(requireContext()).apply {
            text = "🖼️ 图片理解页\n\n图片选择器 + 文字输入 + 结果展示\n\n（Day 2 搭建完成 ✅）"
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
    }
}

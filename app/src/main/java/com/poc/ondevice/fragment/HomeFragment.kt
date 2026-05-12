package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class HomeFragment: Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return  TextView(requireContext()).apply {
            text = "📦 系统状态页\n\n各模型加载状态、内存使用、性能数据\n\n（Day 2 搭建完成 ✅）"
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
    }
}
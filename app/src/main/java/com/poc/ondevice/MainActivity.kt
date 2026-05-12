package com.poc.ondevice

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
// ViewBinding：为每个 XML 布局自动生成一个 Binding 类
// activity_main.xml → ActivityMainBinding
// 用 binding.root、binding.bottomNav 等直接访问 View，不用 findViewById
import com.poc.ondevice.databinding.ActivityMainBinding
import com.poc.ondevice.fragment.*

class MainActivity : AppCompatActivity() {
    // lateinit 表示"这个变量在声明时不初始化，但保证在使用前一定会初始化"
    // 因为 binding 需要在 onCreate 中才能初始化（需要 layoutInflater）
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ViewBinding 初始化三步曲：
        // 1. inflate(layoutInflater) — 用布局填充器把 XML 转成 View 对象
        // 2. setContentView(binding.root) — 把根 View 设为 Activity 的内容
        // 3. 之后就可以用 binding.xxx 直接访问布局中的任何 View
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 处理系统栏（状态栏、导航栏）的内边距，让内容不被遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // savedInstanceState == null 表示 Activity 是第一次创建（不是旋转屏幕等重建）
        // 第一次创建时显示默认的首页 Fragment
        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }

        // setOnItemSelectedListener 设置底部导航栏的 Tab 点击监听
        // 当用户点击某个 Tab 时，lambda 被调用，参数 item 就是被点击的 Tab
        binding.bottomNav.setOnItemSelectedListener { item ->
            // when 是 Kotlin 的模式匹配表达式，类似 Java 的 switch
            // 根据被点击的 Tab 的 id，选择对应的 Fragment
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_chat -> ChatFragment()
                R.id.nav_rag -> RAGFragment()
                R.id.nav_vision -> VisionFragment()
                R.id.nav_voice -> VoiceFragment()
                else -> HomeFragment()
            }
            loadFragment(fragment)
            true  // 返回 true 表示"我已处理这次点击，让 Tab 高亮"
        }
    }

    // 切换 Fragment 的方法
    // supportFragmentManager 管理 Activity 中所有 Fragment 的生命周期
    // beginTransaction() 开始一次 Fragment 事务
    // replace() 把容器中的旧 Fragment 替换成新 Fragment
    // commit() 提交事务，让更改生效
    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}

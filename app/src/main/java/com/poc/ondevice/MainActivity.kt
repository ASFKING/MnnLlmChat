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

/**
 * MainActivity：应用的主入口 Activity
 *
 * 业务职责：
 * - 承载所有 Fragment 的容器
 * - 管理底部导航栏（5 个 Tab：状态/对话/RAG/视觉/语音）
 * - 处理 Fragment 之间的切换
 *
 * 类比：MainActivity 就像一栋大楼的大厅，
 * Fragment 就像各个房间（状态室、对话室、RAG 室…），
 * 底部导航栏就是电梯按钮——按哪个就去哪个房间。
 */
class MainActivity : AppCompatActivity() {

    // lateinit 表示"这个变量在声明时不初始化，但保证在使用前一定会初始化"
    // 因为 binding 需要在 onCreate 中才能初始化（需要 layoutInflater）
    private lateinit var binding: ActivityMainBinding

    /**
     * onCreate：Activity 创建时调用
     *
     * 生命周期说明：
     * Activity 的生命周期是 Android 最核心的概念之一
     * onCreate → onStart → onResume → [运行中] → onPause → onStop → onDestroy
     * onCreate 是"出生"阶段，做初始化工作
     *
     * @param savedInstanceState 如果 Activity 被系统回收后重建，这里保存了之前的状态数据
     *                           第一次创建时为 null
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // enableEdgeToEdge()：启用全面屏显示
        // 让 App 内容延伸到状态栏和导航栏后面（沉浸式体验）
        // 这是 Android 15 推荐的做法
        enableEdgeToEdge()

        // ViewBinding 初始化三步曲：
        // 1. inflate(layoutInflater) → 用布局填充器把 XML 转成 View 对象
        //    类比：把图纸（XML）变成实物（View）
        // 2. setContentView(binding.root) → 把根 View 设为 Activity 的内容
        //    类比：把造好的房子放进地皮（Activity 窗口）
        // 3. 之后就可以用 binding.xxx 直接访问布局中的任何 View
        //    类比：用钥匙直接打开房子里的任何门
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 处理系统栏（状态栏、导航栏）的内边距
        // setOnApplyWindowInsetsListener：监听系统栏的变化
        // 当系统栏出现/隐藏/改变大小时，这个回调会被调用
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            // getInsets(Type.systemBars())：获取系统栏（状态栏+导航栏）占据的空间
            // 返回一个 Insets 对象，包含 top/bottom/left/right 四个方向的像素数
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 设置 padding，让内容不被系统栏遮挡
            // 类比：画框时，边缘留白，不让画面被边框挡住
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
                R.id.nav_home -> HomeFragment()     // 状态页
                R.id.nav_chat -> ChatFragment()     // 文本对话页
                R.id.nav_rag -> RAGFragment()       // RAG 问答页
                R.id.nav_vision -> VisionFragment() // 图片理解页
                R.id.nav_voice -> VoiceFragment()   // 语音对话页
                else -> HomeFragment()               // 默认显示首页
            }
            loadFragment(fragment)
            true  // 返回 true 表示"我已处理这次点击，让 Tab 高亮"
        }
    }

    /**
     * 切换 Fragment
     *
     * supportFragmentManager 管理 Activity 中所有 Fragment 的生命周期
     * beginTransaction() 开始一次 Fragment 事务（类似数据库事务，要么全做，要么全不做）
     * replace() 把容器中的旧 Fragment 替换成新 Fragment
     * commit() 提交事务，让更改生效（异步执行，不阻塞主线程）
     *
     * @param fragment 要显示的 Fragment 实例
     */
    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}

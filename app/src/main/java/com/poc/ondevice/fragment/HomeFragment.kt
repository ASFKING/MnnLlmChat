package com.poc.ondevice.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
// ViewBinding：为 fragment_home.xml 自动生成 FragmentHomeBinding 类
// 编译时根据 XML 中的 android:id 属性生成对应的属性
// 例如 android:id="@+id/tv_llm_status" → binding.tvLlmStatus
import com.poc.ondevice.databinding.FragmentHomeBinding

/**
 * HomeFragment：系统状态页
 *
 * 职责：
 * - 显示各模型的加载状态（LLM / 嵌入 / ASR / TTS / 多模态）
 * - 显示内存使用情况
 * - 显示向量库统计
 * - 显示性能数据（TTFT、tok/s 等）
 *
 * ViewBinding 使用三步：
 * 1. 声明可空的 _binding 变量（Fragment 需要，Activity 不需要）
 * 2. 在 onCreateView 中 inflate 并赋值
 * 3. 在 onDestroyView 中置空（防止内存泄漏）
 */
class HomeFragment : Fragment() {

    // _binding 是可空的，因为 Fragment 的 View 可能在 onDestroyView 后被销毁
    // 但 Fragment 实例还活着（比如被放入回退栈时）
    // 如果不置空，访问已销毁的 View 会导致崩溃
    private var _binding: FragmentHomeBinding? = null

    // binding 是非空的访问器，用 get() 提供便捷访问
    // 只在 onCreateView 和 onDestroyView 之间可用
    // !! 是非空断言操作符，告诉编译器"我确定这时不为 null"
    private val binding get() = _binding!!

    /**
     * onCreateView：创建 Fragment 的 View
     *
     * 参数说明：
     * - inflater：布局填充器，把 XML 转成 View 对象
     * - container：父容器（activity_main.xml 中的 FrameLayout）
     * - savedInstanceState：Fragment 重建时的状态（旋转屏幕等）
     *
     * 返回：Fragment 要显示的 View
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // FragmentHomeBinding.inflate()：用 ViewBinding 加载布局
        // inflater：布局填充器
        // container：父容器（ViewBinding 需要用来计算 layout params）
        // false：不立即添加到父容器（Fragment 系统会自己管理）
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root  // binding.root 获取根 View（ScrollView）
    }

    /**
     * onViewCreated：View 创建完成后调用
     *
     * 这里是初始化 UI 数据的最佳时机
     * 因为此时 View 已经创建，可以安全地访问 binding 的属性
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateMemoryInfo()
    }

    /**
     * 更新内存信息显示
     *
     * Runtime.getRuntime()：获取当前 Java 虚拟机的运行时对象
     * totalMemory()：JVM 当前已分配的内存总量
     * freeMemory()：JVM 当前空闲内存
     * maxMemory()：JVM 最大可用内存
     *
     * 这些是 JVM 层面的内存，不包含 native 层（MNN .so）的内存占用
     * 后续会用 Android 的 Debug.getNativeHeapAllocatedSize() 获取更准确的数据
     */
    private fun updateMemoryInfo() {
        val runtime = Runtime.getRuntime()
        // 已用内存 = 已分配 - 空闲
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        // 总分配内存
        val totalMB = runtime.totalMemory() / 1024 / 1024
        // 最大可用内存
        val maxMB = runtime.maxMemory() / 1024 / 1024

        binding.tvMemoryInfo.text = buildString {
            // buildString {}：Kotlin 的字符串构建器
            // 比连续拼接 "+" 更高效、更可读
            appendLine("JVM 已用: ${usedMB}MB")
            appendLine("JVM 已分配: ${totalMB}MB")
            appendLine("JVM 最大可用: ${maxMB}MB")
            appendLine("（Native 内存需加载模型后通过 Debug API 获取）")
        }
    }

    /**
     * onDestroyView：View 即将被销毁时调用
     *
     * 必须在这里把 _binding 置为 null，否则会导致内存泄漏
     * 因为 binding 持有对 View 的引用，View 持有对 Context（Activity）的引用
     * 如果不释放，Activity 就无法被 GC 回收
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

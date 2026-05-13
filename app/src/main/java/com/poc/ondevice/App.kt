package com.poc.ondevice

import android.app.Application
import com.alibaba.mls.api.ApplicationProvider

/**
 * App：Application 入口
 *
 * Application 是 Android 应用的"大管家"，比所有 Activity 都先创建，最后一个销毁。
 * 适合做全局初始化工作。
 *
 * 类比：如果 App 是一家公司，Application 就是 CEO——
 * 公司开张（进程启动）时第一个到，公司关门（进程被杀）时最后一个走。
 *
 * 为什么需要这个类？
 * 原始 MNN 代码用 ApplicationProvider.get() 获取全局 Context，
 * 我们必须在 App 启动时调用 ApplicationProvider.init() 完成初始化。
 */
class App : Application() {

    /**
     * onCreate：Application 创建时调用（整个 App 生命周期只调用一次）
     *
     * 调用时机：用户第一次打开 App，或 App 进程被系统回收后重新启动
     * 在这里做全局初始化，比在每个 Activity 里重复初始化更高效
     */
    override fun onCreate() {
        super.onCreate()
        // 初始化 ApplicationProvider，让全局代码能拿到 Application Context
        // 这样 ModelConfig、MmapUtils 等文件中的 ApplicationProvider.get() 就不会报错
        ApplicationProvider.init(this)
    }
}

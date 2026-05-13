package com.alibaba.mls.api

import android.app.Application
import android.content.Context

/**
 * ApplicationProvider：应用 Context 的全局提供者
 *
 * 原始 MNN 项目中，这个类在 MLS 模块中实现，提供全局 Application Context。
 * PoC 阶段用这个简化版本替代，功能完全一致。
 *
 * 一句话：就是全局的 Context 工厂，任何地方都能拿到 Application 实例。
 */
object ApplicationProvider {
    private var app: Application? = null

    /**
     * 初始化（在 Application.onCreate 中调用）
     */
    fun init(application: Application) {
        app = application
    }

    /**
     * 获取 Application 实例
     *
     * 为什么用 Application 而不是 Activity？
     * - Activity 会随屏幕旋转等重建，生命周期短
     * - Application 与 App 进程同生共死，适合做全局单例
     */
    fun get(): Application {
        return app ?: throw IllegalStateException(
            "ApplicationProvider not initialized. Call ApplicationProvider.init() in Application.onCreate()"
        )
    }

    /**
     * 获取 Context（便捷方法）
     */
    fun getContext(): Context = get()
}

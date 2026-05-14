package com.alibaba.mls.api

import android.app.Application
import android.content.Context

/**
 * ApplicationProvider：应用 Context 的全局提供者
 *
 * 一句话：全局的 Context 工厂，任何地方都能拿到 Application 实例。
 * 生活类比：就像公司的总机号码——任何部门都能通过总机找到人。
 *
 * 为什么需要这个？
 * Android 中很多操作需要 Context（如文件读写、获取系统服务），
 * 但不是所有代码都有 Activity 引用（如工具类、单例），
 * Application Context 是全局可用的，生命周期与 App 进程一致。
 */
object ApplicationProvider {
    // app：Application 实例（私有，只能通过 get() 访问）
    private var app: Application? = null

    /**
     * 初始化（在 Application.onCreate 中调用）
     *
     * @param application Application 实例
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
     *
     * ?: throw：如果 app 为 null，抛出异常
     * 这确保了在 init() 之前调用 get() 会立即报错，而不是返回 null 导致后续崩溃
     */
    fun get(): Application {
        return app ?: throw IllegalStateException(
            "ApplicationProvider not initialized. Call ApplicationProvider.init() in Application.onCreate()"
        )
    }

    /**
     * 获取 Context（便捷方法）
     * Application 本身就是 Context 的子类
     */
    fun getContext(): Context = get()
}

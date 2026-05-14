package com.alibaba.mnnllm.android

/**
 * MNN：JNI 入口类
 *
 * JNI（Java Native Interface）是什么：
 * 一句话：JNI 是 Java/Kotlin 调用 C/C++ 代码的桥梁。
 * 生活类比：就像翻译官——Kotlin 说中文，C++ 说英文，JNI 帮它们互相沟通。
 * 技术细节：通过 System.loadLibrary() 加载编译好的 .so 文件（C++ 编译产物），
 *           然后用 external fun 声明 C++ 函数的"签名"，Kotlin 就能像调普通函数一样调 C++ 代码。
 *
 * 这个类的职责：
 * - 加载 libmnnllmapp.so（MNN-LLM 的 JNI 库）
 * - 提供 MNN 版本查询接口
 *
 * 注意：这个类必须在任何 MNN 功能调用之前被加载，
 * 因为 LlmSession、DiffusionSession 等类都依赖 libmnnllmapp.so 中的 native 方法。
 */
object MNN {

    /**
     * nativeGetVersion：获取 MNN 引擎版本号
     *
     * external fun：声明一个 JNI 原生方法
     * "external" 表示这个函数的实现不在 Kotlin 中，而是在 C++ 的 .so 文件里
     * Kotlin 编译器只检查签名，实际执行时由 JNI 桥接到 C++ 代码
     *
     * 业务用途：调试和日志，确认加载的 MNN 版本是否正确
     */
    external fun nativeGetVersion(): String

    /**
     * getVersion：获取 MNN 版本的 Kotlin 包装方法
     *
     * 为什么要包装一层？
     * - nativeGetVersion() 是 JNI 方法，外部模块不应该直接调用
     * - 通过这个包装方法，可以添加日志、缓存等逻辑
     * - 保持 API 的一致性
     */
    fun getVersion(): String {
        return nativeGetVersion()
    }

    /**
     * init 块：Kotlin 的初始化块
     *
     * init 块在 object 单例第一次被访问时执行（类似 Java 的 static 块）
     * System.loadLibrary("mnnllmapp")：
     * - 从 jniLibs/arm64-v8a/ 目录加载 libmnnllmapp.so
     * - 加载成功后，所有 external fun 声明的方法就能被调用了
     * - 如果加载失败（比如 .so 文件不存在），会抛出 UnsatisfiedLinkError
     *
     * 类比：就像给翻译官穿上装备——装备上了，翻译官才能工作
     */
    init {
        System.loadLibrary("mnnllmapp")
    }
}

plugins {
    // alias(libs.plugins.android.application)：从 libs.versions.toml 引入 Android 应用插件
    // Gradle 插件 = 构建流程中的"处理器"，android.application 插件负责编译、打包 APK
    alias(libs.plugins.android.application)
}

android {
    // namespace：包名，Android 系统用它区分不同 App
    // 类比：就像人的身份证号，全球唯一
    namespace = "com.poc.ondevice"

    // compileSdk = 35：用 Android 15（API 35）的 SDK 编译
    // 注意：这不等于最低支持版本，只是编译时能用最新 API
    compileSdk = 35

    defaultConfig {
        // applicationId：App 在设备上的唯一标识（用于安装、卸载、数据隔离）
        // 与 namespace 可以不同，但通常保持一致
        applicationId = "com.poc.ondevice"

        // minSdk = 26：最低支持 Android 8.0（API 26）
        // 低于此版本的设备无法安装
        minSdk = 26

        // targetSdk = 35：目标 Android 15
        // 告诉系统"我已适配到这个版本"，系统会启用对应的行为变更
        targetSdk = 35

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.junit4.AndroidJUnitRunner"

        // ndk.abiFilters：指定编译哪些 CPU 架构的原生代码
        // arm64-v8a = 64 位 ARM（主流 Android 手机都是这个）
        // 我们的 .so 文件只有 arm64-v8a 版本，所以只编译这个架构
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // compileOptions：Java 编译版本
    // JVM 17 是当前 Android 开发的主流目标
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // buildFeatures：开启或关闭构建功能
    // viewBinding = true：启用视图绑定
    // 作用：为每个 XML 布局自动生成一个 Binding 类
    // 例如 activity_main.xml → ActivityMainBinding
    // 用 binding.root、binding.bottomNav 等直接访问 View，不用 findViewById
    buildFeatures {
        viewBinding = true
    }

    // androidResources.noCompress：告诉打包工具不要压缩这些文件后缀
    // MNN 引擎需要直接读取模型文件（mnn、weight、bin），如果被压缩了就读不了
    // 类比：你不能把 zip 包里的东西直接当文件用，得先解压
    androidResources {
        noCompress += listOf("mnn", "bin", "txt", "json", "onnx", "weight")
    }
}

// kotlin 编译目标 JVM 版本
// 必须与上面的 compileOptions 一致，否则 Kotlin 编译器会报版本不匹配
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ===== AndroidX 核心库 =====

    // activity-ktx：Activity 的 Kotlin 扩展（如 lifecycleScope 协程作用域）
    implementation(libs.androidx.activity.ktx)

    // appcompat：向下兼容的 Activity/Fragment 基类
    // 让低版本 Android 也能用高版本的 UI 特性
    implementation(libs.androidx.appcompat)

    // constraintlayout：约束布局（复杂布局用，我们 PoC 用 LinearLayout 就够了）
    implementation(libs.androidx.constraintlayout)

    // core-ktx：Android 核心库的 Kotlin 扩展
    implementation(libs.androidx.core.ktx)

    // fragment-ktx：Fragment 的 Kotlin 扩展（如 viewModels() 委托）
    implementation(libs.androidx.fragment.ktx)

    // ===== Material Design 组件 =====

    // material：Material 3 组件库（BottomNavigationView、Button、Card 等）
    implementation(libs.material)

    // ===== RecyclerView（消息列表必须） =====
    // RecyclerView：高性能列表控件
    // 它只渲染屏幕可见的 item，滑出屏幕的 item 会被回收复用
    // 类比：传送带上的商品——你只看到眼前几件，但传送带很长
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ===== Kotlin 协程（异步编程，LLM 流式生成必须） =====
    // kotlinx-coroutines-android：Kotlin 协程的 Android 支持
    // 提供 Dispatchers.Main（主线程调度器）和 Android 专用的协程工具
    // 类比：协程 = 轻量级线程，可以"暂停"等待结果而不阻塞其他任务
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ===== Gson（JSON 序列化，结构化提取 + 数据存储用） =====
    // Gson：Google 的 JSON 序列化/反序列化库
    // 用途1：把 Kotlin 对象转成 JSON 字符串（序列化）
    // 用途2：把 JSON 字符串转回 Kotlin 对象（反序列化）
    // 在我们项目中：结构化提取的 JSON 解析 + 模型配置加载 + 文档存储
    implementation("com.google.code.gson:gson:2.10.1")

    // ===== ONNX Runtime（嵌入模型推理，bge-small-zh-v1.5 用） =====
    // onnxruntime：微软的跨平台推理引擎
    // 在我们项目中：加载 bge-small-zh-v1.5 的 ONNX 模型，执行文本嵌入推理
    // 输入 token ids → 输出 512 维向量
    implementation(libs.onnxruntime)

    // ===== 纯 Kotlin 分词器（bge 模型分词用） =====
    // 不需要额外依赖——我们自己实现了一个轻量级的 BERT WordPiece 分词器
    // 能解析 tokenizer.json，处理中文字符和子词切分
    // 详见 util/SimpleTokenizer.kt

    // ===== Timber（日志库，原始 MNN 代码使用，比 Log.d 更方便） =====
    // Timber：Jake Wharton 开发的日志库
    // 优势：自动用类名做 TAG，支持格式化，release 版自动关闭日志
    // 在我们项目中：原始 MNN 代码中 LlmSession 等文件使用了 Timber
    implementation("com.jakewharton.timber:timber:5.0.1")

    // ===== 测试依赖 =====
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

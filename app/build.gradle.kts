plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.poc.ondevice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.poc.ondevice"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.junit4.AndroidJUnitRunner"

        // 只编译 arm64-v8a 架构（与 .so 文件对应）
        // 你的模拟器必须是 ARM64 架构才能运行
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    // 不压缩模型文件格式（MNN 需要直接读取，不能被 Android 压缩）
    androidResources {
        noCompress += listOf("mnn", "bin", "txt", "json", "onnx", "weight")
    }
}

// Kotlin 编译目标 JVM 版本，与 compileOptions 一致
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX 核心
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)

    // Material Design 组件（BottomNavigationView 等）
    implementation(libs.material)

    // RecyclerView（消息列表必须）
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Kotlin 协程（异步编程，LLM 流式生成必须）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Gson（JSON 序列化，结构化提取 + 数据存储用）
    implementation("com.google.code.gson:gson:2.10.1")

    // Timber（日志库，原始 MNN 代码使用，比 Log.d 更方便）
    implementation("com.jakewharton.timber:timber:5.0.1")

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

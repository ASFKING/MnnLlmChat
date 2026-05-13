# JNI Libraries

将以下 .so 文件放入此目录 (`arm64-v8a/`)：

## 必需文件（从 MnnLlmChat 项目复制）

| 文件 | 来源 | 作用 |
|---|---|---|
| libMNN.so | MNN 编译产出 | MNN 核心推理引擎 |
| libmnnllmapp.so | MnnLlmChat 编译产出 | MNN-LLM JNI 封装 |
| libsherpa-mnn-jni.so | Sherpa-MNN CDN | Sherpa ASR/TTS JNI |
| libmnn_tts.so | MNN 编译产出 | MNN TTS 引擎 |
| libc++_shared.so | NDK | C++ 标准库 |

## 复制命令（Windows）

```cmd
copy D:\Android\MnnLlmChat\app\src\main\jniLibs\arm64-v8a\libMNN.so .\
copy D:\Android\MnnLlmChat\app\src\main\jniLibs\arm64-v8a\libmnnllmapp.so .\
copy D:\Android\MnnLlmChat\app\src\main\jniLibs\arm64-v8a\libsherpa-mnn-jni.so .\
copy D:\Android\MnnLlmChat\app\src\main\jniLibs\arm64-v8a\libmnn_tts.so .\
copy D:\Android\MnnLlmChat\app\src\main\jniLibs\arm64-v8a\libc++_shared.so .\
```

## 注意

- 只需要上述 5 个核心文件，不需要 Firebase Crashlytics 等额外 .so
- 所有 .so 必须是 arm64-v8a 架构
- .so 文件不提交到 git（已在 .gitignore 中排除）

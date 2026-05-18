# Android 端侧 LLM PoC 完整技术设计方案（V4 · 基于 MnnLlmChat + Sherpa-MNN）

> **版本**：V4.0 · 2026-05-09
> **定位**：学习导向 + 可交付 PoC
> **核心原则**：每一步都知道"为什么这么做"，而不是"照着抄"
> **基线项目**：[alibaba/MNN — apps/Android/MnnLlmChat](https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat)
> **技术栈**：Kotlin + ViewBinding + XML 布局（贴近传统 Android 开发习惯）

---

## 目录

- [一、项目全景](#一项目全景)
- [二、技术架构](#二技术架构)
- [三、技术选型详解](#三技术选型详解)
- [四、项目结构设计](#四项目结构设计)
- [五、引擎层详细设计](#五引擎层详细设计)
- [六、数据层设计](#六数据层设计)
- [七、UI 层设计](#七ui-层设计)
- [八、构建与部署](#八构建与部署)
- [九、性能优化策略](#九性能优化策略)
- [十、验证指标](#十验证指标)
- [十一、详细开发计划](#十一详细开发计划)
- [十二、风险与应对](#十二风险与应对)
- [十三、从 PoC 到生产的路线图](#十三从-poc-到生产的路线图)
- [附录 A：依赖版本清单](#附录-a依赖版本清单)
- [附录 B：参考实测数据](#附录-b参考实测数据)
- [附录 C：术语表](#附录-c术语表)

---

## 一、项目全景

### 1.1 一句话定义

在 Android 手机上，用 MNN 推理框架 + Sherpa-MNN 跑通一个完整的端侧 LLM 应用，覆盖"模型加载 → 文本推理 → 文本嵌入 → 向量检索 → RAG 问答 → 结构化提取 → 多模态理解 → 语音识别（ASR）→ 语音合成（TTS）→ 语音对话"全链路，收集真实设备上的性能数据。

### 1.2 最终交付物

| 交付物 | 说明 |
|---|---|
| 可安装运行的 Android APK | 覆盖六个核心功能场景 |
| 性能测试报告 | TTFT、tok/s、内存峰值、端到端延迟、ASR/TTS 延迟 |
| PoC 总结文档 | 技术可行性结论 + 从 PoC 到生产的路线图 |
| 学习笔记 | 每个阶段的关键认知和踩坑记录 |

### 1.3 验证目标

| 验证项 | 具体指标 | 目标值 |
|---|---|---|
| 模型加载 | Qwen3-1.7B Q4_K_M 在 8GB 设备上加载成功 | 成功 |
| 推理速度 | 首 token 延迟 (TTFT) | < 2s |
| 推理速度 | 生成速度 (tok/s) | > 15 tok/s（MNN GPU 后端） |
| 内存占用 | 模型推理时峰值内存 | < 2 GB |
| JSON 输出 | 结构化提取 100 次，格式正确率 | > 90% |
| RAG 端到端 | 从提问到回答完整链路 | 延迟 < 8s |
| 多模态 | 图片 + 文本联合理解 | 可用 |
| ASR | 语音识别准确率（中文） | > 90% |
| ASR 延迟 | 3 秒语音识别耗时 | < 1s |
| TTS | 语音合成自然度 | 可接受 |
| TTS 延迟 | 一句话（20 字）合成耗时 | < 2s |
| 语音对话 | ASR → LLM → TTS 端到端 | 可用 |
| 离线运行 | 飞行模式下全部功能可用 | 100% |

### 1.4 PoC 边界

**做：**

- ✅ 验证 MNN-LLM 在 Android 上能跑通 Qwen 系列推理
- ✅ 验证 ONNX Runtime 加载 bge 模型做嵌入
- ✅ 验证纯 Kotlin 向量检索方案
- ✅ 验证六个核心功能模块的完整链路
- ✅ 验证 Sherpa-MNN 的 ASR 和 TTS 能力
- ✅ 验证 ASR → LLM → TTS 全链路语音对话
- ✅ 收集真实设备上的性能数据
- ✅ 理解每一层的技术原理

**不做：**

- ❌ 不做业务流程（扫码、检查、文书模板）
- ❌ 不做用户认证/权限管理
- ❌ 不做数据同步（仅本地）
- ❌ 不做 UI 美化（功能性 UI 即可）
- ❌ 不做模型微调
- ❌ 不做 NPU 厂商 SDK 适配（高通 QNN / 联发科 NeuroPilot）
- ❌ 不做生产级错误处理和边界情况覆盖
- ❌ 不做语音唤醒词（Wake Word）检测

---

## 二、技术架构

### 2.1 整体分层

```
┌─────────────────────────────────────────────────┐
│                   UI 层                          │
│  ViewBinding + XML 布局 · 5 个 Tab · Fragment    │
├─────────────────────────────────────────────────┤
│                  业务层                           │
│  结构化提取 · RAG 问答 · 文档生成 · 图片理解      │
│  语音识别 · 语音合成 · 语音对话                   │
├─────────────────────────────────────────────────┤
│                  引擎层                           │
│  LLMEngine · EmbeddingEngine · VectorStore       │
│  RAGEngine · VisionEngine · ASREngine · TTSEngine│
│  VoiceChatEngine                                 │
├─────────────────────────────────────────────────┤
│                 推理框架层                        │
│  MNN-LLM (JNI) · ONNX Runtime (Java API)        │
│  Sherpa-MNN (JNI)                                │
├─────────────────────────────────────────────────┤
│                 原生计算层                        │
│  libMNN.so · libmnnllmapp.so                     │
│  libsherpa-mnn-core.so · libsherpa-mnn-asr.so   │
│  libsherpa-mnn-tts.so                            │
│  CPU (ARM NEON/SDOT) · GPU (OpenCL)              │
├─────────────────────────────────────────────────┤
│                  数据层                           │
│  SharedPreferences + JSON 文件 · 文件系统         │
└─────────────────────────────────────────────────┘
```

### 2.2 数据流

#### 场景 A：LLM 直接推理（结构化提取 / 文档生成）

```
用户输入文字
    ↓
UI 层收集输入
    ↓
业务层构建 prompt（模板 + 用户输入）
    ↓
LLMEngine.generate(prompt)
    ↓
JNI 调用 → MNN 引擎 prefill（一次性处理 prompt）
    ↓
MNN 引擎 decode（逐 token 生成）
    ↓
GenerateProgressListener.onProgress(token) 回调
    ↓
Kotlin Flow 逐 token 推送到 UI
    ↓
UI 实时显示生成结果
```

#### 场景 B：RAG 问答

```
用户提问
    ↓
EmbeddingEngine.encode(问题) → 查询向量
    ↓
VectorStore.search(查询向量, topK=3) → 相关文档片段
    ↓
业务层组装 prompt = 系统指令 + 检索到的片段 + 用户问题
    ↓
LLMEngine.generate(组装后的 prompt)
    ↓
流式输出回答
```

#### 场景 C：多模态理解

```
用户选择图片 + 输入文字
    ↓
图片预处理（缩放、归一化）
    ↓
MNN 多模态接口（视觉编码器 + LLM 联合推理）
    ↓
流式输出描述
```

#### 场景 D：语音识别（ASR）

```
用户按住说话
    ↓
AudioRecorder 录制 PCM 音频（16kHz, 16bit, mono）
    ↓
音频数据送入 Sherpa-MNN SenseVoice 引擎
    ↓
流式返回识别文本
    ↓
UI 显示识别结果
```

#### 场景 E：语音合成（TTS）

```
LLM 生成的文字回答
    ↓
TTSEngine.synthesize(text) 或 synthesizeStream(text)
    ↓
返回 PCM 音频数据
    ↓
AudioPlayer 播放音频
    ↓
用户听到语音回答
```

#### 场景 F：语音对话（全链路串联）

```
用户按住说话
    ↓
AudioRecorder 录制语音
    ↓
ASREngine 流式识别 → 中间结果显示在 UI
    ↓
识别完成 → 获得最终文本
    ↓
LLMEngine.generateStream(text) → 流式显示文字回答
    ↓
回答完成 → TTSEngine.synthesizeStream(fullText)
    ↓
AudioPlayer 边接收边播放
    ↓
播放完成 → 等待下一轮语音输入
```

### 2.3 基于 MnnLlmChat 的裁剪策略

**为什么要基于 MnnLlmChat 而不是从零开始：**

| 维度 | 从零开始 | 基于 MnnLlmChat |
|---|---|---|
| JNI 接口 | 需要自己写 | 已经封装好 |
| CMake 配置 | 需要自己配 | 已验证 |
| 模型加载 | 需要自己处理 | 逻辑成熟 |
| Sherpa 集成 | 需要自己对接 | 已有完整集成 |
| 踩坑时间 | 3-5 天 | 0.5 天 |
| 学习深度 | 高但风险大 | 聚焦在业务层 |

**裁剪原则：**

- 保留推理引擎相关的所有代码（`llm/` 目录的核心接口）
- 保留 Sherpa ASR/TTS 集成代码（`com.k2fsa.sherpa.mnn` 包）
- 保留模型下载和配置解析逻辑
- 删除 3D Avatar、模型市场等非核心 UI
- 用 ViewBinding + XML 重写 UI（不用 Compose）
- 新增嵌入、向量检索、RAG 等业务层

---

## 三、技术选型详解

### 3.1 推理引擎：MNN-LLM

**MNN 是什么：**

MNN（Mobile Neural Network）是阿里巴巴开源的移动端深度学习推理框架。MNN-LLM 是其上层的 LLM 推理模块。

**MNN 的核心优化：**

| 优化 | 说明 | 效果 |
|---|---|---|
| ARM 汇编优化 | 手写 NEON/SDOT 指令做矩阵乘法 | 比通用 C++ 快 3-5x |
| 权重量化 | 4-bit / 8-bit 量化，减少内存和计算量 | 模型体积减 50-75% |
| 算子融合 | 把多个小操作合并成一个大操作 | 减少内存读写 |
| KV Cache 优化 | mmap 映射，减少内存峰值 | 支持更长上下文 |
| GPU 后端 | OpenCL 加速，自动降级到 CPU | decode 速度提升 50-100% |

**为什么选 MNN 而不是其他框架：**

| 框架 | 优势 | 劣势 | 适合场景 |
|---|---|---|---|
| **MNN** | 对 Qwen 系列优化好；接入难度低；阿里维护 | 社区相对小 | 本 PoC |
| llama.cpp | 社区大；模型支持广 | 接入需要自己写 JNI | 需要支持多种模型 |
| MediaPipe | Google 维护；接入最简单 | 模型选择受限 | 只跑 Gemma |
| ExecuTorch | PyTorch 官方；生态好 | 还在早期 | PyTorch 用户 |

### 3.2 ASR 引擎：Sherpa-MNN + SenseVoice

**Sherpa-MNN 是什么：**

Sherpa-MNN 是 k2-fsa 项目（新一代语音识别工具链）的 MNN 推理后端版本。它将语音识别和语音合成模型通过 MNN 引擎在端侧运行。

**SenseVoice 模型：**

| 属性 | 值 |
|---|---|
| 开发者 | 阿里达摩院 |
| 参数量 | ~200M |
| 模型大小 | ~200MB（MNN 格式） |
| 输入 | 16kHz PCM 音频 |
| 输出 | 中文/英文/日文/粤语文本 |
| 特点 | 支持情感识别、音频事件检测 |
| 流式支持 | 是（边说边识别） |

**为什么选 Sherpa-MNN 而不是其他方案：**

| 方案 | 优势 | 劣势 |
|---|---|---|
| **Sherpa-MNN** ✅ | MnnLlmChat 已集成；SenseVoice 中文效果极好；统一技术栈 | 模型需单独下载 |
| Whisper (ONNX) | 社区大；多语言好 | 需要自己写 JNI；实时性差 |
| MNN-LLM 内置 Qwen2-Audio | 理解能力最强 | 7B 模型太大（4GB+），不适合 8GB 设备 |
| Android SpeechRecognizer | 零依赖 | 需要联网；不支持离线 |

### 3.3 TTS 引擎：Sherpa-MNN + MeloTTS

**MeloTTS 模型：**

| 属性 | 值 |
|---|---|
| 开发者 | MyShell / k2-fsa 移植 |
| 模型大小 | ~150MB（MNN 格式） |
| 输出 | 16kHz PCM 音频 |
| 中文效果 | 自然度高，支持中英混合 |
| 多说话人 | 是（可切换男声/女声） |
| 流式支持 | 是（边合成边播放） |

**为什么选 Sherpa-MNN TTS 而不是其他方案：**

| 方案 | 优势 | 劣势 |
|---|---|---|
| **Sherpa-MNN TTS** ✅ | 与 ASR 同一库；音质自然；流式合成 | 模型 ~150MB |
| Android 系统 TTS | 零额外空间 | 音质机械；中文支持差 |
| Piper TTS | 轻量 | 中文效果一般 |
| Edge TTS | 音质最好 | 必须联网，不支持离线 |

### 3.4 文本嵌入：ONNX Runtime + bge-small-zh-v1.5

**bge-small-zh-v1.5 模型：**

| 属性 | 值 |
|---|---|
| 开发者 | BAAI（智源研究院） |
| 参数量 | ~33M |
| 输出维度 | 512 |
| 模型大小 | ~100MB（ONNX 格式） |
| 输入长度 | 最大 512 token |
| 用途 | 中文文本语义相似度、检索 |

**为什么用 ONNX Runtime 而不是 MNN：**

- bge 模型很小（33M 参数），不需要 MNN 的移动端优化
- ONNX Runtime 有官方 Java/Kotlin API，一行依赖引入
- bge 模型在 HuggingFace 上有现成 ONNX 格式，不需要转换
- MNN 主要优化 LLM 的 decode 路径，嵌入模型用 ONNX Runtime 更简单

### 3.5 向量检索：纯 Kotlin 暴力搜索

**为什么不用 FAISS：**

| 方案 | 优点 | 缺点 | 适合 |
|---|---|---|---|
| FAISS (C++) | 速度快，支持亿级数据 | 需要 NDK 编译，增加复杂度 | 生产环境 |
| **纯 Kotlin** | 零依赖，5 行代码 | 数据量大时慢 | PoC（< 1 万条） |

**暴力搜索的性能估算：**

```
假设：1 万条 512 维向量
点积计算：512 次乘法 + 512 次加法 ≈ 1024 FLOPS/条
总计算量：10000 × 1024 ≈ 10M FLOPS
骁龙 8 Gen2 的 CPU：~50 GFLOPS
理论耗时：10M / 50G ≈ 0.2ms
实际耗时（含内存访问）：< 5ms
```

结论：PoC 阶段暴力搜索完全够用。

### 3.6 模型选择

| 模型 | 参数量 | 量化后大小 | 用途 | 推荐设备 |
|---|---|---|---|---|
| **Qwen3-1.7B** | 1.7B | ~1.1GB | LLM 推理（PoC 主力） | 8GB+ RAM |
| **bge-small-zh-v1.5** | 33M | ~100MB | 文本嵌入 | 任意设备 |
| **Qwen2.5-VL-3B** | 3B | ~2.5GB | 多模态视觉 | 8GB+ RAM |
| **SenseVoice** | ~200M | ~200MB | 语音识别（ASR） | 任意设备 |
| **MeloTTS-zh** | — | ~150MB | 语音合成（TTS） | 任意设备 |

**模型资源汇总：**

| 模型 | 大小 | 用途 | 内存占用 |
|---|---|---|---|
| Qwen3-1.7B (Q4_K_M) | ~1.1GB | LLM 推理 | ~1.5GB |
| bge-small-zh-v1.5 | ~100MB | 嵌入 | ~150MB |
| Qwen2.5-VL-3B | ~2.5GB | 多模态 | ~2.5GB |
| SenseVoice | ~200MB | ASR | ~300MB |
| MeloTTS-zh | ~150MB | TTS | ~200MB |
| **合计** | **~4GB** | | **~4.6GB（峰值）** |

**内存管理策略**（8GB 设备不能同时加载所有模型）：

- 常驻：bge（150MB）+ SenseVoice（300MB）≈ 450MB
- 按需加载：LLM（1.5GB）/ 多模态（2.5GB）/ TTS（200MB）
- 用完释放，避免同时驻留

### 3.7 分词器方案

**bge 分词器处理：**

使用 `onnxruntime-extensions` 提供的 Java tokenizer binding，避免自己实现 BPE：

```gradle
implementation 'com.microsoft.onnxruntime:onnxruntime-extensions:0.12.0'
```

`onnxruntime-extensions` 内置了 HuggingFace tokenizers 的 C++ 实现，支持 BPE 分词，可直接加载 bge 的 `tokenizer.json`。

---

## 四、项目结构设计

### 4.1 目录结构

```
apps/Android/MnnLlmChat/（基于 MnnLlmChat 裁剪）
├── app/src/main/
│   ├── java/com/poc/ondevice/
│   │   ├── App.kt                              # Application 入口
│   │   ├── MainActivity.kt                     # 宿主 Activity + BottomNavigationView
│   │   │
│   │   ├── engine/                              # 引擎层（核心）
│   │   │   ├── LLMEngine.kt                    # MNN-LLM 推理封装
│   │   │   ├── EmbeddingEngine.kt              # ONNX Runtime 嵌入封装
│   │   │   ├── VectorStore.kt                  # 纯 Kotlin 向量检索
│   │   │   ├── RAGEngine.kt                    # RAG 引擎（组装）
│   │   │   ├── VisionEngine.kt                 # 多模态视觉推理
│   │   │   ├── ASREngine.kt                    # 语音识别（Sherpa-MNN）
│   │   │   ├── TTSEngine.kt                    # 语音合成（Sherpa-MNN）
│   │   │   ├── VoiceChatEngine.kt              # 语音对话串联
│   │   │   └── ModelManager.kt                 # 模型生命周期管理
│   │   │
│   │   ├── ui/                                  # UI 层
│   │   │   ├── fragment/
│   │   │   │   ├── HomeFragment.kt             # 首页（系统状态）
│   │   │   │   ├── ChatFragment.kt             # 文本对话 + 结构化提取
│   │   │   │   ├── RAGFragment.kt              # RAG 问答
│   │   │   │   ├── GenerateFragment.kt         # 文档生成
│   │   │   │   ├── VisionFragment.kt           # 图片理解
│   │   │   │   └── VoiceFragment.kt            # 语音对话
│   │   │   └── adapter/
│   │   │       └── ChatAdapter.kt              # 对话消息 RecyclerView Adapter
│   │   │
│   │   ├── util/
│   │   │   ├── AudioRecorder.kt                # 录音工具
│   │   │   ├── AudioPlayer.kt                  # 播放工具
│   │   │   ├── JsonUtils.kt                    # JSON 解析/校验
│   │   │   ├── MemoryMonitor.kt                # 内存监控
│   │   │   └── PerformanceTracker.kt           # 性能追踪
│   │   │
│   │   └── data/
│   │       ├── DocumentStore.kt                # 文档存储（JSON 文件）
│   │       └── ExtractionStore.kt              # 提取记录存储（SharedPreferences）
│   │
│   ├── java/com/alibaba/mnnllm/android/        # 保留的 MNN 原有代码
│   │   ├── MNN.kt                              # JNI 入口（保留）
│   │   ├── llm/                                # 推理核心（保留）
│   │   │   ├── ChatSession.kt
│   │   │   ├── ChatService.kt
│   │   │   ├── LlmService.kt
│   │   │   ├── LlmSession.kt
│   │   │   └── GenerateProgressListener.kt
│   │   └── model/                              # 模型配置（保留）
│   │
│   ├── java/com/k2fsa/sherpa/mnn/              # Sherpa ASR/TTS（保留）
│   │   ├── SherpaMnn.kt
│   │   ├── OnlineRecognizer.kt
│   │   └── OfflineTts.kt
│   │
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── llm_mnn_jni.cpp                     # LLM JNI（保留）
│   │   ├── llm_session.cpp/h                   # LLM 会话（保留）
│   │   ├── llm_stream_buffer.hpp               # 流式 buffer（保留）
│   │   ├── processor.cpp/h                     # 预处理（保留）
│   │   └── utf8_stream_processor.hpp           # UTF-8 流处理（保留）
│   │
│   ├── jniLibs/arm64-v8a/
│   │   ├── libmnn.so                           # MNN 核心
│   │   ├── libmnnllmapp.so                     # MNN-LLM JNI
│   │   ├── libsherpa-mnn-core.so               # Sherpa 核心
│   │   ├── libsherpa-mnn-asr.so                # Sherpa ASR
│   │   └── libsherpa-mnn-tts.so                # Sherpa TTS
│   │
│   ├── assets/
│   │   └── models/                             # 模型存放目录
│   │       └── README.md                       # 模型下载说明
│   │
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml               # 主布局（BottomNav + FragmentContainer）
│       │   ├── fragment_home.xml               # 系统状态页
│       │   ├── fragment_chat.xml               # 文本对话页
│       │   ├── fragment_rag.xml                # RAG 问答页
│       │   ├── fragment_generate.xml           # 文档生成页
│       │   ├── fragment_vision.xml             # 图片理解页
│       │   ├── fragment_voice.xml              # 语音对话页
│       │   └── item_chat_message.xml           # 对话消息 item
│       ├── menu/
│       │   └── bottom_nav_menu.xml             # 底部导航菜单
│       ├── values/
│       │   ├── strings.xml
│       │   ├── colors.xml
│       │   └── themes.xml                      # Material 3 主题
│       └── drawable/
│           └── ...
│
├── build.gradle
├── settings.gradle
└── gradle.properties
```

### 4.2 保留 vs 删除 vs 新增 清单

#### 保留（原封不动）

| 文件 | 作用 | 为什么保留 |
|---|---|---|
| `MNN.kt` | JNI 入口，加载 `mnnllmapp.so` | 所有 MNN 调用的基础 |
| `llm/ChatSession.kt` | 会话接口定义 | LLM 调用的契约 |
| `llm/ChatService.kt` | 会话工厂 | 创建推理会话 |
| `llm/LlmService.kt` | LLM 封装（Flow 接口） | 流式生成的核心 |
| `llm/LlmSession.kt` | 会话实现（JNI 调用） | 实际调 native 方法 |
| `llm/GenerateProgressListener.kt` | 流式回调接口 | 逐 token 推送 |
| `model/` | 模型配置解析 | config.json 解析 |
| `com.k2fsa.sherpa.mnn/` | Sherpa ASR/TTS Java binding | 语音能力的基础 |

#### 删除

| 文件/目录 | 原因 |
|---|---|
| `asr/` (原 MnnLlmChat 的) | 用 Sherpa 替代 |
| `audio/` (原音频处理) | 用新的 AudioRecorder/Player 替代 |
| `benchmark/` | 自己写简单的性能监控 |
| `debug/` | 调试工具 |
| `history/` | 历史记录 UI |
| `main/` | 原主界面，用 Fragment 重写 |
| `mainsettings/` | 设置页 |
| `modelist/` | 模型列表 UI |
| `modelmarket/` | 模型市场 |
| `modelsettings/` | 模型设置 |
| `privacy/` | 隐私协议 |
| `qnn/` | 高通 NPU |
| `tag/` | 标签 |
| `update/` | 版本更新 |
| `widgets/` | 自定义组件 |
| `llm/DiffusionSession.kt` | 图像生成 |
| `llm/SanaSession.kt` | Sana 编辑 |

#### 新增

| 文件 | 作用 |
|---|---|
| `engine/LLMEngine.kt` | 对 LlmService 的二次封装 |
| `engine/EmbeddingEngine.kt` | ONNX Runtime 嵌入封装 |
| `engine/VectorStore.kt` | 纯 Kotlin 向量库 |
| `engine/RAGEngine.kt` | RAG 组装 |
| `engine/VisionEngine.kt` | 多模态视觉推理 |
| `engine/ASREngine.kt` | 语音识别封装 |
| `engine/TTSEngine.kt` | 语音合成封装 |
| `engine/VoiceChatEngine.kt` | 语音对话串联 |
| `engine/ModelManager.kt` | 统一模型生命周期 |
| `ui/fragment/*.kt` | 5 个 Fragment 页面（HomeFragment 含性能测试） |
| `ui/adapter/ChatAdapter.kt` | 对话消息适配器 |
| `util/AudioRecorder.kt` | 录音工具 |
| `util/AudioPlayer.kt` | 播放工具 |
| `util/JsonUtils.kt` | JSON 工具 |
| `util/MemoryMonitor.kt` | 内存监控 |
| `util/PerformanceTracker.kt` | 性能追踪 |
| `data/DocumentStore.kt` | 文档存储 |
| `data/ExtractionStore.kt` | 提取记录存储 |

---

## 五、引擎层详细设计

### 5.1 LLM Engine

**职责：**

- 封装 MNN-LLM 的推理能力
- 提供协程友好的异步接口
- 流式输出通过 Kotlin Flow 暴露
- 线程安全（同一时刻只允许一个推理任务）
- 提供结构化提取的便捷方法

**接口设计：**

```kotlin
class LLMEngine {
    private var chatSession: ChatSession? = null
    private val mutex = Mutex()
    
    val isLoaded: Boolean get() = chatSession != null
    
    // 加载模型
    suspend fun load(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        chatSession = ChatService.provide().createLlmSession(
            modelId = "poc_model",
            configPath = "$modelDir/config.json",
            sessionId = "poc_session",
            extraConfig = null,
            keepHistory = false
        )
        chatSession?.load() ?: false
    }
    
    // 流式生成
    fun generateStream(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.3f
    ): Flow<String> = channelFlow {
        mutex.withLock {
            chatSession?.generate(prompt, emptyMap(), object : GenerateProgressListener {
                override fun onProgress(token: String?): Boolean {
                    token?.let { trySend(it) }
                    return false // false = 继续生成
                }
            })
        }
    }
    
    // 一次性生成
    suspend fun generate(prompt: String, maxTokens: Int = 512): String {
        val sb = StringBuilder()
        generateStream(prompt, maxTokens).collect { sb.append(it) }
        return sb.toString()
    }
    
    // 结构化提取
    suspend fun structuredExtract(text: String, schemaHint: String = ""): String {
        val prompt = buildExtractPrompt(text, schemaHint)
        return generate(prompt, maxTokens = 256, temperature = 0.1f)
    }
    
    // 重置会话（清除 KV Cache）
    fun resetSession() { chatSession?.reset() }
    
    // 释放资源
    fun release() {
        chatSession?.release()
        chatSession = null
    }
}
```

**关键设计决策：**

| 决策 | 选择 | 理由 |
|---|---|---|
| 推理线程 | `Dispatchers.Default` | CPU 密集型，不阻塞 UI |
| 线程安全 | `Mutex` 保护 | 同一时刻只有一个推理任务 |
| 流式接口 | `channelFlow` | MNN 是回调式 API，需要桥接为 Flow |
| 温度参数 | 结构化提取用 0.1，对话用 0.3 | 低温度 = 更确定的输出 |

### 5.2 ASR Engine

**职责：**

- 封装 Sherpa-MNN 的语音识别能力
- 支持非流式识别（录完再识别）
- 支持流式识别（边说边识别）

**接口设计：**

```kotlin
class ASREngine {
    private var recognizer: OnlineRecognizer? = null
    
    val isLoaded: Boolean get() = recognizer != null
    
    // 加载模型
    suspend fun load(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        val config = OnlineRecognizerConfig(
            modelPath = "$modelDir/model.mnn",
            tokensPath = "$modelDir/tokens.txt",
            numThreads = 4
        )
        recognizer = OnlineRecognizer(config)
        recognizer != null
    }
    
    // 非流式识别：传入完整音频数据
    suspend fun recognize(audioData: FloatArray): String = withContext(Dispatchers.Default) {
        recognizer?.let { rec ->
            val stream = rec.createStream()
            stream.acceptWaveform(audioData, sampleRate = 16000)
            stream.inputFinished()
            
            val result = StringBuilder()
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }
            result.append(rec.getResult(stream).text)
            stream.release()
            result.toString()
        } ?: ""
    }
    
    // 流式识别：边说边识别
    fun recognizeStream(
        audioFlow: Flow<FloatArray>,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            recognizer?.let { rec ->
                val stream = rec.createStream()
                var lastText = ""
                
                audioFlow.collect { audioChunk ->
                    stream.acceptWaveform(audioChunk, sampleRate = 16000)
                    
                    if (rec.isReady(stream)) {
                        rec.decode(stream)
                        val currentText = rec.getResult(stream).text
                        if (currentText != lastText) {
                            onPartialResult(currentText)
                            lastText = currentText
                        }
                    }
                }
                
                stream.inputFinished()
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }
                onFinalResult(rec.getResult(stream).text)
                stream.release()
            }
        }
    }
    
    fun release() {
        recognizer?.release()
        recognizer = null
    }
}
```

**AudioRecorder（配合 ASR 使用）：**

```kotlin
class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private val sampleRate = 16000
    
    // 返回音频数据流
    fun startRecording(): Flow<FloatArray> = flow {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord?.startRecording()
        
        val buffer = ShortArray(bufferSize / 2)
        while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                // Short → Float 归一化
                val floatBuffer = FloatArray(read) { buffer[it].toFloat() / 32768f }
                emit(floatBuffer)
            }
        }
    }.flowOn(Dispatchers.IO)
    
    fun stopRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
```

### 5.3 TTS Engine

**职责：**

- 封装 Sherpa-MNN 的语音合成能力
- 支持一次性合成和流式合成

**接口设计：**

```kotlin
class TTSEngine {
    private var tts: OfflineTts? = null
    private var speakerId: Int = 0
    
    val isLoaded: Boolean get() = tts != null
    
    // 加载模型
    suspend fun load(modelDir: String, speakerId: Int = 0): Boolean =
        withContext(Dispatchers.IO) {
            this@TTSEngine.speakerId = speakerId
            val config = OfflineTtsConfig(
                modelPath = "$modelDir/model.mnn",
                tokensPath = "$modelDir/tokens.txt",
                numThreads = 4
            )
            tts = OfflineTts(config)
            tts != null
        }
    
    // 一次性合成：返回完整 PCM 数据
    suspend fun synthesize(text: String, speed: Float = 1.0f): FloatArray =
        withContext(Dispatchers.Default) {
            tts?.generate(text, sid = speakerId, speed = speed)?.samples ?: FloatArray(0)
        }
    
    // 流式合成：分段回调音频数据
    fun synthesizeStream(
        text: String,
        speed: Float = 1.0f,
        onChunk: (FloatArray) -> Unit,
        onComplete: () -> Unit
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            tts?.generateWithCallback(
                text, sid = speakerId, speed = speed
            ) { samples, _ ->
                onChunk(samples)
                false // false = 继续合成
            }
            onComplete()
        }
    }
    
    fun release() {
        tts?.release()
        tts = null
    }
}
```

### 5.4 VoiceChatEngine（串联 ASR → LLM → TTS）

**职责：**

- 串联 ASR、LLM、TTS 三个引擎
- 管理语音对话的完整流程

**接口设计：**

```kotlin
class VoiceChatEngine(
    private val asr: ASREngine,
    private val llm: LLMEngine,
    private val tts: TTSEngine,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer
) {
    // 完整语音对话流程
    fun startVoiceChat(
        onAsrPartial: (String) -> Unit,        // ASR 中间结果
        onAsrFinal: (String) -> Unit,           // ASR 最终结果
        onLlmToken: (String) -> Unit,           // LLM 流式 token
        onTtsStart: () -> Unit,                 // TTS 开始播放
        onTtsEnd: () -> Unit,                   // TTS 播放结束
        onComplete: () -> Unit
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            // Step 1: 录音 + ASR 识别
            val audioFlow = audioRecorder.startRecording()
            var finalText = ""
            
            asr.recognizeStream(
                audioFlow = audioFlow,
                onPartialResult = { onAsrPartial(it) },
                onFinalResult = {
                    finalText = it
                    onAsrFinal(it)
                    audioRecorder.stopRecording()
                }
            )
            
            // Step 2: LLM 推理
            val llmResponse = StringBuilder()
            llm.generateStream(finalText).collect { token ->
                llmResponse.append(token)
                onLlmToken(token)
            }
            
            // Step 3: TTS 合成 + 播放
            onTtsStart()
            tts.synthesizeStream(
                text = llmResponse.toString(),
                onChunk = { audioPlayer.play(it) },
                onComplete = {
                    onTtsEnd()
                    onComplete()
                }
            )
        }
    }
    
    // 停止当前对话
    fun stop() {
        audioRecorder.stopRecording()
        audioPlayer.stop()
    }
}
```

### 5.5 Embedding Engine

**职责：**

- 加载 ONNX 格式的 bge 模型
- 文本 → 分词 → 推理 → 归一化向量
- 支持单条和批量编码

**接口设计：**

```kotlin
class EmbeddingEngine {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: com.microsoft.onnxruntime.extensions.OrtTokenizer? = null
    
    val isLoaded: Boolean get() = session != null
    val embeddingDim: Int = 512
    
    suspend fun load(modelPath: String, tokenizerPath: String): Boolean =
        withContext(Dispatchers.IO) {
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(modelPath)
            // onnxruntime-extensions 加载 tokenizer
            tokenizer = OrtTokenizer(tokenizerPath)
            session != null
        }
    
    suspend fun encode(text: String): FloatArray = withContext(Dispatchers.Default) {
        val inputIds = tokenizer!!.encode(text)
        val attentionMask = LongArray(inputIds.size) { 1L }
        
        // 构造 ONNX 输入
        val inputTensor = OnnxTensor.createTensor(
            env!!, arrayOf(inputIds)
        )
        val maskTensor = OnnxTensor.createTensor(
            env!!, arrayOf(attentionMask)
        )
        
        val results = session!!.run(
            mapOf("input_ids" to inputTensor, "attention_mask" to maskTensor)
        )
        
        // 取 [CLS] 位置（第一个 token）的输出
        val output = results[0].value as Array<FloatArray>
        val clsVector = output[0]
        
        // L2 归一化
        l2Normalize(clsVector)
    }
    
    private fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(vector.size) { vector[it] / norm }
    }
    
    fun release() {
        session?.close()
        env?.close()
    }
}
```

### 5.6 Vector Store

**职责：**

- 内存中维护向量列表
- 提供余弦相似度搜索
- 支持元数据（文档标题、分块索引等）

```kotlin
class VectorStore {
    private val vectors = mutableListOf<FloatArray>()
    private val texts = mutableListOf<String>()
    private val metadata = mutableListOf<Map<String, String>>()
    
    val size: Int get() = vectors.size
    
    fun add(embedding: FloatArray, text: String, meta: Map<String, String> = emptyMap()) {
        vectors.add(embedding)
        texts.add(text)
        metadata.add(meta)
    }
    
    fun search(query: FloatArray, topK: Int = 3): List<SearchResult> {
        return vectors.mapIndexed { index, vec ->
            val score = dotProduct(query, vec) // 已归一化，点积 = 余弦相似度
            SearchResult(texts[index], score, metadata[index])
        }
        .sortedByDescending { it.score }
        .take(topK)
    }
    
    fun clear() {
        vectors.clear()
        texts.clear()
        metadata.clear()
    }
    
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
    
    data class SearchResult(
        val text: String,
        val score: Float,
        val metadata: Map<String, String>
    )
}
```

### 5.7 RAG Engine

**职责：**

- 组装完整的 RAG 流程
- 文档索引（分块 → 向量化 → 存储）
- RAG 问答（检索 → 上下文组装 → LLM 生成）

```kotlin
class RAGEngine(
    private val embeddingEngine: EmbeddingEngine,
    private val vectorStore: VectorStore,
    private val llmEngine: LLMEngine
) {
    // 文档索引
    suspend fun indexDocument(title: String, content: String): Int {
        val chunks = splitChunks(content)
        chunks.forEachIndexed { index, chunk ->
            val embedding = embeddingEngine.encode(chunk)
            vectorStore.add(
                embedding = embedding,
                text = chunk,
                metadata = mapOf("title" to title, "chunkIndex" to index.toString())
            )
        }
        return chunks.size
    }
    
    // 文档分块（带重叠）
    private fun splitChunks(
        content: String,
        chunkSize: Int = 500,
        overlap: Int = 100
    ): List<String> {
        val paragraphs = content.split("\n\n").filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        
        for (para in paragraphs) {
            if (para.length <= chunkSize) {
                chunks.add(para)
            } else {
                // 按句号断开
                val sentences = para.split("。", "！", "？", ".", "!", "?")
                var current = ""
                for (sent in sentences) {
                    if (current.length + sent.length > chunkSize && current.isNotBlank()) {
                        chunks.add(current)
                        // 保留 overlap
                        current = current.takeLast(overlap) + sent
                    } else {
                        current += sent
                    }
                }
                if (current.isNotBlank()) chunks.add(current)
            }
        }
        return chunks.filter { it.length >= 50 }
    }
    
    // RAG 问答
    suspend fun ask(question: String): Flow<String> {
        val queryEmbedding = embeddingEngine.encode(question)
        val results = vectorStore.search(queryEmbedding, topK = 3)
            .filter { it.score > 0.3f }
        
        val context = results.mapIndexed { index, result ->
            "【参考${index + 1}】(相关度: ${"%.2f".format(result.score)}) ${result.text}"
        }.joinToString("\n\n")
        
        val prompt = """你是知识问答助手。请根据以下参考文档回答问题。
        
$context

问题：$question

要求：仅基于参考文档回答，如果参考文档中没有相关信息，请明确说明"参考文档中未找到相关信息"。标注来源。"""
        
        return llmEngine.generateStream(prompt)
    }
}
```

### 5.8 Model Manager

**职责：**

- 统一管理所有模型的生命周期
- 按需加载，空闲释放
- 低内存时自动释放大模型

**模型生命周期策略：**

| 模型 | 加载时机 | 释放时机 | 内存占用 |
|---|---|---|---|
| bge 嵌入模型 | App 启动时 | App 退出时 | ~150MB |
| SenseVoice ASR | App 启动时 | App 退出时 | ~300MB |
| MeloTTS TTS | 首次语音合成时 | 空闲 5 分钟后 | ~200MB |
| Qwen3 LLM | 首次需要推理时 | 空闲 5 分钟后 / 低内存时 | ~1.5GB |
| Qwen2.5-VL | 用户打开图片理解时 | 切换到其他功能时 | ~2.5GB |

**内存管理流程：**

```
系统回调 onTrimMemory(TRIM_MEMORY_RUNNING_LOW)
    ↓
ModelManager 检查当前状态
    ↓
如果 LLM 正在推理 → 等推理完成再释放
如果 LLM 空闲 → 立即释放 LLM（节省 ~1.5GB）
    ↓
如果多模态已加载 → 释放（节省 ~2.5GB）
保留 bge + SenseVoice（常驻，只占 ~450MB）
    ↓
下次需要时重新加载
```

---

## 六、数据层设计

### 6.1 存储方案

PoC 阶段不引入 Room，使用 SharedPreferences + JSON 文件：

| 数据类型 | 存储方式 | 说明 |
|---|---|---|
| 文档原文 | JSON 文件 | `/data/data/包名/files/docs/{id}.json` |
| 文档分块 | JSON 文件 | `/data/data/包名/files/docs/{id}_chunks.json` |
| 提取记录 | SharedPreferences | key: `extractions`, value: JSON 数组 |
| 生成记录 | SharedPreferences | key: `generations`, value: JSON 数组 |
| 向量数据 | 纯内存 | App 重启后从文档重新生成 |
| 系统设置 | SharedPreferences | 线程数、温度等 |

### 6.2 数据模型

```kotlin
// 文档
data class DocumentData(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val chunkCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

// 提取记录
data class ExtractionRecord(
    val id: String = UUID.randomUUID().toString(),
    val inputText: String,
    val outputJson: String,
    val schemaName: String = "default",
    val createdAt: Long = System.currentTimeMillis()
)

// 生成记录
data class GenerationRecord(
    val id: String = UUID.randomUUID().toString(),
    val docType: String,
    val inputData: String,
    val outputContent: String,
    val createdAt: Long = System.currentTimeMillis()
)
```

### 6.3 DocumentStore

```kotlin
class DocumentStore(private val context: Context) {
    private val docsDir = File(context.filesDir, "docs").apply { mkdirs() }
    
    fun save(doc: DocumentData) {
        val file = File(docsDir, "${doc.id}.json")
        file.writeText(Gson().toJson(doc))
    }
    
    fun load(id: String): DocumentData? {
        val file = File(docsDir, "$id.json")
        return if (file.exists()) {
            Gson().fromJson(file.readText(), DocumentData::class.java)
        } else null
    }
    
    fun loadAll(): List<DocumentData> {
        return docsDir.listFiles()?.filter { it.name.endsWith(".json") && !it.name.contains("_chunks") }
            ?.map { Gson().fromJson(it.readText(), DocumentData::class.java) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }
    
    fun delete(id: String) {
        File(docsDir, "$id.json").delete()
        File(docsDir, "${id}_chunks.json").delete()
    }
}
```

---

## 七、UI 层设计

### 7.1 页面结构

```
底部导航栏（5 个 Tab）
├── 系统状态（HomeFragment）
│   └── 显示：各模型加载状态、内存使用、向量库大小、性能数据 + 性能测试入口
├── 文本对话（ChatFragment）
│   └── 多轮对话 + 结构化提取 + 文档生成
├── RAG 问答（RAGFragment）
│   └── 文档上传 + 知识库管理 + 对话问答
├── 图片理解（VisionFragment）
│   └── 图片选择器 + 文字输入 + 结果展示
└── 语音对话（VoiceFragment）
    └── 按住说话 + ASR 识别 + LLM 回答 + TTS 播放
```

### 7.2 主布局

```xml
<!-- activity_main.xml -->
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <FrameLayout
        android:id="@+id/fragment_container"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottom_nav"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:menu="@menu/bottom_nav_menu" />
</LinearLayout>
```

```xml
<!-- menu/bottom_nav_menu.xml -->
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@+id/nav_home" android:icon="@android:drawable/ic_menu_info_details" android:title="状态" />
    <item android:id="@+id/nav_chat" android:icon="@android:drawable/ic_menu_edit" android:title="对话" />
    <item android:id="@+id/nav_rag" android:icon="@android:drawable/ic_menu_search" android:title="RAG" />
    <item android:id="@+id/nav_vision" android:icon="@android:drawable/ic_menu_gallery" android:title="视觉" />
    <item android:id="@+id/nav_voice" android:icon="@android:drawable/ic_btn_speak_now" android:title="语音" />
</menu>
```

### 7.3 MainActivity

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 默认显示首页
        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_chat -> ChatFragment()
                R.id.nav_rag -> RAGFragment()
                R.id.nav_vision -> VisionFragment()
                R.id.nav_voice -> VoiceFragment()
                else -> HomeFragment()
            }
            loadFragment(fragment)
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
```

### 7.4 各页面布局要点

#### HomeFragment（系统状态）

```xml
<!-- fragment_home.xml -->
<ScrollView ...>
    <LinearLayout android:orientation="vertical" android:padding="16dp">
        
        <TextView android:text="📦 模型状态" android:textAppearance="?attr/textAppearanceTitleMedium" />
        <TextView android:id="@+id/tv_llm_status" android:text="LLM: ⬜ 未加载" />
        <TextView android:id="@+id/tv_embedding_status" android:text="嵌入模型: ⬜ 未加载" />
        <TextView android:id="@+id/tv_asr_status" android:text="ASR: ⬜ 未加载" />
        <TextView android:id="@+id/tv_tts_status" android:text="TTS: ⬜ 未加载" />
        <TextView android:id="@+id/tv_vision_status" android:text="多模态: ⬜ 未加载" />
        
        <View android:layout_height="1dp" android:background="?android:attr/listDivider" />
        
        <TextView android:text="💾 内存使用" android:textAppearance="?attr/textAppearanceTitleMedium" />
        <TextView android:id="@+id/tv_memory_info" />
        
        <View android:layout_height="1dp" android:background="?android:attr/listDivider" />
        
        <TextView android:text="📊 向量库" android:textAppearance="?attr/textAppearanceTitleMedium" />
        <TextView android:id="@+id/tv_vector_info" />
        
        <View android:layout_height="1dp" android:background="?android:attr/listDivider" />
        
        <TextView android:text="⚡ 性能数据" android:textAppearance="?attr/textAppearanceTitleMedium" />
        <TextView android:id="@+id/tv_perf_info" />
        
    </LinearLayout>
</ScrollView>
```

#### VoiceFragment（语音对话）

```xml
<!-- fragment_voice.xml -->
<LinearLayout android:orientation="vertical" android:padding="16dp">
    
    <!-- ASR 识别结果 -->
    <TextView android:text="🎤 语音识别" android:textAppearance="?attr/textAppearanceTitleMedium" />
    <TextView android:id="@+id/tv_asr_result"
        android:minHeight="60dp"
        android:background="@drawable/bg_rounded_edit"
        android:padding="12dp"
        android:text="等待语音输入..." />
    
    <!-- LLM 回答 -->
    <TextView android:text="🤖 AI 回答" android:textAppearance="?attr/textAppearanceTitleMedium" />
    <ScrollView android:layout_weight="1">
        <TextView android:id="@+id/tv_llm_response"
            android:minHeight="100dp"
            android:padding="12dp" />
    </ScrollView>
    
    <!-- 控制按钮 -->
    <LinearLayout android:gravity="center" android:padding="16dp">
        <Button android:id="@+id/btn_talk"
            android:text="按住说话"
            android:textSize="18sp" />
        <Button android:id="@+id/btn_stop"
            android:text="停止"
            android:enabled="false" />
    </LinearLayout>
    
    <!-- TTS 状态 -->
    <TextView android:id="@+id/tv_tts_status"
        android:text="🔇"
        android:gravity="center" />
</LinearLayout>
```

---

## 八、构建与部署

### 8.1 使用预编译 .so（推荐先用这个跑通）

**步骤：**

1. 从 MNN GitHub Releases 下载预编译的 Android .so
2. 从 Sherpa-MNN GitHub Releases 下载 Sherpa 预编译库
3. 放到 `app/src/main/jniLibs/arm64-v8a/`
4. build.gradle 配置：

```gradle
android {
    defaultConfig {
        ndk {
            abiFilters "arm64-v8a"
        }
    }
    // 不需要 externalNativeBuild，因为 .so 是预编译的
}
```

**好处：**
- Day 1 从 6-7 小时缩短到 1-2 小时
- 避免 NDK/CMake 版本不匹配的坑
- 先验证业务逻辑，再优化底层

### 8.2 自行编译 MNN 引擎（可选，需要深度定制时）

```
编译目标：生成 libmnnllmapp.so

编译参数：
  -DMNN_LOW_MEMORY=true              # 低内存模式
  -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true # CPU 权重反量化加速
  -DMNN_BUILD_LLM=true              # 启用 LLM 推理
  -DMNN_SUPPORT_TRANSFORMER_FUSE=true # Transformer 算子融合
  -DMNN_ARM82=true                   # ARMv8.2 FP16 加速
  -DMNN_OPENCL=true                  # OpenCL GPU 后端
  -DMNN_USE_LOGCAT=true              # Android 日志输出
  -DMNN_SEP_BUILD=OFF                # 不分离构建

ABI：arm64-v8a
NDK 版本：27.2.12479018
```

### 8.3 App 构建配置

```
compileSdk = 35
minSdk = 26（Android 8.0）
targetSdk = 35（Android 15）
Kotlin = 1.9.x
NDK = 27.2.12479018
```

### 8.4 模型部署

#### 开发阶段

```
方式：adb push 手动推送到设备

模型目录：/data/data/com.poc.ondevice/files/models/
├── qwen3-1.7b/
│   ├── config.json
│   ├── llm.mnn
│   ├── llm.mnn.weight
│   └── tokenizer.txt
├── bge-small-zh-v1.5/
│   ├── model.onnx
│   └── tokenizer.json
├── sensevoice/
│   ├── model.mnn
│   └── tokens.txt
├── melotts-zh/
│   ├── model.mnn
│   └── tokens.txt
└── qwen2.5-vl-3b/          （可选，多模态）
    ├── config.json
    └── ...

推送命令：
  adb push qwen3-1.7b/ /data/data/com.poc.ondevice/files/models/qwen3-1.7b/
  adb push bge-small-zh-v1.5/ /data/data/com.poc.ondevice/files/models/bge-small-zh-v1.5/
  adb push sensevoice/ /data/data/com.poc.ondevice/files/models/sensevoice/
  adb push melotts-zh/ /data/data/com.poc.ondevice/files/models/melotts-zh/
```

---

## 九、性能优化策略

### 9.1 内存管理

| 策略 | 说明 | 节省量 |
|---|---|---|
| LLM 按需加载 | 只在推理时加载，空闲释放 | ~1.5GB |
| 多模态按需加载 | 只在图片理解时加载 | ~2.5GB |
| TTS 按需加载 | 首次合成时加载 | ~200MB |
| mmap 加载权重 | MNN 支持，减少内存拷贝 | ~30% 峰值 |
| 低内存自动释放 | 监听系统回调 | 避免 OOM |
| bge + SenseVoice 常驻 | 两者合计 ~450MB，常驻合理 | - |

### 9.2 推理速度

| 策略 | 说明 | 预期提升 |
|---|---|---|
| OpenCL GPU 后端 | 矩阵运算用 GPU | decode +50-100% |
| ARMv8.2 FP16 | 半精度浮点计算 | prefill +50% |
| 线程数调优 | 核心数 - 2，上限 4 | 避免 UI 卡顿 |
| KV Cache 复用 | 多轮对话不重新 prefill | 第二轮起快 3-5x |

### 9.3 ASR/TTS 优化

| 策略 | 说明 |
|---|---|
| ASR 流式识别 | 边说边识别，不等录音结束 |
| TTS 流式合成 | 边合成边播放，减少首字延迟 |
| ASR/TTS 与 LLM 并行 | TTS 可以在 LLM 生成完整句子后立即开始合成 |

---

## 十、验证指标

| 验证项 | 具体指标 | 目标值 | 测试方法 |
|---|---|---|---|
| 模型加载 | Qwen3-1.7B Q4 在 8GB 设备上加载 | 成功 | `load()` 返回 true |
| 推理速度 | 首 token 延迟 (TTFT) | < 2s | 记录 prefill 完成时间 |
| 推理速度 | 生成速度 (tok/s) | > 15 tok/s | 计数 token / 耗时 |
| 内存占用 | 推理时峰值内存 | < 2GB | MemoryMonitor 采集 |
| JSON 输出 | 结构化提取 100 次正确率 | > 90% | 统计 JSON 解析成功率 |
| RAG 端到端 | 提问到回答延迟 | < 8s | 计时器 |
| 嵌入质量 | 相似文本相似度 | > 0.8 | 自动化测试 |
| 嵌入质量 | 不相似文本相似度 | < 0.3 | 自动化测试 |
| 多模态 | 图片 + 文本理解 | 可用 | 人工验证 |
| ASR 准确率 | 中文语音识别 | > 90% | 10 句测试 |
| ASR 延迟 | 3 秒语音识别 | < 1s | 计时器 |
| TTS 自然度 | 合成语音 | 可接受 | 人工试听 |
| TTS 延迟 | 20 字合成 | < 2s | 计时器 |
| 语音对话 | ASR → LLM → TTS 全链路 | 可用 | 端到端测试 |
| 离线运行 | 飞行模式下全部功能 | 100% | 关闭网络测试 |

---

## 十一、详细开发计划

### 总览（9 天）

```
Day 0  环境准备 + Kotlin 基础
Day 1  用预编译 .so 跑通 MnnLlmChat + 裁剪项目
Day 2  ViewBinding + XML 搭 UI 骨架（5 个 Tab）
Day 3  嵌入模型集成（ONNX Runtime + bge）
Day 4  向量检索 + RAG
Day 5  结构化提取 + 文档生成
Day 6  多模态（图片理解）
Day 7  ASR 集成（Sherpa-MNN + SenseVoice）
Day 8  TTS 集成（Sherpa-MNN + MeloTTS）+ 语音对话串联
Day 9  联调 + 性能测试 + PoC 总结
```

---

### Day 0：环境准备 + Kotlin 基础

**目标：** 搭建好开发环境，掌握 Kotlin 基础语法。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 0.1 | 安装 Android Studio（最新稳定版） | 环境 | 0.5h | IDE 就绪 | 能创建空项目并编译 |
| 0.2 | 安装 NDK 27.2.12479018 | 环境 | 0.5h | NDK 就绪 | `$ANDROID_NDK/ndk-build` 能执行 |
| 0.3 | 安装 CMake 3.22.1+ | 环境 | 0.5h | CMake 就绪 | `cmake --version` 正确 |
| 0.4 | 安装 Git + Python 3.8+ | 环境 | 0.5h | 工具就绪 | 版本命令正常输出 |
| 0.5 | 克隆 MNN 源码 | 环境 | 0.5h | 源码就绪 | 目录结构完整 |
| 0.6 | 准备测试手机（8GB+ RAM，ARM64） | 硬件 | 0.5h | 设备就绪 | `adb devices` 能识别 |
| 0.7 | 学习 Kotlin 基础语法 | 学习 | 3h | Kotlin 入门 | 能写 data class、扩展函数、协程基础 |

**Day 0 总耗时：** 约 6 小时

**Day 0 完成标志：** 所有工具安装完毕，MNN 源码克隆，手机 adb 连接，能写基础 Kotlin 代码。

---

### Day 1：跑通 MnnLlmChat + 裁剪项目

**目标：** 用预编译 .so 快速跑通 MnnLlmChat，然后裁剪为独立项目。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 1.1 | 下载 MNN 预编译 .so（libmnn.so + libmnnllmapp.so） | 下载 | 0.5h | .so 文件 | 文件存在 |
| 1.2 | 下载 Sherpa-MNN 预编译 .so | 下载 | 0.5h | .so 文件 | 文件存在 |
| 1.3 | 用 Android Studio 打开 MnnLlmChat 项目 | 构建 | 0.5h | 项目可打开 | 无 Gradle Sync 错误 |
| 1.4 | 替换 .so 为预编译版本，编译运行 | 构建 | 1h | APK 安装成功 | App 能启动 |
| 1.5 | 下载一个模型并测试推理 | 测试 | 1h | 推理成功 | 能在 App 中对话 |
| 1.6 | 理解 MnnLlmChat 的代码结构 | 学习 | 1.5h | 架构理解 | 能画出模块依赖图 |
| 1.7 | 复制项目为独立目录，修改包名 | 工程 | 0.5h | 独立项目 | 可独立编译 |
| 1.8 | 删除不需要的代码（见删除清单） | 裁剪 | 1h | 精简项目 | 删除后编译通过 |

**Day 1 总耗时：** 约 6-7 小时

**Day 1 完成标志：** MnnLlmChat 跑通，裁剪后的项目能编译运行。

**Day 1 踩坑预案：**

| 常见问题 | 解决方案 |
|---|---|
| Gradle Sync 失败 | 检查 AGP 版本和 Gradle 版本是否匹配 |
| .so 加载失败 | 检查 ABI 是否为 arm64-v8a，文件名是否正确 |
| 模型加载失败 | 检查模型文件是否完整，路径是否正确 |
| 裁剪后编译报错 | 检查是否有残留的 import 语句 |

---

### Day 2：ViewBinding + XML 搭 UI 骨架

**目标：** 用 ViewBinding + XML 搭建 5 个 Tab 的 UI 框架。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 2.1 | 配置 ViewBinding（build.gradle） | 配置 | 0.5h | ViewBinding 可用 | 编译通过 |
| 2.2 | 创建 activity_main.xml（BottomNav + FragmentContainer） | UI | 0.5h | 主布局 | 布局渲染正常 |
| 2.3 | 创建 bottom_nav_menu.xml | UI | 0.5h | 导航菜单 | 5 个 Tab 图标 |
| 2.4 | 实现 MainActivity + Fragment 切换逻辑 | UI | 1h | Tab 可切换 | 点击 Tab 显示对应页面 |
| 2.5 | 创建 5 个 Fragment 空壳 + 对应布局 | UI | 1h | 占位页面 | 每个 Tab 有内容显示 |
| 2.6 | 实现 HomeFragment（系统状态页） | UI | 1.5h | 状态页 | 显示模型状态和内存信息 |
| 2.7 | 实现 ChatAdapter + item_chat_message.xml | UI | 1h | 对话消息列表 | RecyclerView 显示消息 |
| 2.8 | 实现 MemoryMonitor 工具类 | 工具 | 0.5h | 内存监控 | 能采集内存数据 |
| 2.9 | 实现 PerformanceTracker 工具类 | 工具 | 0.5h | 性能追踪 | 能记录 TTFT、tok/s |

**Day 2 总耗时：** 约 7 小时

**Day 2 完成标志：** 5 个 Tab 可切换，HomeFragment 显示系统状态，对话消息列表可用。

---

### Day 3：嵌入模型集成

**目标：** 用 ONNX Runtime 跑通 bge-small-zh-v1.5 文本嵌入。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 3.1 | 下载 bge-small-zh-v1.5 ONNX 模型 + tokenizer.json | 下载 | 0.5h | 模型文件 | 文件存在 |
| 3.2 | 添加 ONNX Runtime + Extensions 依赖 | 配置 | 0.5h | 依赖可用 | 编译通过 |
| 3.3 | 实现 EmbeddingEngine（加载 + 分词 + 推理 + 归一化） | 开发 | 2.5h | EmbeddingEngine.kt | encode() 返回 512 维向量 |
| 3.4 | 验证嵌入质量：相似文本 vs 不相似文本 | 测试 | 1h | 嵌入质量报告 | 相似 > 0.8，不相似 < 0.3 |
| 3.5 | 性能测试：单条编码耗时 | 测试 | 0.5h | 性能日志 | < 15ms |
| 3.6 | 排查嵌入质量问题（如有） | 排错 | 1h | 问题修复 | 相似度达标 |

**Day 3 总耗时：** 约 6 小时

**Day 3 完成标志：** `encode("药品经营许可证")` 返回 512 维归一化向量，相似文本余弦相似度 > 0.8。

**Day 3 踩坑预案：**

| 常见问题 | 解决方案 |
|---|---|
| ONNX 模型加载失败 | 检查 ONNX Runtime 版本是否与模型兼容 |
| 嵌入向量全为 0 或 NaN | 检查输入是否正确分词，是否包含 [CLS]/[SEP] |
| 相似度计算不正确 | 检查是否做了 L2 归一化，是否取了 [CLS] 位置 |
| onnxruntime-extensions 加载 tokenizer 失败 | 检查 tokenizer.json 格式，尝试手动实现 BPE |

---

### Day 4：向量检索 + RAG

**目标：** 实现完整的 RAG 流程。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 4.1 | 实现 VectorStore（内存向量库） | 开发 | 1h | VectorStore.kt | add() 和 search() 正常 |
| 4.2 | 实现 RAGEngine 的分块逻辑 | 开发 | 1h | 分块函数 | 能按段落分块，支持重叠 |
| 4.3 | 实现 RAGEngine 的索引 + 检索 + prompt 组装 | 开发 | 1h | RAG 问答函数 | 输入问题，输出基于文档的回答 |
| 4.4 | 准备 3-5 篇测试文档（中文） | 内容 | 1h | 测试文档集 | 覆盖不同领域 |
| 4.5 | 实现 RAGFragment UI（文档上传 + 对话） | UI | 2h | RAGFragment.kt | 可上传文档 + 提问 + 看回答 |
| 4.6 | 端到端验证 | 测试 | 1h | RAG 测试记录 | 能基于文档内容回答问题 |

**Day 4 总耗时：** 约 7 小时

**Day 4 完成标志：** 索引 3 篇文档后，提问能得到基于文档内容的准确回答。

---

### Day 5：结构化提取 + 文档生成

**目标：** 实现自然语言 → JSON 的结构化提取，以及基于模板的文档生成。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 5.1 | 设计结构化提取的 prompt 模板 | 设计 | 1h | prompt 模板 | 包含 schema 示例 |
| 5.2 | 实现 JsonUtils（提取 + 校验 + 重试） | 开发 | 1h | JsonUtils.kt | 能从不完美输出中提取 JSON |
| 5.3 | 实现 ChatFragment 中的结构化提取功能 | 开发 | 1.5h | 提取功能 | 输入文本 → 输出 JSON |
| 5.4 | 准备 100 条测试数据 | 测试数据 | 1h | 测试数据集 | 覆盖多种场景 |
| 5.5 | 测试 100 次，统计正确率 | 测试 | 1.5h | 正确率报告 | > 90% |
| 5.6 | 实现文档生成功能 | 开发 | 1h | 生成功能 | 选择类型 → 输入数据 → 生成 |
| 5.7 | 优化 prompt（如正确率不达标） | 优化 | 1h | 优化后 prompt | 正确率提升 |

**Day 5 总耗时：** 约 8 小时

**Day 5 完成标志：** 100 次结构化提取，JSON 格式正确率 > 90%。

---

### Day 6：多模态

**目标：** 实现图片 + 文本联合理解。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 6.1 | 下载 Qwen2.5-VL-3B MNN 多模态模型 | 下载 | 1h | 模型文件 | 文件完整 |
| 6.2 | 研究 MnnLlmChat 的多模态推理接口 | 学习 | 1h | 理解接口 | 能说出图片如何输入模型 |
| 6.3 | 实现 VisionEngine（图片预处理 + 推理） | 开发 | 2h | VisionEngine.kt | 输入图片 + 文字 → 输出 |
| 6.4 | 实现 VisionFragment UI（图片选择 + 对话） | UI | 1.5h | VisionFragment.kt | 可选图片 + 输入问题 |
| 6.5 | 测试图片理解效果 | 测试 | 1h | 测试记录 | 输入图片能返回合理描述 |
| 6.6 | 记录性能数据 | 记录 | 0.5h | 性能日志 | 多模态推理速度 |

**Day 6 总耗时：** 约 7 小时

**Day 6 完成标志：** 输入一张药品图片 + "这是什么药品？"，返回合理描述。

**Day 6 注意事项：**
- Qwen2.5-VL-3B 模型约 2.5GB，8GB 设备上可能内存紧张
- 如果 8GB 设备跑不了，降级到更小的 VL 模型或跳过

---

### Day 7：ASR 集成

**目标：** 用 Sherpa-MNN + SenseVoice 实现语音识别。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 7.1 | 下载 SenseVoice MNN 模型 | 下载 | 0.5h | 模型文件 | 文件存在 |
| 7.2 | 研究 MnnLlmChat 中 Sherpa ASR 的集成代码 | 学习 | 1h | 理解接口 | 能说出调用流程 |
| 7.3 | 实现 ASREngine（加载 + 流式识别） | 开发 | 2h | ASREngine.kt | recognize() 返回文本 |
| 7.4 | 实现 AudioRecorder（录音工具） | 开发 | 1h | AudioRecorder.kt | 能录制 PCM 音频 |
| 7.5 | 实现 VoiceFragment 中的 ASR 部分 | UI | 1h | ASR UI | 按住说话 → 显示识别文本 |
| 7.6 | 测试 ASR 准确率和延迟 | 测试 | 1h | ASR 测试记录 | 准确率 > 90%，延迟 < 1s |

**Day 7 总耗时：** 约 6.5 小时

**Day 7 完成标志：** 按住说话，3 秒内显示中文识别结果，准确率 > 90%。

---

### Day 8：TTS 集成 + 语音对话串联

**目标：** 实现语音合成，并串联 ASR → LLM → TTS 全链路。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 8.1 | 下载 MeloTTS MNN 模型 | 下载 | 0.5h | 模型文件 | 文件存在 |
| 8.2 | 实现 TTSEngine（加载 + 流式合成） | 开发 | 2h | TTSEngine.kt | synthesize() 返回音频 |
| 8.3 | 实现 AudioPlayer（播放工具） | 开发 | 1h | AudioPlayer.kt | 能播放 PCM 音频 |
| 8.4 | 实现 VoiceChatEngine（ASR → LLM → TTS 串联） | 开发 | 1.5h | VoiceChatEngine.kt | 全链路跑通 |
| 8.5 | 完善 VoiceFragment UI（完整语音对话） | UI | 1h | 完整语音对话页 | 按住说话 → 听到回答 |
| 8.6 | 测试语音对话完整流程 | 测试 | 1h | 测试记录 | 全链路可用 |

**Day 8 总耗时：** 约 7 小时

**Day 8 完成标志：** 按住说话 → 显示识别文本 → LLM 文字回答 → TTS 语音播报。

---

### Day 9：联调 + 性能测试 + PoC 总结

**目标：** 全链路联调，收集性能数据，输出总结文档。

| # | 任务 | 类型 | 预计耗时 | 产出 | 验收标准 |
|---|---|---|---|---|---|
| 9.1 | 全链路联调：六个场景逐一验证 | 联调 | 2h | 联调记录 | 六个场景全部可用 |
| 9.2 | 修复联调中发现的问题 | 排错 | 1h | 问题修复 | 无阻塞性 bug |
| 9.3 | 性能测试：所有指标全量测试 | 测试 | 2h | 性能测试报告 | 所有指标达标 |
| 9.4 | 飞行模式离线测试 | 测试 | 0.5h | 离线测试记录 | 全部功能离线可用 |
| 9.5 | 整理 PoC 总结文档 | 文档 | 1.5h | PoC 总结 | 包含技术结论和下一步建议 |
| 9.6 | 代码整理 + README | 文档 | 0.5h | 可交付代码 | 他人能看懂并运行 |

**Day 9 总耗时：** 约 7.5 小时

**Day 9 完成标志：** 六个场景全部可用，性能指标达标，PoC 总结文档完成。

---

### 每日检查清单

```
Day 0 ✅ 所有工具安装完毕 ✅ MNN 源码克隆 ✅ 手机 adb 连接 ✅ Kotlin 基础语法
Day 1 ✅ MnnLlmChat 跑通 ✅ 裁剪后项目编译通过 ✅ 预编译 .so 加载成功
Day 2 ✅ 5 个 Tab 可切换 ✅ HomeFragment 显示状态（含性能测试） ✅ ChatAdapter 可用 ✅ MemoryMonitor 可用 ✅ PerformanceTracker 可用 ✅ LLM 推理接入 ✅ 对话功能可用
Day 3 ✅ bge-large-zh-mnn 已下载但无法加载 ✅ 确定改用 ONNX Runtime + bge ✅ 注册 bge ONNX 模型到 ModelRegistry（hf-mirror 直接下载） ✅ ONNX Runtime 依赖配置（onnxruntime-android） ✅ EmbeddingEngine 实现（含均值池化） ✅ encode() 返回正确向量 ✅ 相似度验证通过
Day 4 ✅ VectorStore 可用（纯 Kotlin 暴力搜索，点积=余弦相似度） ✅ RAGEngine 实现（文档分块 + 索引 + 检索 + prompt 组装） ✅ RAGFragment 编译通过（修复 ChatMessage 重定义 + tvRagStatus 布局缺失） ⬜ RAG 端到端验证（需设备上测试文档索引 + 问答）
Day 5 ⬜ 结构化提取可用 ⬜ JSON 正确率 > 90% ⬜ 文档生成可用
Day 6 ⬜ 多模态推理可用 ⬜ 图片理解效果合理
Day 7 ⬜ ASR 模型加载 ⬜ 语音识别准确率 > 90% ⬜ AudioRecorder 可用
Day 8 ⬜ TTS 模型加载 ⬜ 语音合成可用 ⬜ 全链路语音对话跑通
Day 9 ⬜ 六场景联调通过 ⬜ 性能报告完成 ⬜ 离线测试通过 ⬜ PoC 总结
```

---

## 十二、风险与应对

| 风险 | 概率 | 影响 | 应对方案 |
|---|---|---|---|
| MNN 预编译 .so 不兼容 | 中 | 延迟 | 自行编译 MNN（参考 Day 1 踩坑预案） |
| Sherpa-MNN 预编译库缺失 | 中 | ASR/TTS 受阻 | 从 sherpa-onnx 用 ONNX 格式替代 |
| 设备内存不足 OOM | 中 | 推理失败 | 降级到 Qwen2-0.5B；减少 KV Cache |
| bge 分词器效果差 | 中 | RAG 效果差 | 扩大词表范围；或用 MNN 自带 tokenizer |
| OpenCL GPU 后端不兼容 | 中 | 速度降级 | MNN 自动降级到 CPU；手动设置 backend |
| 多模态模型太大（3B = 2.5GB） | 低 | 内存不足 | 只在 12GB+ 设备上测试；或跳过 |
| ONNX Runtime 在 ARM 上慢 | 低 | 嵌入延迟高 | 启用 XNNPACK 后端；或改用 MNN |
| SenseVoice 模型转换困难 | 低 | ASR 受阻 | 用 Paraformer 替代 |
| MeloTTS 中文效果差 | 低 | TTS 体验差 | 换 VITS 模型；或降级到系统 TTS |

---

## 十三、从 PoC 到生产的路线图

| 维度 | PoC（当前阶段） | 生产（下一阶段） |
|---|---|---|
| 向量检索 | 暴力搜索 | SQLite FTS5 + Annoy/FAISS |
| 分词器 | onnxruntime-extensions | JNI 调用 Rust tokenizers |
| UI | 功能性 XML 布局 | 完整业务 UI + 交互优化 |
| 数据存储 | SharedPreferences + JSON | Room 数据库 |
| 数据同步 | 仅本地 | 离线优先 + 增量同步 |
| 安全 | 无 | 加密存储 + 传输安全 |
| 模型部署 | adb push | CDN 下载 + 断点续传 + 版本管理 |
| 测试 | 单设备手动 | 多设备兼容 + 自动化 |
| NPU 适配 | 不做 | 高通 QNN / 联发科 NeuroPilot |
| 语音唤醒 | 不做 | Wake Word 检测（如 Porcupine） |
| 多轮语音 | 单轮对话 | 多轮上下文 + 打断能力 |

**不变的核心资产（PoC 阶段就设计好，生产可复用）：**

- LLMEngine / RAGEngine / ASREngine / TTSEngine 的接口定义
- 结构化提取的 prompt 设计
- RAG 的分块和检索策略
- 语音对话的串联逻辑
- 模型生命周期管理逻辑

---

## 附录 A：依赖版本清单

| 依赖 | 版本 | 用途 | 引入方式 |
|---|---|---|---|
| MNN | 预编译 .so | LLM 推理引擎 | jniLibs |
| Sherpa-MNN | 预编译 .so | ASR / TTS | jniLibs |
| ONNX Runtime Android | 1.19.0 | 嵌入模型推理 | Maven（onnxruntime-android，含 ARM .so） |
| onnxruntime-extensions | 0.12.0 | BPE 分词器 | Maven |
| Kotlin | 1.9.x | 开发语言 | Gradle Plugin |
| ViewBinding | 内置 | UI 绑定 | AGP 内置 |
| Material 3 | 1.12.0 | UI 组件 | Maven |
| Coroutines | 1.7.3 | 异步编程 | Maven |
| Gson | 2.10.1 | JSON 序列化 | Maven |
| OkHttp | 4.12.0 | 模型下载 | Maven |
| Retrofit | 2.9.0 | 网络请求 | Maven |
| compileSdk | 35 | Android 15 | - |
| minSdk | 26 | Android 8.0 | - |
| targetSdk | 35 | Android 15 | - |
| NDK | 27.2.12479018 | 原生编译 | SDK Manager |

## 附录 B：参考实测数据

以下数据来自公开 benchmark（骁龙 8 Gen3，1.5B 模型，4bit 量化，2048 context），仅供参考量级：

| 引擎 | 首 token 延迟 | decode 速度 | 内存峰值 | 接入难度 |
|---|---|---|---|---|
| llama.cpp (CPU) | ~1800ms | ~18 t/s | ~1.1 GB | ⭐⭐⭐⭐ |
| **MNN (GPU)** | ~1200ms | ~28 t/s | ~980 MB | ⭐⭐ |
| MediaPipe (GPU) | ~900ms | ~32 t/s | ~850 MB | ⭐ |

Sherpa-MNN ASR/TTS 参考：

| 任务 | 模型 | 延迟 | 内存峰值 |
|---|---|---|---|
| 语音识别（3s 音频） | SenseVoice | ~500ms | ~300MB |
| 语音合成（20 字） | MeloTTS | ~1.5s | ~200MB |

## 附录 C：术语表

| 术语 | 解释 |
|---|---|
| **LLM** | Large Language Model，大语言模型 |
| **MNN** | Mobile Neural Network，阿里巴巴开源的移动端推理框架 |
| **Sherpa-MNN** | k2-fsa 项目的 MNN 推理后端，用于 ASR 和 TTS |
| **SenseVoice** | 阿里达摩院的语音识别模型，支持中英日粤 |
| **MeloTTS** | 高质量中文语音合成模型 |
| **NDK** | Native Development Kit，Android 原生开发工具包 |
| **JNI** | Java Native Interface，Java 调用 C/C++ 的桥梁 |
| **.so** | Shared Object，Linux/Android 的动态链接库 |
| **ONNX** | Open Neural Network Exchange，模型交换格式 |
| **KV Cache** | Key-Value Cache，Transformer 推理时缓存历史计算结果 |
| **Prefill** | 预填充，一次性处理用户输入的 prompt |
| **Decode** | 解码，逐 token 自回归生成 |
| **TTFT** | Time To First Token，首 token 延迟 |
| **tok/s** | Tokens per Second，每秒生成的 token 数 |
| **RAG** | Retrieval Augmented Generation，检索增强生成 |
| **Embedding** | 文本嵌入，把文字变成向量 |
| **ASR** | Automatic Speech Recognition，自动语音识别 |
| **TTS** | Text-to-Speech，语音合成 |
| **PCM** | Pulse-Code Modulation，原始音频数据格式 |
| **ViewBinding** | Android 编译期生成的视图绑定类 |
| **Coroutines** | Kotlin 的协程，轻量级线程 |
| **Flow** | Kotlin 的异步数据流 |


---

## 附录 D：项目进度跟踪

> **创建日期**：2026-05-12
> **最后更新**：2026-05-18 16:00 CST
> **当前阶段**：Day 3 已完成 — 嵌入质量验证通过，准备进入 Day 4（向量检索 + RAG）

### D.1 总览

| 阶段 | 名称 | 状态 | 完成度 |
|------|------|------|--------|
| Day 0 | 环境准备 + Kotlin 基础 | ✅ 已完成 | 100% |
| Day 1 | 跑通 MnnLlmChat + 裁剪项目 | ✅ 已完成 | 100% |
| Day 2 | ViewBinding + XML 搭 UI 骨架 | ✅ 已完成 | 100% |
| Day 3 | 嵌入模型集成（ONNX Runtime + bge） | ✅ 已完成 | 100% |
| Day 4 | 向量检索 + RAG | ⬜ 未开始 | 0% |
| Day 5 | 结构化提取 + 文档生成 | ⬜ 未开始 | 0% |
| Day 6 | 多模态（图片理解） | ⬜ 未开始 | 0% |
| Day 7 | ASR 集成（Sherpa-MNN + SenseVoice） | ⬜ 未开始 | 0% |
| Day 8 | TTS 集成 + 语音对话串联 | ⬜ 未开始 | 0% |
| Day 9 | 联调 + 性能测试 + PoC 总结 | ⬜ 未开始 | 0% |

**图例：** ✅ 已完成 | 🟡 进行中 | ⬜ 未开始 | ❌ 阻塞 | ⏭️ 跳过

### D.2 Day 0：环境准备 + Kotlin 基础

| # | 任务 | 状态 | 完成日期 | 备注                   |
|---|------|------|----------|----------------------|
| 0.1 | 安装 Android Studio | ✅ | 2026-05-12 | 已安装，可创建项目            |
| 0.2 | 安装 NDK 27.2.12479018 | ✅ | — | 已安装                  |
| 0.3 | 安装 CMake 3.22.1+ | ✅ | — | 已安装                  |
| 0.4 | 安装 Git + Python 3.8+ | ✅ | 2026-05-12 | Git 已安装并配置           |
| 0.5 | 克隆 MNN 源码 | ✅ | 2026-05-13 | 已克隆到工作目录           |
| 0.6 | 准备测试手机（8GB+ RAM，ARM64） | ✅ | — | 虚拟设备                 |
| 0.7 | 学习 Kotlin 基础语法 | ✅ | — | 有基础，按需补充             |
| 0.8 | 创建空项目并推送到 GitHub | ✅ | 2026-05-12 | `ASFKING/MnnLlmChat` |
| 0.9 | 配置 .gitignore（排除 .idea 等） | ✅ | 2026-05-12 | 已清理并推送               |

**Day 0 完成 ✅**

### D.3 Day 1：跑通 MnnLlmChat + 裁剪项目

| # | 任务 | 状态 | 完成日期 | 备注 |
|---|------|------|----------|------|
| 1.1 | 下载 MNN 预编译 .so（libmnn.so + libmnnllmapp.so） | ✅ | 2026-05-13 | libMNN.so + libmnnllmapp.so 已就位 |
| 1.2 | 下载 Sherpa-MNN 预编译 .so | ✅ | 2026-05-13 | libsherpa-mnn-jni.so + libmnn_tts.so 已就位 |
| 1.3 | 用 Android Studio 打开 MnnLlmChat 项目 | ✅ | 2026-05-13 | Gradle Sync 成功 |
| 1.4 | 替换 .so 为预编译版本，编译运行 | ✅ | 2026-05-13 | 编译通过，修复了裁剪残留依赖 |
| 1.5 | 下载一个模型并测试推理 | ✅ | 2026-05-13 | Qwen3-0.6B 下载成功，页面显示已下载 |
| 1.6 | 理解 MnnLlmChat 的代码结构 | ✅ | 2026-05-14 | 已理解 MNN→JNI→LlmSession→LlmService→Flow 的完整链路 |
| 1.7 | 复制项目为独立目录，修改包名 | ✅ | 2026-05-12 | 已有独立项目 `com.poc.ondevice` |
| 1.8 | 删除不需要的代码（见删除清单） | ✅ | 2026-05-14 | 已裁剪并补全全部双层注释（37 个文件） |

### D.4 Day 2：ViewBinding + XML 搭 UI 骨架

| # | 任务 | 状态 | 完成日期 | 备注 |
|---|------|------|----------|------|
| 2.1 | 配置 ViewBinding（build.gradle） | ✅ | 2026-05-12 | build.gradle.kts 已启用 |
| 2.2 | 创建 activity_main.xml（BottomNav + FragmentContainer） | ✅ | 2026-05-12 | 已完成 |
| 2.3 | 创建 bottom_nav_menu.xml | ✅ | 2026-05-12 | 5 个 Tab 已配置 |
| 2.4 | 实现 MainActivity + Fragment 切换逻辑 | ✅ | 2026-05-12 | 已完成，含详细注释 |
| 2.5 | 创建 5 个 Fragment 空壳 + 对应布局 | ✅ | 2026-05-13 | 5 个布局 XML + ViewBinding Fragment 已完成 |
| 2.6 | 实现 HomeFragment（系统状态页） | ✅ | 2026-05-13 | 含内存监控、模型状态显示 |
| 2.7 | 实现 ChatAdapter + item_chat_message.xml | ✅ | 2026-05-13 | 含 ChatMessageAdapter + RecyclerView |
| 2.8 | 实现 MemoryMonitor 工具类 | ✅ | 2026-05-15 | MemoryMonitor.kt：JVM + Native + PSS 内存监控，含预警判断 |
| 2.9 | 实现 PerformanceTracker 工具类 | ✅ | 2026-05-15 | PerformanceTracker.kt：计时 + tok/s 统计 + 性能报告 |
| 2.10 | 实现 LLMEngine（MNN-LLM 推理封装） | ✅ | 2026-05-15 | LLMEngine.kt：封装 MNN-LLM，提供 Flow 流式接口 + Mutex 线程安全 |
| 2.11 | ChatFragment 接入真实 LLM 推理 | ✅ | 2026-05-15 | 替换占位回复，接入 LLMEngine.generateStream()，对话功能可用 |

### D.5 Day 3：嵌入模型集成

| # | 任务 | 状态 | 完成日期 | 备注 |
|---|------|------|----------|------|
| 3.1 | 下载 bge 嵌入模型 | ✅ | 2026-05-15 | bge-large-zh-mnn 已下载（ModelScope），但无法通过 LlmSession 加载（见踩坑记录） |
| 3.2 | 确定嵌入模型方案 | ✅ | 2026-05-15 | 改用 ONNX Runtime + bge（原始设计方案回归） |
| 3.3 | 注册 bge ONNX 模型到 ModelRegistry | ✅ | 2026-05-17 | 改用 hf-mirror onnx-community 预导出的 bge-small-zh-v1.5 ONNX，App 内直接下载 |
| 3.4 | 添加 ONNX Runtime 依赖 | ✅ | 2026-05-17 | onnxruntime 1.19.0 已在 build.gradle.kts，onnxruntime-extensions 改用纯 Kotlin SimpleTokenizer 替代 |
| 3.5 | 实现 EmbeddingEngine（ONNX Runtime 版） | ✅ | 2026-05-17 | 含 SimpleTokenizer + EmbeddingValidator，首次测试发现取 [CLS] 导致相似度低（0.36），已改为均值池化 |
| 3.6 | 验证嵌入质量（相似 vs 不相似文本） | ✅ | 2026-05-18 | 修复 onnxruntime → onnxruntime-android 后测试通过，均值池化生效 |
| 3.7 | 性能测试：单条编码耗时 | ⬜ | — | |

### D.6 Day 4：向量检索 + RAG

| # | 任务 | 状态 | 完成日期 | 备注 |
|---|------|------|----------|------|
| 4.1 | 实现 VectorStore（内存向量库） | ⬜ | — | |
| 4.2 | 实现 RAGEngine 的分块逻辑 | ⬜ | — | |
| 4.3 | 实现 RAGEngine 的索引 + 检索 + prompt 组装 | ⬜ | — | |
| 4.4 | 准备 3-5 篇测试文档 | ⬜ | — | |
| 4.5 | 实现 RAGFragment UI | ⬜ | — | |
| 4.6 | 端到端验证 | ⬜ | — | |

### D.7 Day 5：结构化提取 + 文档生成

| # | 任务 | 状态 | 完成日期 | 备注 |
|---|------|------|----------|------|
| 5.1 | 设计结构化提取 prompt 模板 | ⬜ | — | |
| 5.2 | 实现 JsonUtils | ⬜ | — | |
| 5.3 | 实现 ChatFragment 中的结构化提取功能 | ⬜ | — | |
| 5.4 | 准备 100 条测试数据 | ⬜ | — | |
| 5.5 | 测试 100 次，统计正确率 | ⬜ | — | 目标 > 90% |
| 5.6 | 实现文档生成功能 | ⬜ | — | |
| 5.7 | 优化 prompt（如正确率不达标） | ⬜ | — | |

### D.8 Day 6：多模态（图片理解）

| # | 任务 | 状态 | 完成日期 | 备注 |
|---|------|------|----------|------|
| 6.1 | 下载 Qwen2.5-VL-3B MNN 多模态模型 | ⬜ | — | ~2.5GB |
| 6.2 | 研究 MnnLlmChat 的多模态推理接口 | ⬜ | — | |
| 6.3 | 实现 VisionEngine | ⬜ | — | |
| 6.4 | 实现 VisionFragment UI | ⬜ | — | |
| 6.5 | 测试图片理解效果 | ⬜ | — | |
| 6.6 | 记录性能数据 | ⬜ | — | |

### D.9 Day 7：ASR 集成

| # | 任务 | 状态 | 完成日期 | 备注 |
|---|------|------|----------|------|
| 7.1 | 下载 SenseVoice MNN 模型 | ⬜ | — | |
| 7.2 | 研究 MnnLlmChat 中 Sherpa ASR 集成代码 | ⬜ | — | |
| 7.3 | 实现 ASREngine | ⬜ | — | |
| 7.4 | 实现 AudioRecorder | ⬜ | — | |
| 7.5 | 实现 VoiceFragment 中的 ASR 部分 | ⬜ | — | |
| 7.6 | 测试 ASR 准确率和延迟 | ⬜ | — | 目标 > 90%，< 1s |

### D.10 Day 8：TTS 集成 + 语音对话串联

| # | 任务 | 状态 | 完成日期 | 备注 |
|---|------|------|----------|------|
| 8.1 | 下载 MeloTTS MNN 模型 | ⬜ | — | |
| 8.2 | 实现 TTSEngine | ⬜ | — | |
| 8.3 | 实现 AudioPlayer | ⬜ | — | |
| 8.4 | 实现 VoiceChatEngine | ⬜ | — | |
| 8.5 | 完善 VoiceFragment UI | ⬜ | — | |
| 8.6 | 测试语音对话完整流程 | ⬜ | — | |

### D.11 Day 9：联调 + 性能测试 + PoC 总结

| # | 任务 | 状态 | 完成日期 | 备注 |
|---|------|------|----------|------|
| 9.1 | 全链路联调：六个场景逐一验证 | ⬜ | — | |
| 9.2 | 修复联调中发现的问题 | ⬜ | — | |
| 9.3 | 性能测试：所有指标全量测试 | ⬜ | — | |
| 9.4 | 飞行模式离线测试 | ⬜ | — | |
| 9.5 | 整理 PoC 总结文档 | ⬜ | — | |
| 9.6 | 代码整理 + README | ⬜ | — | |

### D.12 验证指标追踪

| 验证项 | 目标值 | 实际值 | 状态 | 测试日期 |
|--------|--------|--------|------|----------|
| 模型加载（Qwen3-1.7B Q4） | 成功 | — | ⬜ | — |
| TTFT（首 token 延迟） | < 2s | — | ⬜ | — |
| 生成速度 | > 15 tok/s | — | ⬜ | — |
| 内存峰值 | < 2GB | — | ⬜ | — |
| JSON 正确率 | > 90% | — | ⬜ | — |
| RAG 端到端延迟 | < 8s | — | ⬜ | — |
| ASR 准确率 | > 90% | — | ⬜ | — |
| ASR 延迟（3s 语音） | < 1s | — | ⬜ | — |
| TTS 延迟（20 字） | < 2s | — | ⬜ | — |
| 语音对话全链路 | 可用 | — | ⬜ | — |
| 离线运行 | 100% | — | ⬜ | — |

### D.13 模型资源追踪

| 模型 | 大小 | 下载状态 | 部署状态 | 备注 |
|------|------|----------|----------|------|
| Qwen3-1.7B (Q4_K_M) | ~1.1GB | ⬜ | ⬜ | LLM 推理主力（备选） |
| Qwen3-0.6B | ~0.6GB | ✅ | ✅ | 已下载，PoC 验证用 |
| bge-large-zh-mnn | ~216MB | ✅ | ❌ | 已下载但无法通过 LlmSession 加载（架构不兼容），弃用 |
| bge-small-zh-v1.5 (ONNX) | ~248MB | ✅ | ✅ | hf-mirror onnx-community 预导出，App 内直接下载，512 维输出，嵌入质量验证通过 |
| Qwen2.5-VL-3B | ~2.5GB | ⬜ | ⬜ | 多模态（可选） |
| SenseVoice | ~200MB | ⬜ | ⬜ | ASR |
| MeloTTS-zh | ~150MB | ⬜ | ⬜ | TTS |

### D.14 踩坑记录

| 日期 | 问题描述 | 解决方案 | 耗时 |
|------|----------|----------|------|
| 2026-05-12 | git push 被拒（远程有 README） | `git pull origin main --allow-unrelated-histories` 合并后推送 | 2min |
| 2026-05-12 | .idea/ 目录混入 git 暂存区 | `git rm -r --cached .idea` + `.gitignore` 追加 `/.idea/` | 2min |
| 2026-05-13 | ChatDataItem.kt 引用已删除的 chatlist 包 | 删除 import，内联 USER/ASSISTANT 常量 | 5min |
| 2026-05-13 | 裁剪后 mls.api/modelist/qnn/benchmark 包缺失 | 创建 9 个桩类 + App.kt + Timber 依赖 | 15min |
| 2026-05-13 | ModelUtils.kt 引用 modelmarket/R.drawable 等缺失资源 | 重写为精简版，删除不需要的功能 | 10min |
| 2026-05-15 | LLMEngine 的 configPath 传了目录而非 config.json 文件 | 修正为 `"$modelDir/config.json"`，使用 ModelDownloader.getModelPath() 获取正确路径 | 10min |
| 2026-05-15 | MemoryMonitor 用 nativeHeapAllocatedSize 不准确 | 改用 Debug.getNativePss() 获取更准确的原生内存占用 | 5min |
| 2026-05-15 | onnxruntime-extensions:0.12.0 在 Maven Central 不存在 | 修正为 0.12.4（可用版本：0.11.0 / 0.12.4 / 0.13.0） | 5min |
| 2026-05-15 | androidx.test.ext:junit:1.5 不存在 | 修正为 1.2.1 | 2min |
| 2026-05-15 | bge-large-zh-mnn 的 config.json 中 llm_weight 指向不存在的 embedding.mnn.weight | ModelScope 仓库的配置与文件不同步（提交信息："update model, split embedding layer"），权重文件实际叫 embeddings_bf16.bin | 15min |
| 2026-05-15 | 复制 embeddings_bf16.bin → embedding.mnn.weight 后仍然加载失败 | 两者格式不同：embeddings_bf16.bin 是 DiskEmbedding 用的原始 bf16 数据，embedding.mnn.weight 应该是 MNN 权重格式 | 10min |
| 2026-05-15 | 创建空的 embedding.mnn.weight 占位文件仍然失败 | 问题不在权重文件，而是 Llm::load() 硬编码了 LLM 的输入输出名（input_ids/attention_mask/position_ids/logits_index → logits），与 BERT 嵌入模型架构不兼容 | 20min |
| 2026-05-15 | 最终结论：bge 嵌入模型无法通过 MNN LlmSession 加载 | LlmSession 的 C++ 层（Llm::load()）只支持 decoder-only LLM 架构，不支持 BERT encoder 架构。需要改用 ONNX Runtime 或 MNN 通用 API | — |
| 2026-05-17 | 对话流式输出时 RecyclerView 闪烁 | Adapter 新增 onBindViewHolder(payloads) 局部更新 + scrollToPosition 替代 smoothScrollToPosition | 15min |
| 2026-05-17 | 生成完成后按钮卡在"生成中" | callbackFlow 在 session.generate() 返回后未调用 close()，添加 close() 调用 | 10min |
| 2026-05-17 | Qwen3 空 <think> 标签显示 | thinking tokens 不通过 onProgress 回调，但标签本身会发过来。用正则 <think>\s*</think> 移除空对 | 5min |
| 2026-05-17 | moyangzhan/bge-base-zh-v1.5-onnx 仓库 404 | 改用 onnx-community/bge-small-zh-v1.5-ONNX（HF 官方组织），URL 已验证可用 | 5min |
| 2026-05-17 | bge 嵌入相似度只有 0.36（目标 > 0.8） | 原代码取 [CLS] 位置的向量（`arr3d[0][0]`），但 bge 模型训练时用的是均值池化（Mean Pooling）。改为对所有有效 token 的向量取平均，用 attention_mask 排除 padding。修改后需重新验证 | 20min |
| 2026-05-18 | `UnsatisfiedLinkError: libonnxruntime4j_jni.so not found` | `com.microsoft.onnxruntime:onnxruntime` 是桌面版，不含 Android ARM .so。改为 `onnxruntime-android`，内置 arm64-v8a 原生库 | 5min |

### D.15 关键决策记录

| 日期 | 决策 | 理由 |
|------|------|------|
| 2026-05-12 | 项目包名使用 `com.poc.ondevice` | 与设计方案一致 |
| 2026-05-15 | 嵌入模型弃用 bge-large-zh-mnn（MNN 格式），改用 bge（ONNX 格式） | MNN LlmSession 的 C++ 层只支持 decoder-only LLM 架构，不支持 BERT encoder 架构。ONNX Runtime 加载 bge 是经过大量验证的方案 |
| 2026-05-15 | 嵌入模型用 ONNX Runtime 加载，不走 MNN 框架 | bge 模型很小（33M 参数），不需要 MNN 的移动端优化。ONNX Runtime 有官方 Java API，接入最简单 |
| 2026-05-17 | 改用 hf-mirror onnx-community 预导出的 bge-small-zh-v1.5 ONNX，App 内直接下载 | 免去用户手动用 optimum-cli 导出的步骤，复用 ModelDownloader 的 directFiles 模式，体验与 LLM 模型下载一致。onnx-community 是 HF 官方 ONNX 转换组织，512 维符合原始设计 |

### D.16 每日进度摘要

#### 2026-05-12（Day 0）
- ✅ Android Studio 已安装，空项目创建成功
- ✅ 项目推送到 GitHub：`ASFKING/MnnLlmChat`
- ✅ .gitignore 配置完成，排除了 .idea、build 等目录
- ✅ ViewBinding 已配置，activity_main.xml + bottom_nav_menu.xml 已创建
- ✅ MainActivity + 5 个 Fragment 空壳已创建

#### 2026-05-13（Day 1 + Day 2 并行推进）
- ✅ MNN 官方仓库已克隆（`git clone --depth 1`）
- ✅ 预编译 .so 文件已获取并复制到 PoC 项目
- ✅ build.gradle.kts 配置更新（ndk abiFilters + noCompress + Kotlin 插件 + 协程/Gson/RecyclerView 依赖）
- ✅ 5 个 Fragment 布局 XML 创建完成（fragment_home/chat/rag/vision/voice.xml）
- ✅ HomeFragment 实现：ViewBinding + 内存监控 + 模型状态显示
- ✅ ChatFragment 实现：ViewBinding + RecyclerView + ChatMessageAdapter
- ✅ RAGFragment / VisionFragment / VoiceFragment：ViewBinding 骨架就绪
- ✅ item_chat_message.xml + bg_chat_bubble.xml 创建完成
- ✅ 修复裁剪后残留编译依赖：创建 9 个桩类（mls.api/modelist/qnn/benchmark）
- ✅ 新建 App.kt（Application 入口，初始化 ApplicationProvider）
- ✅ AndroidManifest.xml 注册 App 类 + 录音权限
- ✅ 添加 Timber 日志库依赖
- ✅ 重写 ModelUtils.kt，删除对 modelmarket/R.drawable 等缺失资源的依赖
- ✅ Android Studio 编译通过（Gradle Sync + Build 成功）
- ✅ App 安装到实体手机成功
- ✅ Qwen3-0.6B 模型下载成功，页面显示已下载

#### 2026-05-14（Day 1 收尾 + Day 2 收尾）
- ✅ 理解 MnnLlmChat 完整代码结构（MNN→JNI→LlmSession→LlmService→Flow）
- ✅ 裁剪项目完成，删除不需要的代码
- ✅ 补全全部 37 个 Kotlin 文件的双层注释（语法注释 + 业务注释）
- ✅ 注释覆盖：PoC 核心层、MNN 引擎层、模型配置层、工具类层、桩类层

#### 2026-05-15（Day 2 最终收尾 + LLM 推理接入 + Day 3 嵌入模型探索）
- ✅ 实现 MemoryMonitor 工具类（JVM + Native + PSS 内存监控 + 预警判断）
- ✅ 实现 PerformanceTracker 工具类（计时 + tok/s 统计 + 性能报告）
- ✅ 更新 HomeFragment 集成 MemoryMonitor + PerformanceTracker（每 3 秒自动刷新）
- ✅ 实现 LLMEngine.kt（封装 MNN-LLM 推理，Flow 流式接口，Mutex 线程安全）
- ✅ ChatFragment 接入真实 LLM 推理，替换占位回复，对话功能可用
- ✅ 修复 configPath 指向目录而非 config.json 的问题
- ✅ 修复 MemoryMonitor 使用 nativePss 替代 nativeHeapAllocatedSize
- ✅ Day 2 全部任务完成（11/11），LLM 对话功能跑通验证
- ✅ bge-large-zh-mnn 模型下载成功（ModelScope，216MB）
- ❌ 尝试通过 LlmSession 加载 bge-large-zh-mnn 失败
- 🔍 深入分析 MNN C++ 源码（llm.cpp / llm_session.cpp / llmconfig.hpp / diskembedding.cpp）
- 🔍 发现 Llm::load() 硬编码 LLM 输入输出名，不兼容 BERT 架构
- 🔍 发现 ModelScope 仓库的 config.json 与实际文件不同步（llm_weight 指向不存在的文件）
- ✅ 确定 Day 3 方案：改用 ONNX Runtime + bge-small-zh-v1.5（回归原始设计方案）

#### 2026-05-17（Day 3 嵌入模型注册 + Bug 修复）
- ✅ 修复对话流式输出 RecyclerView 闪烁（Adapter payload 局部更新 + scrollToPosition）
- ✅ 修复生成完成后按钮卡在"生成中"（callbackFlow 添加 close() 调用）
- ✅ 修复 Qwen3 空 <think> 标签显示（正则清理空对）
- ✅ 注册 bge ONNX 嵌入模型到 ModelRegistry（hf-mirror onnx-community 直接下载）
- ✅ 验证 bge-small-zh-v1.5 ONNX 模型下载成功（~248MB，512 维）
- ✅ EmbeddingEngine + SimpleTokenizer + EmbeddingValidator 全部实现
- ✅ 首次嵌入质量测试：模型加载成功，encode 返回 512 维向量，但 sim(A,B)=0.3621（目标>0.8）
- ✅ 诊断根因：bge 模型应用均值池化（Mean Pooling），原代码取 [CLS] 位置导致相似度低
- ✅ 修复 EmbeddingEngine：将 extractClsEmbedding 替换为 extractMeanPooledEmbedding + meanPool2D
- ⬜ 待重新验证嵌入质量（需在真机上重新编译运行）
- ✅ 更新 PHASE3 文档（进度跟踪、踩坑记录、决策记录、模型资源追踪）

#### 2026-05-18（Day 3 收尾）
- ❌ 首次真机验证失败：`UnsatisfiedLinkError: libonnxruntime4j_jni.so not found`
- 🔍 诊断：libs.versions.toml 中 onnxruntime 依赖是桌面版（不含 Android ARM .so）
- ✅ 修复：`onnxruntime` → `onnxruntime-android`，提交推送
- ✅ 重新编译运行，嵌入质量验证通过（EmbeddingValidator 全部测试通过）
- ✅ Day 3 全部任务完成，准备进入 Day 4（向量检索 + RAG）

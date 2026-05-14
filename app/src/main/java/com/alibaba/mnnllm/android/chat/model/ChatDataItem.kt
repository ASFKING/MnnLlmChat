package com.alibaba.mnnllm.android.chat.model

import android.net.Uri
import java.io.File

/**
 * ChatDataItem：对话消息数据类
 *
 * 一句话：表示一条聊天消息（可以是用户消息、AI 回复、或系统消息）。
 * 生活类比：就像聊天记录中的一条消息——有发送者、内容、时间、附件等。
 *
 * 这个类是原始 MnnLlmChat 的核心数据结构，支持多种消息类型：
 * - 纯文本消息
 * - 图片消息（支持多图）
 * - 音频消息
 * - 视频消息
 * - 带思考过程的消息（Qwen3 的深度思考模式）
 */
class ChatDataItem {
    // loading：是否正在加载中（用于显示 loading 动画）
    var loading: Boolean = false

    // forceShowLoadingWithText：强制在有文字的同时也显示 loading
    var forceShowLoadingWithText: Boolean = false

    // @JvmField：告诉 Kotlin 编译器这个字段直接暴露给 Java（不生成 getter/setter）
    // 为什么需要？JNI 回调时，C++ 代码可能直接访问这些字段
    @JvmField
    var time: String? = null      // 消息时间戳

    @JvmField
    var text: String? = null      // 消息文本内容

    // type：消息类型（USER=0, ASSISTANT=1, SYSTEM=2）
    // private set：只能在类内部修改，外部只能读取
    var type: Int
        private set

    @Deprecated("Use imageUris instead")
    var imageUri: Uri?
        // get()：自定义 getter，返回 imageUris 的第一个元素
        get() = imageUris?.firstOrNull()
        // set(value)：自定义 setter，把单个 Uri 转成列表存储
        set(value) {
            imageUris = if (value != null) listOf(value) else null
        }

    // imageUris：图片 URI 列表（支持多图输入）
    var imageUris: List<Uri>? = null

    @JvmField
    var videoUri: Uri? = null     // 视频 URI

    @JvmField
    var audioUri: Uri? = null     // 音频 URI

    @JvmField
    var benchmarkInfo: String? = null  // 性能测试信息

    // displayText：显示文本（可能与 text 不同，如流式生成时的中间状态）
    // 自定义 getter：如果 field 为 null，返回空字符串
    var displayText: String? = null
        get() = field ?: ""

    // thinkingText：思考过程文本（Qwen3 深度思考模式的"内心独白"）
    var thinkingText: String? = null

    // audioDuration：音频时长（秒）
    var audioDuration = 0f

    private var _hasOmniAudio: Boolean = false

    /**
     * 主构造函数（3 参数版本）
     *
     * Kotlin 中，类可以有多个构造函数：
     * - 主构造函数：写在类名后面
     * - 次构造函数：用 constructor 关键字声明
     */
    constructor(time: String?, type: Int, text: String?) {
        this.time = time
        this.type = type
        this.text = text
        this.displayText = text
    }

    /**
     * 次构造函数（1 参数版本）
     * 只指定类型，其他字段后续设置
     */
    constructor(type: Int) {
        this.type = type
    }

    // hasOmniAudio：是否有 Omni 音频数据
    var hasOmniAudio: Boolean
        get() = _hasOmniAudio
        set(value) {
            _hasOmniAudio = value
        }

    /**
     * audioPath：获取音频文件的本地路径
     *
     * Uri.scheme：获取 URI 的协议部分（如 "file"、"content"）
     * 只有 "file" 协议的 URI 才能直接获取路径
     */
    val audioPath: String?
        get() {
            if (this.audioUri != null && "file" == audioUri!!.scheme) {
                return audioUri!!.path
            }
            return null
        }

    val videoPath: String?
        get() {
            if (this.videoUri != null && "file" == videoUri!!.scheme) {
                return videoUri!!.path
            }
            return null
        }

    // showThinking：是否显示思考过程
    var showThinking: Boolean = true

    // thinkingFinishedTime：思考完成的时间戳（-1 表示未完成）
    var thinkingFinishedTime = -1L

    /**
     * toggleThinking：切换思考过程的显示/隐藏
     */
    fun toggleThinking() {
        showThinking = !showThinking
    }

    companion object {
        // 消息类型常量
        const val USER = 0       // 用户消息
        const val ASSISTANT = 1  // AI 回复
        const val SYSTEM = 2     // 系统消息

        /**
         * 创建图片输入消息的工厂方法
         *
         * 工厂方法模式：用 companion object 中的方法代替构造函数
         * 优势：方法名更有语义（createImageInputData 比 ChatDataItem(time, USER, text) 更清晰）
         */
        fun createImageInputData(timeString: String?, text: String?, imageUris: List<Uri>?): ChatDataItem {
            val result = ChatDataItem(timeString, USER, text)
            result.imageUris = imageUris
            return result
        }

        @Deprecated("Use createImageInputData with list")
        fun createImageInputData(timeString: String?, text: String?, imageUri: Uri?): ChatDataItem {
            val result = ChatDataItem(timeString, USER, text)
            result.imageUri = imageUri
            return result
        }

        fun createAudioInputData(
            timeString: String?,
            text: String?,
            audioPath: String,
            duration: Float
        ): ChatDataItem {
            val result = ChatDataItem(timeString, USER, text)
            // Uri.fromFile()：从文件路径创建 file:// URI
            result.audioUri = Uri.fromFile(File(audioPath))
            result.audioDuration = duration
            return result
        }

        fun createVideoInputData(
            timeString: String?,
            text: String?,
            videoPath: String
        ): ChatDataItem {
            val result = ChatDataItem(timeString, USER, text)
            result.videoUri = Uri.fromFile(File(videoPath))
            return result
        }
    }
}

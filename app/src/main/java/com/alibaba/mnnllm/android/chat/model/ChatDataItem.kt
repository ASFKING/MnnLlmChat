// Created by ruoyi.sjd on 2024/12/25.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.chat.model

import android.net.Uri
import java.io.File

class ChatDataItem {
    var loading: Boolean = false
    var forceShowLoadingWithText: Boolean = false

    @JvmField
    var time: String? = null
    @JvmField
    var text: String? = null
    var type: Int
        private set
    @Deprecated("Use imageUris instead")
    var imageUri: Uri?
        get() = imageUris?.firstOrNull()
        set(value) {
            imageUris = if (value != null) listOf(value) else null
        }

    var imageUris: List<Uri>? = null

    @JvmField
    var videoUri: Uri? = null

    @JvmField
    var audioUri: Uri? = null

    @JvmField
    var benchmarkInfo: String? = null

    var displayText: String? = null
        get() = field?:""

    var thinkingText: String? = null

    var audioDuration = 0f

    private var _hasOmniAudio:Boolean = false

    constructor(time: String?, type: Int, text: String?) {
        this.time = time
        this.type = type
        this.text = text
        this.displayText = text
    }

    constructor(type: Int) {
        this.type = type
    }

    var hasOmniAudio:Boolean
        get() = _hasOmniAudio
        set(value) {
            _hasOmniAudio = value
        }

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

    var showThinking: Boolean = true

    var thinkingFinishedTime = -1L

    fun toggleThinking() {
        showThinking = !showThinking
    }

    companion object {
        // 消息类型常量（替代已删除的 ChatViewHolders）
        const val USER = 0       // 用户消息
        const val ASSISTANT = 1  // AI 回复
        const val SYSTEM = 2     // 系统消息

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

package com.alibaba.mnnllm.android.model

import java.util.Locale
import com.alibaba.mnnllm.android.modelist.ModelListManager

/**
 * ModelTypeUtils：模型类型判断工具类
 *
 * 一句话：根据模型名称或 ID 判断它是什么类型的模型。
 * 生活类比：就像看车牌号判断车辆类型——"京A"是北京的，"沪B"是上海的。
 * 技术细节：通过字符串匹配（contains）判断模型名称中是否包含特定关键词，
 *           如 "vl" 表示视觉模型，"audio" 表示音频模型，"diffusion" 表示图像生成模型。
 *
 * 为什么需要这个？
 * - ChatService 创建会话时，需要知道模型类型来决定创建 LlmSession 还是 DiffusionSession
 * - 不同类型的模型有不同的输入输出格式
 */
object ModelTypeUtils {

    /**
     * isAudioModel：是否是音频模型
     * 模型名包含 "audio" 或者是 Omni 模型
     */
    fun isAudioModel(modelId: String): Boolean {
        // .lowercase(Locale.getDefault())：把字符串转成小写（不区分大小写匹配）
        return modelId.lowercase(Locale.getDefault()).contains("audio") || isOmni(modelId)
                || ModelListManager.isAudioModel(modelId)
    }

    /**
     * isMultiModalModel：是否是多模态模型（能处理图片/音频/视频等多种输入）
     */
    fun isMultiModalModel(modelName: String): Boolean {
        return isAudioModel(modelName) || isVisualModel(modelName) || isDiffusionModel(modelName) || isOmni(modelName)
    }

    /**
     * isQnnModel：是否是高通 QNN NPU 模型
     */
    fun isQnnModel(modelId: String): Boolean {
        val normalizedId = modelId.lowercase(Locale.getDefault())
        if (modelId.startsWith("local/") && normalizedId.contains("qnn")) {
            return true
        }
        val tags = ModelListManager.getModelTags(modelId)
        if (isQnnModel(tags)) {
            return true
        }
        return false
    }

    /**
     * isDiffusionModel：是否是图像生成模型（Stable Diffusion / Sana）
     */
    fun isDiffusionModel(modelName: String): Boolean {
        val lower = modelName.lowercase(Locale.getDefault())
        return lower.contains("stable-diffusion") || lower.contains("sana")
    }

    /**
     * isSanaModel：是否是 Sana 模型（一种高效的图像生成模型）
     */
    fun isSanaModel(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("sana")
    }

    fun requiresFaceImageInput(modelName: String): Boolean {
        return isSanaModel(modelName)
    }

    /**
     * isVisualModel：是否是视觉语言模型（VL = Vision-Language）
     * 模型名包含 "vl" 或 "visual"，或者是 Omni 模型
     */
    fun isVisualModel(modelId: String): Boolean {
        return modelId.lowercase(Locale.getDefault()).contains("vl") || isOmni(modelId) ||
                ModelListManager.isVisualModel(modelId) || isSanaModel(modelId)
    }

    fun isVideoModel(modelId: String): Boolean {
        return modelId.lowercase(Locale.getDefault()).contains("video") ||
                ModelListManager.isVideoModel(modelId)
    }

    /**
     * isR1Model：是否是 DeepSeek-R1 模型（带深度思考能力）
     */
    fun isR1Model(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("deepseek-r1")
    }

    /**
     * isOmni：是否是 Omni 模型（全模态，同时支持文本+音频输入输出）
     */
    fun isOmni(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("omni")
    }

    fun isSupportThinkingSwitchByTags(extraTags: List<String>): Boolean {
        return extraTags.any { it.equals("ThinkingSwitch", ignoreCase = true) }
    }

    fun isOpenClWarningByExtraTags(extraTags: List<String>): Boolean {
        return extraTags.any {
            it.equals("opencl_warning", ignoreCase = true) ||
                it.equals("OpenCLWarning", ignoreCase = true)
        }
    }

    fun isQnnModel(tags: List<String>): Boolean {
        // any {}：检查列表中是否有至少一个元素满足条件
        return tags.any { it.equals("QNN", ignoreCase = true) }
    }

    fun supportAudioOutput(modelName: String): Boolean {
        return isOmni(modelName)
    }

    fun isTtsModel(modelName: String): Boolean {
        return modelName.lowercase(Locale.getDefault()).contains("bert-vits") ||
               modelName.lowercase(Locale.getDefault()).contains("tts")
    }

    fun isTtsModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("TTS", ignoreCase = true) }
    }

    fun isAsrModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("ASR", ignoreCase = true) }
    }

    fun isThinkingModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Think", ignoreCase = true) }
    }

    fun isVisualModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Vision", ignoreCase = true) }
    }

    fun isVideoModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Video", ignoreCase = true) }
    }

    fun isAudioModelByTags(tags: List<String>): Boolean {
        return tags.any { it.equals("Audio", ignoreCase = true) }
    }
}

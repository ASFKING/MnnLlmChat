package com.poc.ondevice.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * VisionEngine：多模态视觉推理引擎
 *
 * 职责：
 * - 封装 MNN 的多模态视觉推理能力
 * - 图片预处理（保存到 App 私有目录）
 * - 构建包含图片的 prompt（<img>path</img> 格式）
 * - 流式生成图片描述/回答
 *
 * 一句话定义：让 LLM 能"看"图片并回答关于图片的问题。
 *
 * 生活类比：就像给一个只懂文字的朋友发一张照片，
 *           然后问"照片里有什么？"——朋友用文字描述他"看到"的内容。
 *
 * MNN 多模态的工作原理：
 * 1. 用户选一张图片，保存到 App 私有目录
 * 2. 构建 prompt："<img>/path/to/image.jpg</img>描述这张图片"
 * 3. 调用 LlmSession.generate()，传入这个 prompt
 * 4. C++ 层的 PromptProcessor 解析 <img> 标签，加载图片
 * 5. 图片经过视觉编码器（Vision Encoder）转成向量
 * 6. 视觉向量和文字向量一起送入 LLM
 * 7. LLM 综合理解后生成回答
 *
 * 为什么需要保存到 App 私有目录？
 * Android 的图片选择器返回的是 content:// URI，不是直接的文件路径。
 * MNN 的 C++ 层需要文件路径才能加载图片。
 * 所以我们需要先把图片复制到 App 的 filesDir 里，拿到绝对路径。
 */
class VisionEngine(
    /** llmEngine：复用已有的 LLM 引擎（不单独加载模型） */
    private val llmEngine: LLMEngine
) {

    companion object {
        private const val TAG = "VisionEngine"

        /** 图片存储子目录 */
        private const val IMAGE_DIR = "images"

        /** 最大图片尺寸（像素），超过会缩放 */
        private const val MAX_IMAGE_SIZE = 1024
    }

    // ==================== 状态 ====================

    /** isReady：引擎是否可用（LLM 已加载） */
    val isReady: Boolean get() = llmEngine.isLoaded

    // ==================== 图片预处理 ====================

    /**
     * 保存图片到 App 私有目录，返回绝对路径
     *
     * 为什么需要这一步？
     * Android 图片选择器返回 content:// URI（内容提供者 URI），
     * 不是直接的文件路径。MNN 的 C++ 层需要文件路径。
     * 所以我们需要先把图片复制到 App 的 filesDir。
     *
     * @param sourceFile 源图片文件
     * @param context    Android Context（用于获取 filesDir）
     * @return 保存后的绝对路径
     */
    suspend fun saveImageToPrivateDir(sourceFile: File, context: android.content.Context): String? =
        withContext(Dispatchers.IO) {
            try {
                // 创建图片存储目录
                val imageDir = File(context.filesDir, IMAGE_DIR)
                imageDir.mkdirs()

                // 生成唯一文件名（避免冲突）
                val destFile = File(imageDir, "img_${System.currentTimeMillis()}.jpg")

                // 如果图片太大，先缩放再保存
                val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
                if (bitmap == null) {
                    Log.e(TAG, "无法解码图片: ${sourceFile.absolutePath}")
                    return@withContext null
                }

                val scaledBitmap = scaleBitmap(bitmap)
                FileOutputStream(destFile).use { out ->
                    // compress() 把 Bitmap 压缩写入输出流
                    // Bitmap.CompressFormat.JPEG：JPEG 格式
                    // 85：质量（0-100），85 是性价比最好的平衡点
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }

                // 回收 Bitmap 释放内存
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                bitmap.recycle()

                Log.d(TAG, "图片已保存: ${destFile.absolutePath} (${destFile.length() / 1024}KB)")
                destFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "保存图片失败", e)
                null
            }
        }

    /**
     * 缩放 Bitmap 到最大尺寸
     *
     * 为什么要缩放？
     * 1. 大图片占用大量内存（一张 4000x3000 的图片 = 48MB 内存）
     * 2. MNN 视觉模型的输入尺寸通常不大（如 448x448），大图会被缩放
     * 3. 提前缩放可以减少 JNI 层的内存压力
     *
     * @param bitmap 原始 Bitmap
     * @return 缩放后的 Bitmap（如果原始尺寸 OK，返回原对象）
     */
    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= MAX_IMAGE_SIZE && height <= MAX_IMAGE_SIZE) {
            return bitmap  // 不需要缩放
        }

        // 计算缩放比例，保持宽高比
        val scale = MAX_IMAGE_SIZE.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        // createScaledBitmap：创建缩放后的新 Bitmap
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    // ==================== 推理 ====================

    /**
     * 图片理解：输入图片路径 + 问题，流式输出回答
     *
     * 核心原理：
     * MNN 的 C++ 层会在 prompt 中查找 <img>path</img> 标签，
     * 自动加载图片并经过视觉编码器处理。
     * 我们只需要把图片路径嵌入 prompt 就行。
     *
     * @param imagePath 图片的绝对路径（必须是 App 可访问的路径）
     * @param question  用户的问题（如"这是什么？"、"描述这张图片"）
     * @return 流式输出的 token Flow
     */
    fun understand(imagePath: String, question: String): Flow<String> {
        if (!isReady) {
            Log.e(TAG, "VisionEngine 未就绪（LLM 未加载）")
            // 返回一个只发错误消息的 Flow
            return channelFlow { send("错误：LLM 模型未加载") }
        }

        // 构建多模态 prompt
        // <img>path</img> 是 MNN 的图片标签格式
        // C++ 层的 PromptProcessor 会解析这个标签，加载图片
        val prompt = "<img>$imagePath</img>$question"

        Log.d(TAG, "多模态推理: $question (图片: $imagePath)")

        // 复用 LLMEngine 的 generateStream
        // 底层调用 LlmSession.submitNative()，
        // C++ 层检测到 <img> 标签后自动走多模态路径
        return llmEngine.generateStream(prompt)
    }

    /**
     * 图片描述：生成图片的详细描述（使用默认 prompt）
     *
     * @param imagePath 图片的绝对路径
     * @return 流式输出的描述文本
     */
    fun describeImage(imagePath: String): Flow<String> {
        return understand(imagePath, "请详细描述这张图片的内容。")
    }

    /**
     * 释放资源（目前 VisionEngine 不持有独立资源，委托给 LLMEngine）
     */
    fun release() {
        // VisionEngine 不持有独立的模型资源
        // 图片理解复用 LLMEngine 的模型
        Log.d(TAG, "VisionEngine released (no-op, uses LLMEngine)")
    }
}

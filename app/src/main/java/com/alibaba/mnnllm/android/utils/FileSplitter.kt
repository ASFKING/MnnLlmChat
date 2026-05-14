package com.alibaba.mnnllm.android.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * FileSplitter：文件分片工具
 *
 * 一句话：把大文件拆成小块，或者把小块合并回大文件。
 * 生活类比：就像搬家时把大件家具拆开运输，到新家再组装。
 *
 * 为什么需要分片？
 * - GitHub 单文件上传限制 100MB，模型文件可能 >1GB
 * - 某些存储系统对单文件大小有限制
 * - 分片下载支持断点续传（下载失败只需重新下载失败的那一片）
 *
 * 文件格式：
 * - 原始文件：model.bin（1.5GB）
 * - 分片后：model.bin.part1（1GB）+ model.bin.part2（0.5GB）
 * - 元数据：splits_info.json（记录分片信息）
 */
object FileSplitter {
    private const val TAG = "FileSplitter"

    // MAX_CHUNK_SIZE：每个分片的最大大小（1GB）
    // const val 必须是编译时常量
    const val MAX_CHUNK_SIZE = 1024 * 1024 * 1024L // 1GB

    /**
     * SplitInfo：分片信息（序列化为 splits_info.json）
     *
     * @param originalFileName 原始文件名
     * @param originalFileSize 原始文件大小
     * @param chunkSize 每个分片的最大大小
     * @param totalChunks 总分片数
     * @param chunks 各分片的详细信息
     */
    data class SplitInfo(
        val originalFileName: String,
        val originalFileSize: Long,
        val chunkSize: Long,
        val totalChunks: Int,
        val chunks: List<ChunkInfo>
    )

    /**
     * ChunkInfo：单个分片的信息
     *
     * @param chunkIndex 分片序号（从 1 开始）
     * @param chunkFileName 分片文件名（如 "model.bin.part1"）
     * @param chunkSize 分片大小（字节）
     * @param checksum 校验和（可选）
     */
    data class ChunkInfo(
        val chunkIndex: Int,
        val chunkFileName: String,
        val chunkSize: Long,
        val checksum: String? = null
    )

    /**
     * splitFile：把大文件拆分成多个小分片
     *
     * @param sourceFile 要拆分的源文件
     * @param outputDir 分片输出目录
     * @param chunkSize 每个分片的最大大小
     * @return SplitInfo（分片信息），如果文件不需要拆分返回 null
     */
    fun splitFile(
        sourceFile: File,
        outputDir: File,
        chunkSize: Long = MAX_CHUNK_SIZE
    ): SplitInfo? {
        if (!sourceFile.exists()) {
            Log.e(TAG, "Source file does not exist: ${sourceFile.absolutePath}")
            return null
        }

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val fileSize = sourceFile.length()
        // 如果文件已经够小，不需要拆分
        if (fileSize <= chunkSize) {
            Log.d(TAG, "File ${sourceFile.name} is already small enough (${fileSize} bytes), no splitting needed")
            return null
        }

        // 计算需要多少个分片
        // (fileSize + chunkSize - 1) / chunkSize 是向上取整的除法
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
        val chunks = mutableListOf<ChunkInfo>()

        Log.d(TAG, "Splitting file ${sourceFile.name} (${fileSize} bytes) into $totalChunks chunks")

        try {
            // FileInputStream.use {}：自动关闭文件流（类似 try-with-resources）
            FileInputStream(sourceFile).use { inputStream ->
                val buffer = ByteArray(chunkSize.toInt())

                for (chunkIndex in 0 until totalChunks) {
                    val chunkFileName = "${sourceFile.name}.part${chunkIndex + 1}"
                    val chunkFile = File(outputDir, chunkFileName)

                    // 读取一个分片大小的数据
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    // 写入分片文件
                    FileOutputStream(chunkFile).use { outputStream ->
                        outputStream.write(buffer, 0, bytesRead)
                    }

                    val actualChunkSize = bytesRead.toLong()
                    val chunkInfo = ChunkInfo(
                        chunkIndex = chunkIndex + 1,
                        chunkFileName = chunkFileName,
                        chunkSize = actualChunkSize
                    )
                    chunks.add(chunkInfo)

                    Log.d(TAG, "Created chunk $chunkFileName (${actualChunkSize} bytes)")
                }
            }

            val splitInfo = SplitInfo(
                originalFileName = sourceFile.name,
                originalFileSize = fileSize,
                chunkSize = chunkSize,
                totalChunks = totalChunks,
                chunks = chunks
            )

            // 保存分片信息到 JSON 文件
            val splitInfoFile = File(outputDir, "splits_info.json")
            val gson = Gson()
            val json = gson.toJson(splitInfo)
            splitInfoFile.writeText(json)

            Log.d(TAG, "Split info saved to ${splitInfoFile.absolutePath}")
            return splitInfo

        } catch (e: IOException) {
            Log.e(TAG, "Failed to split file ${sourceFile.name}", e)
            return null
        }
    }

    /**
     * mergeFiles：把分片合并回原始文件
     *
     * @param splitInfo 分片信息
     * @param chunksDir 分片文件所在目录
     * @param outputFile 合并后的输出文件
     * @return true = 合并成功
     */
    fun mergeFiles(splitInfo: SplitInfo, chunksDir: File, outputFile: File): Boolean {
        if (!chunksDir.exists()) {
            Log.e(TAG, "Chunks directory does not exist: ${chunksDir.absolutePath}")
            return false
        }

        Log.d(TAG, "Merging ${splitInfo.totalChunks} chunks into ${outputFile.name}")

        // 按序号排序分片
        // sortedBy {}：按 lambda 返回的值升序排序
        val sortedChunks = splitInfo.chunks.sortedBy { it.chunkIndex }

        // 预验证所有分片文件
        var totalExpectedSize = 0L
        for (chunkInfo in sortedChunks) {
            val chunkFile = File(chunksDir, chunkInfo.chunkFileName)
            if (!chunkFile.exists()) {
                Log.e(TAG, "Chunk file does not exist: ${chunkFile.absolutePath}")
                return false
            }
            val actualSize = chunkFile.length()
            if (actualSize != chunkInfo.chunkSize) {
                Log.e(TAG, "Chunk file ${chunkInfo.chunkFileName} size mismatch: expected ${chunkInfo.chunkSize}, actual $actualSize")
                return false
            }
            totalExpectedSize += chunkInfo.chunkSize
        }

        if (totalExpectedSize != splitInfo.originalFileSize) {
            Log.e(TAG, "Total chunk sizes ($totalExpectedSize) do not match expected original size (${splitInfo.originalFileSize})")
            return false
        }

        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            var totalBytesWritten = 0L

            FileOutputStream(outputFile).use { outputStream ->
                for (chunkInfo in sortedChunks) {
                    val chunkFile = File(chunksDir, chunkInfo.chunkFileName)
                    Log.d(TAG, "Merging chunk ${chunkInfo.chunkFileName} (${chunkInfo.chunkSize} bytes)")

                    FileInputStream(chunkFile).use { inputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var chunkBytesWritten = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            chunkBytesWritten += bytesRead
                            totalBytesWritten += bytesRead
                        }

                        if (chunkBytesWritten != chunkInfo.chunkSize) {
                            Log.e(TAG, "Chunk ${chunkInfo.chunkFileName} bytes written ($chunkBytesWritten) != expected (${chunkInfo.chunkSize})")
                            return false
                        }
                    }
                }
            }

            // 验证合并后的文件大小
            val mergedFileSize = outputFile.length()
            if (mergedFileSize != splitInfo.originalFileSize) {
                Log.e(TAG, "CRITICAL: Merged file size ($mergedFileSize) does not match original size (${splitInfo.originalFileSize})")
                outputFile.delete()
                return false
            }

            Log.d(TAG, "Successfully merged file ${outputFile.name} (${mergedFileSize} bytes)")
            return true

        } catch (e: IOException) {
            Log.e(TAG, "Failed to merge files", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return false
        }
    }

    /**
     * loadSplitInfo：从 JSON 文件加载分片信息
     */
    fun loadSplitInfo(splitInfoFile: File): SplitInfo? {
        if (!splitInfoFile.exists()) {
            return null
        }
        return try {
            val json = splitInfoFile.readText()
            Gson().fromJson(json, SplitInfo::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load split info from ${splitInfoFile.absolutePath}", e)
            null
        }
    }

    /**
     * needsMerging：检查目录中是否有需要合并的分片文件
     */
    fun needsMerging(modelDir: File): Boolean {
        val splitInfoFile = File(modelDir, "splits_info.json")
        return splitInfoFile.exists()
    }

    /**
     * mergeAllSplitFiles：合并目录中所有需要合并的分片文件
     *
     * @return true = 全部合并成功
     */
    fun mergeAllSplitFiles(modelDir: File): Boolean {
        val splitInfoFile = File(modelDir, "splits_info.json")
        val splitInfo = loadSplitInfo(splitInfoFile) ?: return true

        Log.d(TAG, "Merging split files in ${modelDir.absolutePath}")

        // 找出所有需要合并的原始文件名
        val filesToMerge = mutableListOf<String>()
        for (chunkInfo in splitInfo.chunks) {
            // 去掉 .partX 后缀得到原始文件名
            val originalFileName = chunkInfo.chunkFileName.replace(Regex("\\.part\\d+$"), "")
            if (!filesToMerge.contains(originalFileName)) {
                filesToMerge.add(originalFileName)
            }
        }

        var allMerged = true
        val mergedFiles = mutableListOf<String>()
        val failedFiles = mutableListOf<String>()

        for (originalFileName in filesToMerge) {
            val outputFile = File(modelDir, originalFileName)
            if (outputFile.exists()) {
                val existingSize = outputFile.length()
                if (existingSize == splitInfo.originalFileSize) {
                    Log.d(TAG, "Existing file $originalFileName has correct size, skipping merge")
                    mergedFiles.add(originalFileName)
                    continue
                } else {
                    outputFile.delete()
                }
            }

            val merged = mergeFiles(splitInfo, modelDir, outputFile)
            if (!merged) {
                failedFiles.add(originalFileName)
                allMerged = false
            } else {
                mergedFiles.add(originalFileName)
                // 合并成功后删除分片文件
                deletePartFiles(modelDir, originalFileName, splitInfo)
            }
        }

        // 全部合并成功后删除 splits_info.json
        if (allMerged && mergedFiles.isNotEmpty()) {
            try {
                splitInfoFile.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete split info file", e)
            }
        }

        return allMerged
    }

    /**
     * deletePartFiles：删除分片文件
     *
     * 在合并文件成功且大小正确后，删除原始分片文件释放空间
     */
    private fun deletePartFiles(modelDir: File, originalFileName: String, splitInfo: SplitInfo) {
        try {
            val mergedFile = File(modelDir, originalFileName)
            if (!mergedFile.exists()) {
                Log.e(TAG, "CRITICAL: Merged file $originalFileName does not exist, cannot delete part files")
                return
            }
            val mergedSize = mergedFile.length()
            if (mergedSize != splitInfo.originalFileSize) {
                Log.e(TAG, "CRITICAL: Merged file has wrong size, cannot delete part files")
                return
            }

            val partFiles = splitInfo.chunks.filter {
                it.chunkFileName.startsWith(originalFileName) && it.chunkFileName.matches(Regex(".*\\.part\\d+$"))
            }

            for (chunkInfo in partFiles) {
                val partFile = File(modelDir, chunkInfo.chunkFileName)
                if (partFile.exists()) {
                    partFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting part files for $originalFileName", e)
        }
    }
}

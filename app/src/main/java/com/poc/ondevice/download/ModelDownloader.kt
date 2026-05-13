package com.poc.ondevice.download

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ModelDownloader：模型下载器
 *
 * 负责从 ModelScope 下载 MNN 模型到手机本地存储
 *
 * 工作流程（类比：网购收快递）：
 * 1. 查询仓库里有哪些文件 → 相当于查看订单里的商品清单
 * 2. 逐个下载文件 → 相当于快递员一个个送包裹
 * 3. 存到本地目录 → 相当于把包裹放进储物柜
 *
 * 为什么用 ModelScope 而不是 HuggingFace：
 * - ModelScope 是阿里云的，国内访问快
 * - HuggingFace 在国内经常被墙
 *
 * @param context Android Context，用于获取 App 私有存储路径
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"

        // ModelScope API 基础地址
        // 调用格式：GET /api/v1/models/{group}/{repo}/repo/files?Recursive=1
        // 返回仓库中所有文件的列表（含路径、大小、SHA256）
        private const val MS_API_BASE = "https://modelscope.cn/api/v1/models"

        // 文件下载地址格式
        // 调用格式：GET /api/v1/models/{group}/{repo}/repo?FilePath={path}
        // 返回文件的二进制内容
        private const val MS_DOWNLOAD_BASE = "https://modelscope.cn/api/v1/models"
    }

    // 模型存储根目录：/data/data/com.poc.ondevice/files/models/
    // context.filesDir → App 私有目录的 files 子目录
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    // Gson 实例：用于 JSON 解析
    // Gson 是 Google 的 JSON 序列化/反序列化库
    private val gson = Gson()

    /**
     * 获取模型本地存储路径
     *
     * @param modelDirName 模型目录名（如 "Qwen3-1.7B-MNN"）
     * @return 模型在手机上的完整路径
     */
    fun getModelPath(modelDirName: String): File {
        return File(modelsDir, modelDirName)
    }

    /**
     * 检查模型是否已下载
     *
     * @param modelDirName 模型目录名
     * @return true = 已下载，false = 未下载
     */
    fun isModelDownloaded(modelDirName: String): Boolean {
        val dir = getModelPath(modelDirName)
        // 检查目录存在且不为空（至少有一个文件）
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    /**
     * 下载模型（挂起函数）
     *
     * suspend fun：Kotlin 协程的挂起函数
     * - 可以在执行过程中"暂停"（比如等网络响应），不阻塞主线程
     * - 必须在协程作用域内调用（比如 lifecycleScope.launch {}）
     * - withContext(Dispatchers.IO)：切换到 IO 线程池执行网络/文件操作
     *
     * @param model 要下载的模型配置
     * @param onProgress 进度回调：(已下载字节, 总字节, 当前文件名) -> Unit
     * @param onComplete 下载完成回调
     * @param onError 下载失败回调
     */
    suspend fun downloadModel(
        model: ModelEntry,
        onProgress: (downloaded: Long, total: Long, currentFile: String) -> Unit,
        onComplete: (modelPath: File) -> Unit,
        onError: (error: String) -> Unit
    ) {
        // withContext(Dispatchers.IO)：切换到 IO 线程池
        // 网络请求和文件写入是 IO 密集型操作，不能在主线程执行
        // 否则会触发 Android 的 NetworkOnMainThreadException
        withContext(Dispatchers.IO) {
            try {
                val modelDir = getModelPath(model.modelDirName)

                // 如果已下载，直接返回
                if (isModelDownloaded(model.modelDirName)) {
                    Log.d(TAG, "Model already downloaded: ${model.modelDirName}")
                    onComplete(modelDir)
                    return@withContext
                }

                // Step 1: 获取仓库文件列表
                Log.d(TAG, "Fetching file list for: ${model.modelId}")
                val files = fetchRepoFiles(model.modelId)
                if (files.isEmpty()) {
                    onError("获取文件列表失败：返回为空")
                    return@withContext
                }

                // 计算总大小
                val totalSize = files.sumOf { it.size }
                var downloadedSize = 0L

                // 创建模型目录
                modelDir.mkdirs()

                // Step 2: 逐个下载文件
                for (fileInfo in files) {
                    val targetFile = File(modelDir, fileInfo.path)
                    targetFile.parentFile?.mkdirs()

                    // 如果文件已存在且大小匹配，跳过（支持断点续传的基础）
                    if (targetFile.exists() && targetFile.length() == fileInfo.size) {
                        downloadedSize += fileInfo.size
                        continue
                    }

                    // 下载单个文件
                    downloadFile(
                        modelId = model.modelId,
                        filePath = fileInfo.path,
                        targetFile = targetFile,
                        onFileProgress = { fileDownloaded ->
                            onProgress(
                                downloadedSize + fileDownloaded,
                                totalSize,
                                fileInfo.path
                            )
                        }
                    )

                    downloadedSize += fileInfo.size
                }

                Log.d(TAG, "Model download complete: ${model.modelDirName}")
                onComplete(modelDir)

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                onError("下载失败: ${e.message}")
            }
        }
    }

    /**
     * 获取仓库文件列表
     *
     * 调用 ModelScope API 获取仓库中所有文件的信息
     * API 返回格式：
     * {
     *   "Code": 0,
     *   "Data": {
     *     "Files": [
     *       { "Path": "config.json", "Size": 1234, "Sha256": "abc...", "Type": "blob" },
     *       { "Path": "subdir/file.bin", "Size": 5678, "Sha256": "def...", "Type": "blob" }
     *     ]
     *   }
     * }
     *
     * @param modelId 模型 ID，格式 "MNN/Qwen3-1.7B-MNN"
     * @return 文件信息列表
     */
    private suspend fun fetchRepoFiles(modelId: String): List<RepoFileInfo> {
        return withContext(Dispatchers.IO) {
            // split("/") 把 "MNN/Qwen3-1.7B-MNN" 拆成 ["MNN", "Qwen3-1.7B-MNN"]
            val parts = modelId.split("/")
            require(parts.size == 2) { "modelId 格式错误，应为 'group/repo'" }

            val group = parts[0]  // "MNN"
            val repo = parts[1]   // "Qwen3-1.7B-MNN"

            // 拼接 API URL
            // 结果：https://modelscope.cn/api/v1/models/MNN/Qwen3-1.7B-MNN/repo/files?Recursive=1
            val url = URL("$MS_API_BASE/$group/$repo/repo/files?Recursive=1")

            // 打开 HTTP 连接
            // HttpURLConnection 是 Java 标准库的 HTTP 客户端
            // 虽然不如 OkHttp 功能丰富，但零依赖，够用
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000  // 连接超时 15 秒
            conn.readTimeout = 30000     // 读取超时 30 秒

            try {
                // 检查 HTTP 响应码
                if (conn.responseCode != 200) {
                    throw Exception("API 请求失败: HTTP ${conn.responseCode}")
                }

                // 读取响应体
                // conn.inputStream → 网络响应的输入流
                // bufferedReader() → 包装成带缓冲的字符流（提高读取效率）
                // readText() → 一次性读取全部文本
                val responseText = conn.inputStream.bufferedReader().readText()

                // JSON 反序列化：把 JSON 字符串转成 Kotlin 对象
                // Gson().fromJson(json, Type) → 自动映射字段名
                val response = gson.fromJson(responseText, MsRepoResponse::class.java)

                // 过滤掉 tree 类型（目录），只保留实际文件
                // ?. 安全调用：如果 response.Data 为 null，整个表达式返回 null
                // ?: Elvis 操作符：如果左边为 null，返回右边的默认值
                response.data?.files?.filter { it.type != "tree" } ?: emptyList()
            } finally {
                // finally 块：无论成功还是失败都会执行
                // 断开连接，释放网络资源
                conn.disconnect()
            }
        }
    }

    /**
     * 下载单个文件
     *
     * @param modelId 模型 ID
     * @param filePath 文件在仓库中的相对路径
     * @param targetFile 本地目标文件
     * @param onFileProgress 文件内进度回调
     */
    private suspend fun downloadFile(
        modelId: String,
        filePath: String,
        targetFile: File,
        onFileProgress: (downloaded: Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val parts = modelId.split("/")
            val group = parts[0]
            val repo = parts[1]

            // 文件下载 URL
            // 结果：https://modelscope.cn/api/v1/models/MNN/Qwen3-1.7B-MNN/repo?FilePath=config.json
            val encodedPath = java.net.URLEncoder.encode(filePath, "UTF-8")
            val url = URL("$MS_DOWNLOAD_BASE/$group/$repo/repo?FilePath=$encodedPath")

            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            // 设置 User-Agent，某些 CDN 不接受默认的 Java UA
            conn.setRequestProperty("User-Agent", "MnnLlmChat-PoC/1.0")

            try {
                if (conn.responseCode != 200) {
                    throw Exception("下载失败: HTTP ${conn.responseCode} for $filePath")
                }

                val inputStream = conn.inputStream
                val outputStream = FileOutputStream(targetFile)

                // 流式读写：每次读 8KB，避免一次性加载大文件到内存
                // 类比：搬砖时一块块搬，而不是一次搬整面墙
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var bytesRead: Int

                // inputStream.read(buffer) → 读取数据到 buffer，返回实际读取的字节数
                // -1 表示读取结束
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    onFileProgress(downloaded)
                }

                outputStream.flush()  // 刷新缓冲区，确保所有数据写入磁盘
                outputStream.close()
                inputStream.close()

            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 删除已下载的模型
     *
     * File.deleteRecursively()：递归删除目录及其所有内容
     * 类比：rm -rf 命令
     */
    fun deleteModel(modelDirName: String) {
        val dir = getModelPath(modelDirName)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.d(TAG, "Model deleted: $modelDirName")
        }
    }

    // ========== 数据类：ModelScope API 响应结构 ==========

    /**
     * MsRepoResponse：ModelScope API 的文件列表响应
     *
     * 数据类（data class）：Kotlin 自动生成 equals/hashCode/toString/copy
     * @SerializedName：Gson 注解，指定 JSON 字段名和 Kotlin 属性名的映射
     * 因为 ModelScope API 返回的 JSON 用大写开头（如 "Code"），而 Kotlin 惯例用小写
     */
    data class MsRepoResponse(
        @SerializedName("Code") val code: Int = -1,
        @SerializedName("Data") val data: RepoData? = null,
        @SerializedName("Message") val message: String? = null
    )

    data class RepoData(
        @SerializedName("Files") val files: List<RepoFileInfo>? = null
    )

    /**
     * RepoFileInfo：仓库中的单个文件信息
     *
     * @param path   文件相对路径（如 "config.json" 或 "subdir/model.bin"）
     * @param size   文件大小（字节）
     * @param sha256 文件 SHA256 哈希值（可用于校验完整性）
     * @param type   文件类型："blob"=普通文件，"tree"=目录
     */
    data class RepoFileInfo(
        @SerializedName("Path") val path: String = "",
        @SerializedName("Size") val size: Long = 0,
        @SerializedName("Sha256") val sha256: String? = null,
        @SerializedName("Type") val type: String = ""
    )
}

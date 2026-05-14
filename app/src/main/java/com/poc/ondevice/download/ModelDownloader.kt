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

    // companion object：Kotlin 的伴生对象，类似 Java 的 static 成员
    // 里面的属性和方法属于类本身，不属于实例
    companion object {
        // private const val：编译时常量，只能在 companion object 中声明
        // const 要求值必须在编译时就能确定（不能是变量或函数返回值）
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
    // .apply { mkdirs() } → 创建 File 对象的同时，如果目录不存在就创建它
    // apply 是 Kotlin 的作用域函数，在对象上调用 lambda，返回对象本身
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
        // File(parent, child)：在 parent 目录下创建子路径
        // 结果：/data/data/com.poc.ondevice/files/models/Qwen3-1.7B-MNN
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
        // exists()：检查文件/目录是否存在
        // listFiles()：列出目录下的所有文件，返回 Array<File>?（目录为空或不存在时返回 null）
        // ?.isNotEmpty()：安全调用 + Elvis 操作符，null 时返回 null
        // == true：因为整个表达式可能为 null（?.链式调用），所以用 == true 而不是直接返回
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
        // Dispatchers.IO 专门用于网络/文件等 IO 操作，线程池较大
        withContext(Dispatchers.IO) {
            // try-catch：Kotlin 的异常处理
            // try 块中的代码如果抛出异常，会被 catch 块捕获
            try {
                val modelDir = getModelPath(model.modelDirName)

                // 如果已下载，直接返回
                if (isModelDownloaded(model.modelDirName)) {
                    Log.d(TAG, "Model already downloaded: ${model.modelDirName}")
                    onComplete(modelDir)
                    // return@withContext：从 lambda 中返回（不是从函数返回）
                    // 类比：从 withContext 这个"子任务"中退出
                    return@withContext
                }

                // Step 1: 获取仓库文件列表
                Log.d(TAG, "Fetching file list for: ${model.modelId}")
                val files = fetchRepoFiles(model.modelId)
                if (files.isEmpty()) {
                    onError("获取文件列表失败：返回为空")
                    return@withContext
                }

                // sumOf {}：对列表中每个元素执行 lambda，把结果加起来
                // 这里是计算所有文件的总大小
                val totalSize = files.sumOf { it.size }
                var downloadedSize = 0L

                // 创建模型目录（包括所有父目录）
                modelDir.mkdirs()

                // Step 2: 逐个下载文件
                for (fileInfo in files) {
                    // 目标文件路径：模型目录 + 文件在仓库中的相对路径
                    val targetFile = File(modelDir, fileInfo.path)
                    // parentFile：获取父目录
                    // ?.mkdirs()：安全调用，如果父目录不为 null 就创建
                    targetFile.parentFile?.mkdirs()

                    // 如果文件已存在且大小匹配，跳过（支持断点续传的基础）
                    if (targetFile.exists() && targetFile.length() == fileInfo.size) {
                        downloadedSize += fileInfo.size
                        continue  // continue：跳过本次循环，进入下一次
                    }

                    // 下载单个文件
                    downloadFile(
                        modelId = model.modelId,
                        filePath = fileInfo.path,
                        targetFile = targetFile,
                        onFileProgress = { fileDownloaded ->
                            // 更新总进度：已下载的文件大小 + 当前文件已下载的大小
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
                // 捕获所有异常，通知调用方
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
     *       { "Path": "subdir/file.bin", "Size": 5678, "Sha256": "def...", "Type": "tree" }
     *     ]
     *   }
     * }
     *
     * @param modelId 模型 ID，格式 "MNN/Qwen3-1.7B-MNN"
     * @return 文件信息列表
     */
    private suspend fun fetchRepoFiles(modelId: String): List<RepoFileInfo> {
        // withContext(Dispatchers.IO)：确保网络操作在 IO 线程执行
        return withContext(Dispatchers.IO) {
            // split("/") 把 "MNN/Qwen3-1.7B-MNN" 拆成 ["MNN", "Qwen3-1.7B-MNN"]
            val parts = modelId.split("/")
            // require()：如果条件不满足，抛出 IllegalArgumentException
            // 与 check() 的区别：require 用于参数校验，check 用于状态校验
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
            // as HttpURLConnection：类型转换（Java 中用 (HttpURLConnection) cast）
            conn.requestMethod = "GET"              // HTTP 方法
            conn.connectTimeout = 15000              // 连接超时 15 秒
            conn.readTimeout = 30000                 // 读取超时 30 秒

            try {
                // 检查 HTTP 响应码
                // 200 = 成功，其他都是错误
                if (conn.responseCode != 200) {
                    throw Exception("API 请求失败: HTTP ${conn.responseCode}")
                }

                // 读取响应体
                // conn.inputStream → 网络响应的输入流（字节流）
                // bufferedReader() → 包装成带缓冲的字符流（提高读取效率）
                // readText() → 一次性读取全部文本
                val responseText = conn.inputStream.bufferedReader().readText()

                // JSON 反序列化：把 JSON 字符串转成 Kotlin 对象
                // Gson().fromJson(json, Type) → 自动映射字段名
                // 配合 @SerializedName 注解，可以处理 JSON 字段名与 Kotlin 属性名不同的情况
                val response = gson.fromJson(responseText, MsRepoResponse::class.java)

                // 过滤掉 tree 类型（目录），只保留实际文件
                // ?. 安全调用：如果 response.Data 为 null，整个表达式返回 null
                // ?: Elvis 操作符：如果左边为 null，返回右边的默认值
                // filter {}：过滤列表，只保留 lambda 返回 true 的元素
                response.data?.files?.filter { it.type != "tree" } ?: emptyList()
            } finally {
                // finally 块：无论成功还是失败都会执行
                // 断开连接，释放网络资源
                // 类比：用完水龙头后一定要关掉
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
            // URLEncoder.encode：把文件路径中的特殊字符编码成 URL 安全格式
            // 例如空格变成 %20，中文变成 %XX%XX
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
                // FileOutputStream：文件输出流，用于写文件
                val outputStream = FileOutputStream(targetFile)

                // 流式读写：每次读 8KB，避免一次性加载大文件到内存
                // 类比：搬砖时一块块搬，而不是一次搬整面墙
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var bytesRead: Int

                // inputStream.read(buffer) → 读取数据到 buffer，返回实际读取的字节数
                // -1 表示读取结束
                // .also { bytesRead = it } → 把 read 的返回值同时赋给 bytesRead
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    // write(buffer, 0, bytesRead) → 写入 bytesRead 个字节
                    // 不是写整个 buffer，因为最后一块可能不满 8KB
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
     * @SerializedName：Gson 注解，指定 JSON 字段名和 Kotlin 属性名的映射
     * 因为 ModelScope API 返回的 JSON 用大写开头（如 "Code"），而 Kotlin 惯例用小写
     * 注解告诉 Gson：JSON 里的 "Code" 对应 Kotlin 的 code 属性
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

package com.alibaba.mls.api.download

import java.io.File

/**
 * DownloadFileUtils：下载文件工具类
 *
 * 原始 MNN 项目中，提供文件下载和管理的工具方法。
 * PoC 阶段只用到 deleteDirectoryRecursively()，所以只实现这一个方法。
 */
object DownloadFileUtils {

    /**
     * 递归删除目录及其所有内容
     *
     * 用法：deleteDirectoryRecursively(File("/path/to/dir"))
     * 效果：删除该目录下的所有文件和子目录，最后删除目录本身
     */
    fun deleteDirectoryRecursively(dir: File): Boolean {
        if (!dir.exists()) return true
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                deleteDirectoryRecursively(child)
            }
        }
        return dir.delete()
    }
}

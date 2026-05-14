package com.alibaba.mls.api.download

import java.io.File

/**
 * DownloadFileUtils：下载文件工具类
 *
 * 一句话：提供文件操作的辅助方法。
 * PoC 阶段只用到 deleteDirectoryRecursively()。
 */
object DownloadFileUtils {

    /**
     * 递归删除目录及其所有内容
     *
     * 用法：deleteDirectoryRecursively(File("/path/to/dir"))
     * 效果：删除该目录下的所有文件和子目录，最后删除目录本身
     *
     * 递归原理：
     * 1. 如果是目录 → 先删除所有子文件/子目录
     * 2. 删除自身
     * 类比：拆楼时，先拆里面的房间，再拆大楼本身
     */
    fun deleteDirectoryRecursively(dir: File): Boolean {
        if (!dir.exists()) return true
        if (dir.isDirectory) {
            // listFiles()：列出目录下的所有文件和子目录
            // ?.forEach {}：安全遍历，如果为 null 就跳过
            dir.listFiles()?.forEach { child ->
                deleteDirectoryRecursively(child)  // 递归调用
            }
        }
        return dir.delete()
    }
}

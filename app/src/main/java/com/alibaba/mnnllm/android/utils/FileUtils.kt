package com.alibaba.mnnllm.android.utils

import java.io.File

/**
 * FileUtils：文件工具类
 *
 * 提供常用的文件操作辅助方法。
 */
object FileUtils {

    /**
     * 确保文件的父目录存在（不存在则创建）
     *
     * 为什么需要这个？
     * File.writeText() 在父目录不存在时会抛 FileNotFoundException
     * 所以写文件前先确保目录结构完整
     */
    fun ensureParentDirectoriesExist(file: File) {
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
    }
}

package com.alibaba.mnnllm.android.llm

/**
 * GenerateProgressListener：生成进度回调接口
 *
 * 一句话：LLM 每生成一个 token，就通过这个接口通知调用方。
 * 生活类比：就像打印机打一行通知你一行——不用等全部打完才看结果。
 * 技术细节：MNN 的 C++ 引擎在 decode 每个 token 后，调用 onProgress() 回调，
 *           把 token 文本传给 Kotlin 层。返回 true 表示"停止生成"，false 表示"继续"。
 *
 * 为什么用接口而不是 lambda？
 * - 这个接口会被 JNI（C++ 代码）回调，JNI 需要一个 Java 对象引用
 * - 接口比 lambda 更容易被 JNI 持有和调用
 */
interface GenerateProgressListener {

    /**
     * onProgress：每生成一个 token 时被调用
     *
     * @param progress 当前生成的 token 文本（可能为 null，表示生成结束）
     * @return false = 继续生成，true = 停止生成
     *
     * 业务逻辑：
     * - 正常生成时，progress 是一个 token（如 "你"、"好"、"，"）
     * - 生成结束时，progress 为 null
     * - 如果用户点击"停止"，可以在回调中返回 true 来中断生成
     */
    fun onProgress(progress: String?): Boolean
}

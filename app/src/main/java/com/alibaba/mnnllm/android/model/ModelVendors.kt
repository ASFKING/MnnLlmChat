package com.alibaba.mnnllm.android.model

/**
 * ModelVendors：模型厂商常量
 *
 * 一句话：定义了所有支持的模型厂商名称。
 * 用途：模型分类、UI 显示、搜索过滤。
 */
object ModelVendors {
    const val Qwen = "Qwen"           // 通义千问（阿里）
    const val DeepSeek = "DeepSeek"   // 深度求索
    const val Llama = "Llama"         // Meta Llama
    const val Smo = "Smo"             // SmoLM
    const val Phi = "Phi"             // Microsoft Phi
    const val Baichuan = "Baichuan"   // 百川智能
    const val Yi = "Yi"               // 零一万物
    const val Glm = "Glm"             // 智谱 GLM
    const val Jina = "Jina"           // Jina AI
    const val Internlm = "Internlm"   // 书生浦语
    const val Gemma = "Gemma"         // Google Gemma
    const val Lfm = "LFM"            // Liquid Foundation Models
    const val Mimo = "Mimo"           // 小米 MiMo
    const val FastVlm = "FastVlm"     // Apple FastVLM
    const val OpenElm = "OpenElm"     // Apple OpenELM
    const val Others = "Others"       // 其他

    // vendorList：所有厂商列表（用于 UI 筛选下拉框）
    val vendorList = listOf(
        Qwen, DeepSeek, Gemma, Lfm, Smo, FastVlm, Phi, Mimo,
        Llama, Yi, Glm, Jina, OpenElm, Internlm, Baichuan, Others
    )
}

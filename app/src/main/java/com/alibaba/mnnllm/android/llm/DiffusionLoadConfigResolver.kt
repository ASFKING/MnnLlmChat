package com.alibaba.mnnllm.android.llm

import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import com.google.gson.Gson

/**
 * DiffusionLoadConfigResolver：Diffusion 模型加载配置解析器
 *
 * 一句话：为 Diffusion 模型构建额外的配置 JSON。
 * 生活类比：就像点外卖时的"加料区"——主餐是 config.json，加料是这里生成的 extraConfig。
 *
 * Diffusion 模型（如 Stable Diffusion、Sana）除了基础配置外，
 * 还需要 diffusion_memory_mode 等专用参数，这个类负责组装这些参数。
 */
internal object DiffusionLoadConfigResolver {

    /**
     * 构建 Diffusion 模型的额外配置 JSON
     *
     * @param modelId 模型 ID
     * @param configPath 配置文件路径
     * @return JSON 字符串，传给 native 层
     */
    fun buildExtraConfigJson(modelId: String, configPath: String): String {
        // 加载配置：先尝试合并配置，失败则用默认配置
        val config = ModelConfig.loadMergedConfig(configPath, ModelConfig.getExtraConfigFile(modelId))
            ?: ModelConfig.loadDefaultConfig(configPath)
            ?: ModelConfig.defaultConfig

        // 构建额外配置 Map
        val configMap = hashMapOf<String, Any>(
            "diffusion_memory_mode" to (config.diffusionMemoryMode
                ?: ModelConfig.defaultConfig.diffusionMemoryMode
                ?: "0")
        )
        // Gson().toJson()：把 Map 序列化为 JSON 字符串
        return Gson().toJson(configMap)
    }
}

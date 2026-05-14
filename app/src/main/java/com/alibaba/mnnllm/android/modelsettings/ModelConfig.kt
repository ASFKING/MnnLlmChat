package com.alibaba.mnnllm.android.modelsettings

import android.util.Log
import com.alibaba.mls.api.ApplicationProvider
import com.alibaba.mls.api.download.ModelDownloadManager
import com.alibaba.mnnllm.android.model.ModelUtils
import com.alibaba.mnnllm.android.utils.FileUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import com.google.gson.annotations.SerializedName

/**
 * JinjaContext：Jinja 模板上下文配置
 * 用于控制模型的思考模式（Qwen3 的深度思考功能）
 */
data class JinjaContext(
    @SerializedName("enable_thinking") var enableThinking: Boolean = false
)

/**
 * Jinja：Jinja 模板配置
 */
data class Jinja(
    @SerializedName("context") var context: JinjaContext? = null
)

/**
 * ModelConfig：模型配置数据类
 *
 * 一句话：定义了 MNN 模型的所有可配置参数。
 * 生活类比：就像汽车的仪表盘——转速、油门、刹车等参数都在这里。
 *
 * config.json 文件中的字段映射到这个数据类的属性：
 * - "llm_model" → llmModel（模型文件名）
 * - "backend_type" → backendType（推理后端）
 * - "temperature" → temperature（温度参数）
 * - ...
 *
 * @SerializedName 注解：Gson 的字段映射注解
 * JSON 中的 "llm_model" 对应 Kotlin 的 llmModel 属性
 */
data class ModelConfig(
    @SerializedName("llm_model") var llmModel: String?,          // 模型文件名
    @SerializedName("llm_weight") var llmWeight: String?,        // 权重文件名
    @SerializedName("backend_type") var backendType: String?,    // 推理后端：cpu/opencl/vulkan
    @SerializedName("thread_num") var threadNum: Int?,           // CPU 线程数
    @SerializedName("precision") var precision: String?,         // 精度：low/normal/high
    @SerializedName("use_mmap") var useMmap: Boolean?,           // 是否使用 mmap 加载
    @SerializedName("memory") var memory: String?,               // 内存模式
    @SerializedName("system_prompt") var systemPrompt: String?,  // 系统提示词
    @SerializedName("prompt_cache") var promptCache: Boolean?,   // 是否启用 prompt 缓存
    @SerializedName("sampler_type") var samplerType: String?,    // 采样器类型
    @SerializedName("mixed_samplers") var mixedSamplers: MutableList<String>?,  // 混合采样器列表
    @SerializedName("temperature") var temperature: Float?,      // 温度参数（0.0-2.0）
    @SerializedName("topP") var topP: Float?,                    // Top-P 采样
    @SerializedName("topK") var topK: Int?,                      // Top-K 采样
    @SerializedName("minP") var minP: Float?,                    // Min-P 采样
    var tfsZ: Float?,                                            // TFS-Z 采样
    var typical: Float?,                                         // Typical 采样
    var penalty: Float?,                                         // 重复惩罚
    @SerializedName("n_gram") var nGram: Int?,                   // N-gram 采样
    @SerializedName("ngram_factor") var nGramFactor: Float?,     // N-gram 因子
    @SerializedName("max_new_tokens") var maxNewTokens: Int?,    // 最大生成 token 数
    @SerializedName("assistant_prompt_template") var assistantPromptTemplate: String?,  // 助手模板
    @SerializedName("penalty_sampler") var penaltySampler: String?,  // 惩罚采样器
    @SerializedName("jinja") var jinja: Jinja?,                  // Jinja 模板配置
    @SerializedName("visual_model") var visualModel: String?,    // 视觉模型文件名
    @SerializedName("diffusion_memory_mode") var diffusionMemoryMode: String?,  // Diffusion 内存模式
    @SerializedName("diffusion_steps") var diffusionSteps: Int?, // Diffusion 步数
    @SerializedName("image_width") var imageWidth: Int?,         // 生成图片宽度
    @SerializedName("image_height") var imageHeight: Int?,       // 生成图片高度
    @SerializedName("diffusion_seed") var diffusionSeed: Long?,  // Diffusion 随机种子
    @SerializedName("cfg_prompt") var cfgPrompt: String?,        // CFG 提示词
    @SerializedName("grid_size") var gridSize: Int?              // 网格大小
) {
    /**
     * deepCopy：深拷贝配置
     *
     * 为什么要深拷贝？
     * 修改配置时不想影响原始对象（比如用户改了温度参数，但不想改默认值）
     */
    fun deepCopy(): ModelConfig {
        return ModelConfig(
            llmModel = this.llmModel,
            llmWeight = this.llmWeight,
            backendType = this.backendType,
            threadNum = this.threadNum,
            precision = this.precision,
            memory = this.memory,
            systemPrompt = this.systemPrompt,
            promptCache = this.promptCache,
            samplerType = this.samplerType,
            mixedSamplers = this.mixedSamplers?.toMutableList(),  // toMutableList() 创建新列表
            temperature = this.temperature,
            topP = this.topP,
            topK = this.topK,
            minP = this.minP,
            tfsZ = this.tfsZ,
            typical = this.typical,
            penalty = this.penalty,
            nGram = this.nGram,
            nGramFactor = this.nGramFactor,
            maxNewTokens = this.maxNewTokens,
            assistantPromptTemplate = this.assistantPromptTemplate,
            penaltySampler = this.penaltySampler,
            useMmap = this.useMmap,
            jinja = this.jinja?.let {
                Jinja(context = JinjaContext(enableThinking = it.context?.enableThinking == true))
            },
            visualModel = this.visualModel,
            diffusionMemoryMode = this.diffusionMemoryMode,
            diffusionSteps = this.diffusionSteps,
            imageWidth = this.imageWidth,
            imageHeight = this.imageHeight,
            diffusionSeed = this.diffusionSeed,
            cfgPrompt = this.cfgPrompt,
            gridSize = this.gridSize
        )
    }

    /**
     * samplerEquals：比较采样器配置是否相同
     * 用于判断配置是否被修改过
     */
    fun samplerEquals(loadedConfig: ModelConfig): Boolean {
        return this.samplerType == loadedConfig.samplerType &&
                this.mixedSamplers == loadedConfig.mixedSamplers &&
                this.temperature == loadedConfig.temperature &&
                this.topP == loadedConfig.topP &&
                this.topK == loadedConfig.topK &&
                this.minP == loadedConfig.minP &&
                this.tfsZ == loadedConfig.tfsZ &&
                this.typical == loadedConfig.typical &&
                this.penalty == loadedConfig.penalty &&
                this.nGram == loadedConfig.nGram &&
                this.nGramFactor == loadedConfig.nGramFactor &&
                this.penaltySampler == loadedConfig.penaltySampler &&
                this.visualModel == loadedConfig.visualModel &&
                this.diffusionMemoryMode == loadedConfig.diffusionMemoryMode &&
                this.diffusionSteps == loadedConfig.diffusionSteps &&
                this.imageWidth == loadedConfig.imageWidth &&
                this.imageHeight == loadedConfig.imageHeight &&
                this.diffusionSeed == loadedConfig.diffusionSeed &&
                this.cfgPrompt == loadedConfig.cfgPrompt &&
                this.gridSize == loadedConfig.gridSize
    }

    companion object {
        const val TAG = "ModelConfig"

        /**
         * loadDefaultConfig：加载默认配置文件
         * 读取 config.json 并反序列化为 ModelConfig 对象
         */
        fun loadDefaultConfig(filePath: String): ModelConfig? {
            return try {
                val file = File(filePath)
                val json = file.readText()
                Gson().fromJson(json, ModelConfig::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        /**
         * loadConfig：加载模型配置（合并默认配置和自定义配置）
         */
        fun loadConfig(modelId: String): ModelConfig? {
            val defaultConfigFile = getDefaultConfigFile(modelId)
            if (defaultConfigFile.isNullOrEmpty()) {
                Log.w(TAG, "loadConfig: default config not found for modelId=$modelId")
                return null
            }
            return loadMergedConfig(defaultConfigFile, getExtraConfigFile(modelId))
        }

        /**
         * loadMergedConfig：加载合并后的配置
         *
         * 先加载原始 config.json，再用 custom_config.json 覆盖
         * 这样用户只需要修改差异部分，不用改整个配置
         */
        fun loadMergedConfig(originalFilePath: String, overrideFilePath: String): ModelConfig? {
            return try {
                val originalFile = File(originalFilePath)
                val originalJson = JsonParser.parseString(originalFile.readText()).asJsonObject

                val overrideFile = File(overrideFilePath)
                if (overrideFile.exists()) {
                    val overrideJson = JsonParser.parseString(overrideFile.readText()).asJsonObject
                    mergeJson(originalJson, overrideJson)
                }
                Gson().fromJson(originalJson, ModelConfig::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        /**
         * getDefaultConfigFile：获取默认配置文件路径
         * 支持本地模型、内置模型和远程下载模型三种路径
         */
        fun getDefaultConfigFile(modelId: String): String? {
            if (modelId.startsWith("local/")) {
                val localPath = modelId.removePrefix("local/")
                val configFilePath = File(localPath, "config.json")
                if (configFilePath.exists()) {
                    return configFilePath.absolutePath
                }
                return null
            }
            if (modelId.startsWith("Builtin/")) {
                val modelName = modelId.removePrefix("Builtin/MNN/")
                val builtinModelsDir = File(ApplicationProvider.get().filesDir, ".mnnmodels/builtin")
                val modelDir = File(builtinModelsDir, modelName)
                val configFilePath = File(modelDir, "config.json")
                if (configFilePath.exists()) {
                    return configFilePath.absolutePath
                }
                return null
            }
            val configFileName = "config.json"
            val destModelDir = ModelDownloadManager.getInstance(ApplicationProvider.get())
                .getDownloadedFile(modelId)?.absolutePath
            destModelDir?.let {
                val configFilePath = File(destModelDir, configFileName)
                if (configFilePath.exists()) {
                    return configFilePath.absolutePath
                }
            }
            return null
        }

        /** 不能被空字符串覆盖的键（模型路径和后端类型） */
        private val PROTECTED_KEYS = setOf("llm_model", "llm_weight", "backend_type")

        /**
         * mergeJson：合并两个 JsonObject
         * override 中的字段会覆盖 original 中的同名字段
         */
        private fun mergeJson(original: JsonObject, override: JsonObject) {
            for (key in override.keySet()) {
                val overrideVal = override.get(key)
                // 保护关键字段不被空字符串覆盖
                if (key in PROTECTED_KEYS && overrideVal.isJsonPrimitive &&
                    overrideVal.asJsonPrimitive.isString && overrideVal.asString.isBlank() &&
                    original.has(key) && original.get(key).isJsonPrimitive &&
                    original.get(key).asJsonPrimitive.isString && original.get(key).asString.isNotBlank()
                ) {
                    continue
                }
                original.add(key, overrideVal)
            }
        }

        fun toJson(): String {
            return GsonBuilder()
                .disableHtmlEscaping()
                .create()
                .toJson(this)
        }

        /**
         * saveConfig：保存配置到文件
         * 用 PrettyPrinting 格式化输出（带缩进的 JSON）
         */
        fun saveConfig(filePath: String, config: ModelConfig): Boolean {
            return try {
                Log.d(TAG, "file is : $filePath")
                val file = File(filePath)
                FileUtils.ensureParentDirectoriesExist(file)
                val gson = GsonBuilder()
                    .setPrettyPrinting()       // 格式化输出
                    .disableHtmlEscaping()      // 不转义 HTML 字符
                    .create()
                val jsonString = gson.toJson(config)
                file.writeText(jsonString)
                true
            } catch (e: Exception) {
                Log.e(TAG, "saveConfig error", e)
                false
            }
        }

        fun getExtraConfigFile(modelId: String): String {
            return getModelConfigDir(modelId) + "/custom_config.json"
        }

        /** 删除自定义配置，恢复默认 */
        fun deleteExtraConfig(modelId: String): Boolean {
            return try {
                val file = File(getExtraConfigFile(modelId))
                file.exists() && file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "deleteExtraConfig error", e)
                false
            }
        }

        fun getMarketConfigFile(modelId: String): String {
            if (modelId.startsWith("local/")) {
                val localPath = modelId.removePrefix("local/")
                return File(localPath, "market_config.json").absolutePath
            }
            if (modelId.startsWith("Builtin/")) {
                val modelName = modelId.removePrefix("Builtin/MNN/")
                val builtinModelsDir = File(ApplicationProvider.get().filesDir, ".mnnmodels/builtin")
                val modelDir = File(builtinModelsDir, modelName)
                return File(modelDir, "market_config.json").absolutePath
            }
            return getModelConfigDir(modelId) + "/market_config.json"
        }

        fun getModelConfigDir(modelId: String): String {
            val rootCacheDir =
                ApplicationProvider.get().filesDir.toString() + "/configs/" + ModelUtils.safeModelId(
                    modelId
                )
            return rootCacheDir
        }

        // 默认配置
        val defaultConfig: ModelConfig = ModelConfig(
            llmModel = null,
            llmWeight = null,
            backendType = null,
            threadNum = 4,
            precision = "low",
            memory = "",
            systemPrompt = "You are a helpful assistant.",
            promptCache = false,
            samplerType = "",
            mixedSamplers = mutableListOf("topK", "topP", "minP", "temperature"),
            temperature = 0.6f,
            topP = 0.95f,
            topK = 20,
            minP = 0.05f,
            tfsZ = 1.0f,
            typical = 0.95f,
            penalty = 1.02f,
            nGram = 8,
            nGramFactor = 1.02f,
            maxNewTokens = 2048,
            assistantPromptTemplate = "",
            penaltySampler = "greedy",
            useMmap = false,
            jinja = null,
            visualModel = "visual.mnn",
            diffusionMemoryMode = "0",
            diffusionSteps = 20,
            imageWidth = 512,
            imageHeight = 512,
            diffusionSeed = 42L,
            cfgPrompt = "Generate high quality image",
            gridSize = 1
        )
    }
}

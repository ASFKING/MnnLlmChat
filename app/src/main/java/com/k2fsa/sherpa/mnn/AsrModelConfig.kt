package com.k2fsa.sherpa.mnn

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * AsrModelConfig：ASR 模型配置（从 config.json 解析）
 *
 * 一句话定义：描述一个 ASR 模型目录里有什么文件、怎么加载。
 * 生活类比：就像药盒上的说明书——写了药名、成分、用法用量。
 *
 * @param modelType    模型类型（如 "zipformer"、"sensevoice"）
 * @param transducer   Transducer 模型的三个文件路径
 * @param tokens       词表文件名
 * @param language     支持的语言列表（可选）
 * @param description  模型描述（可选）
 * @param lm           语言模型配置（可选）
 */
data class AsrModelConfig(
    val modelType: String,
    val transducer: TransducerConfig,
    val tokens: String,
    val language: List<String>? = null,
    val description: String? = null,
    val lm: LmConfig? = null,
)

/** Transducer 模型的三个文件路径 */
data class TransducerConfig(
    val encoder: String,
    val decoder: String,
    val joiner: String,
)

/** 语言模型配置 */
data class LmConfig(
    val model: String,
    val scale: Float = 0.5f,
)

/**
 * AsrConfigManager：ASR 配置管理器
 *
 * 职责：从模型目录的 config.json 文件中读取配置，
 *       自动构建 OnlineModelConfig 供 OnlineRecognizer 使用。
 *
 * 为什么需要这个？
 * 不同的 ASR 模型（zipformer、sensevoice 等）文件名不同，
 * 硬编码文件名容易出错。读 config.json 更灵活、更通用。
 */
object AsrConfigManager {
    private const val TAG = "AsrConfigManager"
    private const val CONFIG_FILE_NAME = "config.json"

    /**
     * 从模型目录读取配置，返回 OnlineModelConfig
     *
     * @param modelDir ASR 模型目录路径
     * @return OnlineModelConfig 或 null（配置文件不存在时）
     */
    fun getModelConfigFromDirectory(modelDir: String): OnlineModelConfig? {
        return try {
            val configFile = File(modelDir, CONFIG_FILE_NAME)
            Log.d(TAG, "Looking for config file at: ${configFile.absolutePath}")

            if (!configFile.exists()) {
                Log.w(TAG, "Config file not found at ${configFile.absolutePath}, using fallback")
                return getFallbackConfig(modelDir)
            }

            val configContent = configFile.readText()
            Log.d(TAG, "Read config file content: ${configContent.take(200)}...")

            val asrConfig = parseConfigJson(configContent)
            Log.i(TAG, "Using ASR config from JSON: ${asrConfig.description ?: "Unknown model"}")
            convertToOnlineModelConfig(modelDir, asrConfig)

        } catch (e: Exception) {
            Log.e(TAG, "Error reading config file from $modelDir", e)
            getFallbackConfig(modelDir)
        }
    }

    /**
     * 解析 JSON 配置文件
     *
     * config.json 格式示例：
     * {
     *   "modelType": "zipformer",
     *   "transducer": {
     *     "encoder": "encoder.mnn",
     *     "decoder": "decoder.mnn",
     *     "joiner": "joiner.mnn"
     *   },
     *   "tokens": "tokens.txt",
     *   "language": ["zh", "en"],
     *   "description": "Bilingual ASR model"
     * }
     */
    private fun parseConfigJson(jsonContent: String): AsrModelConfig {
        val configJson = JSONObject(jsonContent)
        val transducerJson = configJson.getJSONObject("transducer")

        // 解析可选的 language 数组
        val languages = if (configJson.has("language")) {
            val languageArray = configJson.getJSONArray("language")
            val langList = mutableListOf<String>()
            for (i in 0 until languageArray.length()) {
                langList.add(languageArray.getString(i))
            }
            langList
        } else null

        val transducerConfig = TransducerConfig(
            encoder = transducerJson.getString("encoder"),
            decoder = transducerJson.getString("decoder"),
            joiner = transducerJson.getString("joiner"),
        )

        // 解析可选的 LM 配置
        val lmConfig = if (configJson.has("lm")) {
            val lmJson = configJson.getJSONObject("lm")
            LmConfig(
                model = lmJson.getString("model"),
                scale = lmJson.optDouble("scale", 0.5).toFloat(),
            )
        } else null

        return AsrModelConfig(
            modelType = configJson.getString("modelType"),
            transducer = transducerConfig,
            tokens = configJson.getString("tokens"),
            language = languages,
            description = configJson.optString("description", null),
            lm = lmConfig,
        )
    }

    /**
     * 把 AsrModelConfig 转换为 OnlineRecognizer 需要的 OnlineModelConfig
     *
     * 关键：把相对路径拼成绝对路径。
     * config.json 里写的是 "encoder.mnn"（相对路径），
     * 需要拼成 "/data/data/.../models/sensevoice/encoder.mnn"（绝对路径）。
     */
    private fun convertToOnlineModelConfig(modelDir: String, config: AsrModelConfig): OnlineModelConfig {
        return OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, config.transducer.encoder).absolutePath,
                decoder = File(modelDir, config.transducer.decoder).absolutePath,
                joiner = File(modelDir, config.transducer.joiner).absolutePath,
            ),
            tokens = File(modelDir, config.tokens).absolutePath,
            modelType = config.modelType,
        )
    }

    /**
     * 兜底配置：当 config.json 不存在时，根据目录名推断
     *
     * 兼容旧版模型目录（没有 config.json 的情况）
     */
    private fun getFallbackConfig(modelDir: String): OnlineModelConfig? {
        Log.w(TAG, "Using fallback configuration for modelDir: $modelDir")
        val dirName = File(modelDir).name.lowercase()

        return when {
            dirName.contains("bilingual") || dirName.contains("zh") -> {
                Log.d(TAG, "Using bilingual/Chinese fallback config")
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.int8.mnn",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.int8.mnn",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.int8.mnn",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "zipformer",
                )
            }
            else -> {
                Log.d(TAG, "Using default fallback config")
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.mnn",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.mnn",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.mnn",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "zipformer",
                )
            }
        }
    }

    /**
     * 从模型目录获取语言模型配置
     */
    fun getLmConfigFromDirectory(modelDir: String): OnlineLMConfig {
        return try {
            val configFile = File(modelDir, CONFIG_FILE_NAME)
            if (!configFile.exists()) {
                Log.w(TAG, "Config file not found, using default LM config")
                return OnlineLMConfig()
            }
            val configContent = configFile.readText()
            val asrConfig = parseConfigJson(configContent)
            if (asrConfig.lm != null) {
                val fullModelPath = File(modelDir, asrConfig.lm.model).absolutePath
                OnlineLMConfig(model = fullModelPath, scale = asrConfig.lm.scale)
            } else {
                OnlineLMConfig()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading LM config from $modelDir", e)
            OnlineLMConfig()
        }
    }
}

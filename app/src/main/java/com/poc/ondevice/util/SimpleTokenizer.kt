package com.poc.ondevice.util

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * SimpleTokenizer：纯 Kotlin 实现的 BERT WordPiece 分词器
 *
 * 职责：
 * - 解析 HuggingFace 格式的 tokenizer.json
 * - 把中文文本切成 token ids（数字序列）
 * - 供 bge-small-zh-v1.5 嵌入模型使用
 *
 * 为什么自己实现？
 * DJL 的 HuggingFaceTokenizer 依赖 Rust JNI，在 Android 上无法加载 native 库。
 * onnxruntime-extensions 没有独立的分词器 Java API。
 * 所以我们用纯 Kotlin 实现一个轻量级的 BERT WordPiece 分词器。
 *
 * BERT 分词流程：
 * 1. 预处理：文本 → 去除多余空白、处理中文字符
 * 2. 基础分词：按空格和标点切分，中文每个字符独立
 * 3. WordPiece：对每个词尝试最长匹配词表，匹配不上就拆成子词
 * 4. 添加特殊标记：[CLS] + tokens + [SEP]
 * 5. 转 ID：每个 token 查词表得到数字 ID
 *
 * 生活类比：
 * WordPiece 就像查字典——先尝试匹配整个词（"药品"），
 * 匹配不上就拆开（"药"+"品"），每个部分前面加 ## 表示"这是续接部分"。
 */
class SimpleTokenizer(private val tokenizerPath: String) {

    // ==================== 核心数据结构 ====================

    /**
     * vocab：词表，token → id 的映射
     * 例如："药品" → 5765, "经" → 1234, "营" → 5678
     */
    private val vocab = mutableMapOf<String, Int>()

    /**
     * 特殊 token 的 ID
     */
    private var clsTokenId = 101   // [CLS] 句子开头标记
    private var sepTokenId = 102   // [SEP] 句子结尾标记
    private var unkTokenId = 100   // [UNK] 未知词标记
    private var padTokenId = 0     // [PAD] 填充标记

    /**
     * continuingSubwordPrefix：子词续接前缀
     * BERT 用 "##" 表示"这不是一个词的开头，而是续接部分"
     * 例如："许可证" → ["许", "##可", "##证"]
     */
    private val continuingSubwordPrefix = "##"

    /**
     * 最大输入长度（token 数）
     */
    private var maxInputLength = 512

    // ==================== 初始化 ====================

    init {
        loadTokenizer()
    }

    /**
     * 加载 tokenizer.json 并解析词表
     *
     * tokenizer.json 是 HuggingFace 的标准格式
     * 包含词表、特殊 token、预处理规则等
     */
    private fun loadTokenizer() {
        try {
            val file = File(tokenizerPath)
            if (!file.exists()) {
                Log.e(TAG, "tokenizer.json 不存在: $tokenizerPath")
                return
            }

            val jsonStr = file.readText()
            val json = JsonParser.parseString(jsonStr).asJsonObject

            // ===== 解析词表 =====
            // model.vocab: { "token": id, ... }
            val model = json.getAsJsonObject("model")
            val vocabObj = model.getAsJsonObject("vocab")
            for ((token, value) in vocabObj.entrySet()) {
                vocab[token] = value.asInt
            }
            Log.d(TAG, "词表加载完成，共 ${vocab.size} 个 token")

            // ===== 解析 added_tokens（特殊 token） =====
            // added_tokens 包含 [CLS], [SEP], [UNK], [PAD] 等特殊标记
            val addedTokens = json.getAsJsonArray("added_tokens")
            if (addedTokens != null) {
                for (element in addedTokens) {
                    val tokenObj = element.asJsonObject
                    val content = tokenObj.get("content").asString
                    val id = tokenObj.get("id").asInt

                    when (content) {
                        "[CLS]" -> clsTokenId = id
                        "[SEP]" -> sepTokenId = id
                        "[UNK]" -> unkTokenId = id
                        "[PAD]" -> padTokenId = id
                    }
                }
            }

            // ===== 解析续接前缀 =====
            // model.continuing_subword_prefix: "##"
            if (model.has("continuing_subword_prefix")) {
                val prefix = model.get("continuing_subword_prefix").asString
                if (prefix.isNotEmpty()) {
                    // 我们已经在字段声明时设置了默认值 "##"
                    Log.d(TAG, "续接前缀: $prefix")
                }
            }

            Log.d(TAG, "特殊 token: CLS=$clsTokenId, SEP=$sepTokenId, UNK=$unkTokenId, PAD=$padTokenId")

        } catch (e: Exception) {
            Log.e(TAG, "加载 tokenizer.json 失败", e)
        }
    }

    // ==================== 编码（文本 → token ids） ====================

    /**
     * 把文本编码为 token ids
     *
     * 完整流程：
     * 1. 预处理：全角转半角、去除多余空白
     * 2. 基础分词：按空格和标点切分，中文每个字符独立
     * 3. WordPiece：每个词尝试最长匹配词表
     * 4. 添加 [CLS] 和 [SEP]
     * 5. 截断到最大长度
     *
     * @param text 输入文本
     * @return token ids 数组（包含 [CLS] 和 [SEP]）
     */
    fun encode(text: String): LongArray {
        // Step 1: 预处理
        val cleaned = preprocess(text)

        // Step 2: 基础分词
        val words = basicTokenize(cleaned)

        // Step 3: WordPiece 子词切分
        val tokens = mutableListOf<String>()
        for (word in words) {
            tokens.addAll(wordPieceTokenize(word))
        }

        // Step 4: 截断（留 2 个位置给 [CLS] 和 [SEP]）
        val maxTokens = maxInputLength - 2
        val truncatedTokens = if (tokens.size > maxTokens) {
            tokens.subList(0, maxTokens)
        } else {
            tokens
        }

        // Step 5: 添加 [CLS] 和 [SEP] 并转 ID
        val ids = mutableListOf<Long>()
        ids.add(clsTokenId.toLong())

        for (token in truncatedTokens) {
            val id = vocab[token] ?: unkTokenId
            ids.add(id.toLong())
        }

        ids.add(sepTokenId.toLong())

        return ids.toLongArray()
    }

    // ==================== 预处理 ====================

    /**
     * 文本预处理
     *
     * 1. 全角字符 → 半角（Ａ→A，１→1）
     * 2. 去除多余空白
     * 3. 去除不可见字符
     */
    private fun preprocess(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when {
                // 全角 ASCII → 半角
                c in '\uFF01'..'\uFF5E' -> sb.append((c.code - 0xFEE0).toChar())
                // 全角空格 → 半角空格
                c == '\u3000' -> sb.append(' ')
                // 控制字符 → 跳过
                c.isISOControl() -> { /* skip */ }
                // 正常字符
                else -> sb.append(c)
            }
        }
        // 去除多余空白
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    // ==================== 基础分词 ====================

    /**
     * 基础分词：按空格和标点切分
     *
     * 规则：
     * - 空格处切分
     * - 英文标点处切分
     * - 中文标点处切分
     * - 中文每个字符独立（因为中文没有空格分隔）
     * - 英文单词保持完整
     */
    private fun basicTokenize(text: String): List<String> {
        val words = mutableListOf<String>()
        val currentWord = StringBuilder()

        for (c in text) {
            when {
                // 空格：切分
                c == ' ' -> {
                    if (currentWord.isNotEmpty()) {
                        words.add(currentWord.toString())
                        currentWord.clear()
                    }
                }
                // 中文字符：每个字符独立切分
                // Unicode 范围：CJK 统一汉字 0x4E00-0x9FFF
                isChinese(c) -> {
                    if (currentWord.isNotEmpty()) {
                        words.add(currentWord.toString())
                        currentWord.clear()
                    }
                    words.add(c.toString())
                }
                // 标点符号：切分
                isPunctuation(c) -> {
                    if (currentWord.isNotEmpty()) {
                        words.add(currentWord.toString())
                        currentWord.clear()
                    }
                    words.add(c.toString())
                }
                // 其他字符（英文、数字等）：追加到当前词
                else -> {
                    currentWord.append(c.lowercaseChar())
                }
            }
        }

        if (currentWord.isNotEmpty()) {
            words.add(currentWord.toString())
        }

        return words
    }

    /**
     * 判断是否是中文字符
     */
    private fun isChinese(c: Char): Boolean {
        val type = Character.UnicodeBlock.of(c)
        return type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || type == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || type == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
    }

    /**
     * 判断是否是标点符号
     */
    private fun isPunctuation(c: Char): Boolean {
        // ASCII 标点 + 中文常用标点
        return c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
                || c in "，。！？；：""''【】（）、—…·"
                || c in "，。！？；：""''【】（）《》、—…·～"
    }

    // ==================== WordPiece 子词切分 ====================

    /**
     * WordPiece 子词切分算法
     *
     * 策略：贪心最长匹配
     * 1. 先尝试匹配整个词
     * 2. 匹配不上就从右边缩短一个字符
     * 3. 缩短的部分加 "##" 前缀，继续尝试
     * 4. 如果单个字符都匹配不上，返回 [UNK]
     *
     * 例子：
     * "许可证" →
     *   尝试 "许可证" → 词表有 → ["许可证"]
     *
     * "经营许可" →
     *   尝试 "经营许可" → 词表没有
     *   尝试 "经营许" → 词表没有
     *   尝试 "经营" → 词表有 → ["经营"]
     *   剩余 "许可" → 尝试 "许可" → 词表有 → ["##许可"]
     *   结果：["经营", "##许可"]
     */
    private fun wordPieceTokenize(word: String): List<String> {
        // 如果词已经在词表中，直接返回
        if (vocab.containsKey(word)) {
            return listOf(word)
        }

        val tokens = mutableListOf<String>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var found = false

            // 从最长子串开始尝试
            while (start < end) {
                val sub = word.substring(start, end)
                // 第一个片段不需要 ## 前缀，后续片段需要
                val token = if (start == 0) sub else "$continuingSubwordPrefix$sub"

                if (vocab.containsKey(token)) {
                    tokens.add(token)
                    start = end
                    found = true
                    break
                }
                end--
            }

            // 如果连单个字符都匹配不上，标记为 [UNK]
            if (!found) {
                tokens.add("[UNK]")
                start++
            }
        }

        return tokens
    }

    // ==================== 工具方法 ====================

    /**
     * 获取词表大小
     */
    fun vocabSize(): Int = vocab.size

    /**
     * 检查分词器是否加载成功
     */
    fun isLoaded(): Boolean = vocab.isNotEmpty()

    companion object {
        private const val TAG = "SimpleTokenizer"
    }
}

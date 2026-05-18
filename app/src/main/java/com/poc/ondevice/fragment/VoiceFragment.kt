package com.poc.ondevice.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.poc.ondevice.App
import com.poc.ondevice.databinding.FragmentVoiceBinding
import com.poc.ondevice.download.ModelDownloader
import com.poc.ondevice.download.ModelRegistry
import com.poc.ondevice.util.AudioPlayer
import com.poc.ondevice.util.AudioRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * VoiceFragment：语音对话页
 *
 * 全链路语音对话（三层解释法）：
 * 一句话：用户说话 → AI 听懂 → AI 思考 → AI 说出来。
 * 生活类比：就像和朋友打电话——你说，他听，他想，他说。
 * 技术细节：AudioRecorder 录音 → ASREngine 语音识别 → LLMEngine 文本推理
 *           → TTSEngine 语音合成 → AudioPlayer 播放
 *
 * 完整流程：
 * 1. 用户按住按钮 → AudioRecorder 开始录音
 * 2. 松开按钮 → ASREngine 识别语音 → 得到文字
 * 3. 文字送入 LLMEngine → 流式生成回答 → UI 实时显示
 * 4. 回答完成后 → TTSEngine 合成语音 → AudioPlayer 播放
 */
class VoiceFragment : Fragment() {

    // ==================== ViewBinding ====================
    private var _binding: FragmentVoiceBinding? = null
    private val binding get() = _binding!!

    // ==================== 引擎 ====================
    private val asrEngine get() = (requireActivity().application as App).asrEngine
    private val llmEngine get() = (requireActivity().application as App).llmEngine
    private val ttsEngine get() = (requireActivity().application as App).ttsEngine
    private val modelDownloader by lazy { ModelDownloader(requireContext()) }

    // ==================== 工具 ====================
    private val audioRecorder by lazy { AudioRecorder(requireContext()) }
    private val audioPlayer by lazy { AudioPlayer() }

    // ==================== 状态 ====================
    /** isRecording：是否正在录音 */
    private var isRecording = false

    /** recordingJob：录音协程任务（用于 join 等待完成） */
    private var recordingJob: Job? = null

    /** collectedAudioData：录音过程中收集到的所有音频数据 */
    private val collectedAudioData = mutableListOf<Float>()

    /** isModelLoaded：ASR 模型是否已加载 */
    private var isModelLoaded = false

    /** isTtsLoaded：TTS 模型是否已加载 */
    private var isTtsLoaded = false

    /** isProcessing：是否正在处理中（ASR→LLM→TTS） */
    @Volatile
    private var isProcessing = false

    // ==================== 权限请求 ====================
    /**
     * 录音权限请求器
     *
     * registerForActivityResult：Android 推荐的权限请求方式
     * 工作流程：launch() → 系统弹窗 → 用户选择 → 回调 lambda
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(TAG, "RECORD_AUDIO permission granted by user")
            startRecordingInternal()
        } else {
            Log.w(TAG, "RECORD_AUDIO permission denied by user")
            Toast.makeText(requireContext(), "需要录音权限才能使用语音功能", Toast.LENGTH_LONG).show()
            binding.tvAsrResult.text = "❌ 需要录音权限，请在系统设置中授权"
        }
    }

    // ==================== 生命周期 ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVoiceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        loadLLMModel()
        loadASRModel()
        loadTTSModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        audioRecorder.stopRecording()
        audioPlayer.stop()
        _binding = null
    }

    // ==================== UI 初始化 ====================

    private fun setupButtons() {
        // ==================== 按住说话按钮 ====================
        binding.btnTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isModelLoaded) {
                        Toast.makeText(requireContext(), "ASR 模型尚未加载", Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    if (isProcessing) {
                        Toast.makeText(requireContext(), "正在处理中，请稍候", Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    checkPermissionAndRecord()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        stopRecordingAndProcess()
                    }
                    true
                }
                else -> false
            }
        }

        // 停止按钮：强制停止当前所有操作
        binding.btnStop.setOnClickListener {
            if (isRecording) {
                stopRecordingAndProcess()
            }
            if (isProcessing) {
                // 强制停止
                isProcessing = false
                audioPlayer.stop()
                binding.btnTalk.isEnabled = true
                binding.tvTtsStatus.text = "⏹️ 已停止"
            }
        }
    }

    // ==================== 权限检查 ====================

    /**
     * 检查录音权限，有权限就录音，没权限就请求
     */
    private fun checkPermissionAndRecord() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "RECORD_AUDIO permission already granted")
                startRecordingInternal()
            }
            else -> {
                Log.d(TAG, "Requesting RECORD_AUDIO permission")
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // ==================== 模型加载 ====================

    /**
     * 加载 LLM 模型（语音对话的核心——ASR 识别出的文字需要 LLM 来回答）
     *
     * 为什么需要在这里加载？
     * 用户可能直接从语音 Tab 开始对话，不一定会先去首页加载 LLM。
     * 所以在 VoiceFragment 创建时自动加载，确保全链路可用。
     */
    private fun loadLLMModel() {
        if (llmEngine.isLoaded) {
            Log.d(TAG, "LLM model already loaded")
            return
        }

        lifecycleScope.launch {
            try {
                // 找到第一个 LLM 模型（非多模态、非 ASR、非 TTS、非嵌入）
                val llmModelEntry = ModelRegistry.defaultModels.find { entry ->
                    val name = entry.displayName.lowercase()
                    !name.contains("多模态") && !name.contains("vl") &&
                    !name.contains("asr") && !name.contains("zipformer") &&
                    !name.contains("tts") && !name.contains("bertvits") &&
                    !name.contains("supertonic") && !name.contains("嵌入") &&
                    !name.contains("bge")
                }

                if (llmModelEntry == null) {
                    Log.w(TAG, "No LLM model found in registry")
                    return@launch
                }

                val modelDir = modelDownloader.getModelPath(llmModelEntry.modelDirName)
                if (!modelDir.exists()) {
                    Log.w(TAG, "LLM model not downloaded: ${llmModelEntry.displayName}")
                    return@launch
                }

                Log.d(TAG, "Loading LLM model from: ${modelDir.absolutePath}")
                val loaded = llmEngine.load(modelDir.absolutePath)
                if (loaded) {
                    Log.d(TAG, "LLM model loaded successfully")
                } else {
                    Log.e(TAG, "LLM model load failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载 LLM 模型异常", e)
            }
        }
    }

    /**
     * 加载 ASR 模型（Zipformer 流式，中英双语）
     */
    private fun loadASRModel() {
        binding.tvAsrResult.text = "正在加载 ASR 模型..."

        lifecycleScope.launch {
            try {
                val asrModelEntry = ModelRegistry.defaultModels.find { entry ->
                    entry.displayName.contains("ASR", ignoreCase = true) ||
                    entry.displayName.contains("Zipformer", ignoreCase = true) ||
                    entry.displayName.contains("语音识别", ignoreCase = true)
                }

                if (asrModelEntry == null) {
                    binding.tvAsrResult.text = "错误：未找到 ASR 模型"
                    return@launch
                }

                val modelDir = modelDownloader.getModelPath(asrModelEntry.modelDirName)
                if (!modelDir.exists()) {
                    binding.tvAsrResult.text = "请先在首页下载 ${asrModelEntry.displayName} 模型"
                    return@launch
                }

                Log.d(TAG, "Loading ASR model from: ${modelDir.absolutePath}")
                isModelLoaded = asrEngine.load(modelDir.absolutePath)

                if (isModelLoaded) {
                    binding.tvAsrResult.text = "ASR 模型已就绪，请按住说话"
                    Log.d(TAG, "ASR model loaded successfully")
                } else {
                    binding.tvAsrResult.text = "ASR 模型加载失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载 ASR 模型异常", e)
                binding.tvAsrResult.text = "加载 ASR 出错: ${e.message}"
            }
        }
    }

    /**
     * 加载 TTS 模型（BertVITS2 中英双语）
     *
     * TTS 模型约 1.4GB，加载可能需要几秒。
     * 加载成功后才能进行语音合成。
     */
    private fun loadTTSModel() {
        binding.tvTtsStatus.text = "🔄 正在加载 TTS 模型..."

        lifecycleScope.launch {
            try {
                val ttsModelEntry = ModelRegistry.defaultModels.find { entry ->
                    entry.displayName.contains("TTS", ignoreCase = true) ||
                    entry.displayName.contains("BertVITS", ignoreCase = true) ||
                    entry.displayName.contains("语音合成", ignoreCase = true)
                }

                if (ttsModelEntry == null) {
                    binding.tvTtsStatus.text = "❌ 未找到 TTS 模型"
                    return@launch
                }

                val modelDir = modelDownloader.getModelPath(ttsModelEntry.modelDirName)
                if (!modelDir.exists()) {
                    binding.tvTtsStatus.text = "请先在首页下载 ${ttsModelEntry.displayName} 模型"
                    return@launch
                }

                Log.d(TAG, "Loading TTS model from: ${modelDir.absolutePath}")
                isTtsLoaded = ttsEngine.load(modelDir.absolutePath)

                if (isTtsLoaded) {
                    binding.tvTtsStatus.text = "🔊 TTS 已就绪"
                    Log.d(TAG, "TTS model loaded successfully, sampleRate=${ttsEngine.sampleRate}")
                } else {
                    binding.tvTtsStatus.text = "❌ TTS 模型加载失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载 TTS 模型异常", e)
                binding.tvTtsStatus.text = "❌ 加载 TTS 出错: ${e.message}"
            }
        }
    }

    // ==================== 录音控制 ====================

    /**
     * 开始录音（内部方法，权限已确认后调用）
     */
    private fun startRecordingInternal() {
        isRecording = true
        collectedAudioData.clear()

        binding.btnTalk.text = "松开结束"
        binding.tvAsrResult.text = "🎤 正在录音..."
        binding.tvLlmResponse.text = "等待语音识别完成后显示 AI 回答..."

        recordingJob = lifecycleScope.launch {
            audioRecorder.startRecording().collect { audioChunk ->
                collectedAudioData.addAll(audioChunk.toList())
            }
            Log.d(TAG, "Recording flow ended naturally, collected ${collectedAudioData.size} samples")
        }

        Log.d(TAG, "Recording started")
    }

    /**
     * 停止录音并处理全链路：ASR → LLM → TTS
     *
     * 这是语音对话的核心方法，串联三个引擎：
     * 1. ASR：语音 → 文字
     * 2. LLM：问题 → 回答（流式）
     * 3. TTS：回答文字 → 语音音频 → 播放
     */
    private fun stopRecordingAndProcess() {
        isRecording = false
        audioRecorder.stopRecording()

        binding.btnTalk.text = "按住说话"
        binding.btnTalk.isEnabled = false  // 处理中禁用按钮
        isProcessing = true

        lifecycleScope.launch {
            try {
                // ============ Step 1: ASR 语音识别 ============
                binding.tvAsrResult.text = "正在识别语音..."
                recordingJob?.join()

                Log.d(TAG, "Recording job finished, audio samples: ${collectedAudioData.size}")

                if (collectedAudioData.isEmpty()) {
                    binding.tvAsrResult.text = "未检测到音频，请重试"
                    resetProcessingState()
                    return@launch
                }

                val audioArray = collectedAudioData.toFloatArray()
                val asrResult = asrEngine.recognize(audioArray)

                if (asrResult.isBlank()) {
                    binding.tvAsrResult.text = "未识别到语音内容，请重试"
                    resetProcessingState()
                    return@launch
                }

                binding.tvAsrResult.text = "🎤 $asrResult"
                Log.d(TAG, "ASR result: $asrResult")

                // ============ Step 2: LLM 文本推理 ============
                binding.tvLlmResponse.text = ""
                binding.tvTtsStatus.text = "🤖 AI 正在思考..."

                val llmResponse = StringBuilder()

                // 检查 LLM 是否已加载
                if (!llmEngine.isLoaded) {
                    binding.tvLlmResponse.text = "LLM 模型未加载，请先在首页下载并加载模型"
                    resetProcessingState()
                    return@launch
                }

                // 流式生成：逐 token 显示
                llmEngine.generateStream(asrResult).collect { token ->
                    llmResponse.append(token)
                    binding.tvLlmResponse.text = llmResponse.toString()
                }

                Log.d(TAG, "LLM response: ${llmResponse}")

                // ============ Step 3: TTS 语音合成 + 播放 ============
                // 过滤 <think> 思考标签，只合成实际回答内容
                // Qwen3 模型会把思考过程包在 <think>...</think> 中，这些不需要读出来
                val responseText = llmResponse.toString()
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "")  // 移除 <think>...</think> 块
                    .trim()  // 去除首尾空白

                if (responseText.isBlank()) {
                    binding.tvTtsStatus.text = "AI 未生成回答"
                    resetProcessingState()
                    return@launch
                }

                if (!isTtsLoaded) {
                    binding.tvTtsStatus.text = "🔇 TTS 未加载，跳过语音播放"
                    resetProcessingState()
                    return@launch
                }

                binding.tvTtsStatus.text = "🔊 正在合成语音..."

                // 一次性合成完整音频
                val audioData = ttsEngine.synthesize(responseText)

                if (audioData.isEmpty()) {
                    binding.tvTtsStatus.text = "🔇 语音合成失败"
                    resetProcessingState()
                    return@launch
                }

                binding.tvTtsStatus.text = "🔊 正在播放..."

                // 播放音频（阻塞直到播放完）
                audioPlayer.playBlocking(audioData, ttsEngine.sampleRate)

                binding.tvTtsStatus.text = "🔊 播放完毕"
                Log.d(TAG, "TTS playback finished")

            } catch (e: Exception) {
                Log.e(TAG, "Voice chat pipeline failed", e)
                binding.tvTtsStatus.text = "❌ 处理失败: ${e.message}"
            } finally {
                resetProcessingState()
            }
        }
    }

    /**
     * 重置处理状态，恢复按钮可用
     */
    private fun resetProcessingState() {
        isProcessing = false
        binding.btnTalk.isEnabled = true
    }

    companion object {
        private const val TAG = "VoiceFragment"
    }
}

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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.poc.ondevice.App
import com.poc.ondevice.databinding.FragmentVoiceBinding
import com.poc.ondevice.download.ModelDownloader
import com.poc.ondevice.download.ModelRegistry
import com.poc.ondevice.util.AudioRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * VoiceFragment：语音对话页
 *
 * 全链路语音对话是什么（三层解释法）：
 * 一句话：用户说话 → AI 听懂 → AI 思考 → AI 说出来。
 * 生活类比：就像和朋友打电话——你说，他听，他想，他说。
 * 技术细节：AudioRecorder 录音 → ASREngine 语音识别 → LLMEngine 文本推理
 *           → TTSEngine 语音合成 → AudioPlayer 播放
 *
 * 职责：
 * - 按住说话 → ASR 语音识别（Day 7）
 * - LLM 流式回答（Day 8）
 * - TTS 语音播报（Day 8）
 * - 全链路 ASR → LLM → TTS 串联（Day 8）
 *
 * 当前状态：Day 7 — ASR 集成
 */
class VoiceFragment : Fragment() {

    // ==================== ViewBinding ====================
    private var _binding: FragmentVoiceBinding? = null
    private val binding get() = _binding!!

    // ==================== 引擎 ====================
    private val asrEngine get() = (requireActivity().application as App).asrEngine
    private val llmEngine get() = (requireActivity().application as App).llmEngine
    private val modelDownloader by lazy { ModelDownloader(requireContext()) }

    // ==================== 录音工具 ====================
    private val audioRecorder by lazy { AudioRecorder(requireContext()) }

    // ==================== 状态 ====================
    /** isRecording：是否正在录音 */
    private var isRecording = false

    /** recordingJob：录音协程任务（用于取消） */
    private var recordingJob: Job? = null

    /** collectedAudioData：录音过程中收集到的所有音频数据 */
    private val collectedAudioData = mutableListOf<Float>()

    /** isModelLoaded：ASR 模型是否已加载 */
    private var isModelLoaded = false

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
        loadASRModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        audioRecorder.stopRecording()
        _binding = null
    }

    // ==================== UI 初始化 ====================

    private fun setupButtons() {
        // ==================== 按住说话按钮 ====================
        // setOnTouchListener：监听触摸事件（比 setOnClickListener 更细粒度）
        // ACTION_DOWN：手指按下 → 开始录音
        // ACTION_UP：手指抬起 → 停止录音 → ASR 识别
        // 为什么用 OnTouchListener 而不是 OnClickListener？
        // 因为我们需要区分"按下"和"抬起"两个时刻，
        // OnClickListener 只在抬起时触发，无法在按下时开始录音。
        binding.btnTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isModelLoaded) {
                        Toast.makeText(requireContext(), "ASR 模型尚未加载", Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    startRecording()
                    true  // 返回 true 表示消费了这个事件
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        stopRecordingAndRecognize()
                    }
                    true
                }
                else -> false
            }
        }

        // 停止按钮：强制停止当前所有操作
        binding.btnStop.setOnClickListener {
            if (isRecording) {
                stopRecordingAndRecognize()
            }
        }
    }

    // ==================== 模型加载 ====================

    /**
     * 加载 SenseVoice ASR 模型
     *
     * SenseVoice 是离线模型，加载速度很快（< 1s）。
     * 模型约 200MB，内存占用约 300MB，适合常驻。
     */
    private fun loadASRModel() {
        binding.tvAsrResult.text = "正在加载 ASR 模型..."

        lifecycleScope.launch {
            try {
                // 从 ModelRegistry 中查找 ASR 模型（Zipformer 中英双语）
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
                    binding.tvTtsStatus.text = "🔇 TTS 未就绪（Day 8 实现）"
                    Log.d(TAG, "ASR model loaded successfully")
                } else {
                    binding.tvAsrResult.text = "ASR 模型加载失败，请检查模型文件"
                    Log.e(TAG, "ASR model load failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载 ASR 模型异常", e)
                binding.tvAsrResult.text = "加载模型出错: ${e.message}"
            }
        }
    }

    // ==================== 录音控制 ====================

    /**
     * 开始录音
     *
     * 按下按钮时调用。启动 AudioRecorder，开始收集音频数据。
     */
    private fun startRecording() {
        isRecording = true
        collectedAudioData.clear()

        // 更新 UI：按钮状态 + 提示文字
        binding.btnTalk.text = "松开结束"
        binding.tvAsrResult.text = "🎤 正在录音..."
        binding.tvLlmResponse.text = "等待语音识别完成后显示 AI 回答..."

        // 启动录音协程
        // lifecycleScope.launch：在 Fragment 生命周期内启动协程
        // Fragment 销毁时协程自动取消，避免内存泄漏
        recordingJob = lifecycleScope.launch {
            // startRecording() 返回 Flow<FloatArray>
            // collect {} 逐个接收音频数据块
            audioRecorder.startRecording().collect { audioChunk ->
                // audioChunk 是一帧音频数据（约 100ms）
                // 把 FloatArray 展平存入 collectedAudioData
                // 注意：这里在 IO 线程写入，join() 后在主线程读取，
                // join() 提供了 happens-before 保证，所以是安全的
                collectedAudioData.addAll(audioChunk.toList())
            }
            Log.d(TAG, "Recording flow ended naturally, collected ${collectedAudioData.size} samples")
        }

        Log.d(TAG, "Recording started")
    }

    /**
     * 停止录音并进行 ASR 识别
     *
     * 松开按钮时调用。停止录音 → 等待 Flow 自然结束 → 把收集到的音频送入 ASR → 显示识别结果。
     *
     * 关键修复：不要立刻 cancel 协程！
     * 之前的 bug：stopRecording() + cancel() 太快，IO 线程的 emit() 还没执行协程就被取消了，
     * 导致 collectedAudioData 永远是空的。
     * 修复后：只设置 isRecording = false，让 while 循环自然退出，Flow 自然结束，
     * 然后用 join() 等协程完成，确保所有数据都收集完毕。
     */
    private fun stopRecordingAndRecognize() {
        isRecording = false

        // 停止 AudioRecord（isRecording = false 后 while 循环会退出，Flow 自然结束）
        audioRecorder.stopRecording()

        // 更新 UI
        binding.btnTalk.text = "按住说话"
        binding.tvAsrResult.text = "正在识别..."

        // 在新协程中：先等录音协程完成（确保数据收集完毕），再做识别
        lifecycleScope.launch {
            try {
                // join()：等待录音协程完成
                // 类比：等助手接完水再拿走杯子，而不是水还没接完就抢走杯子
                recordingJob?.join()

                Log.d(TAG, "Recording job finished, audio samples: ${collectedAudioData.size}")

                if (collectedAudioData.isEmpty()) {
                    binding.tvAsrResult.text = "未检测到音频，请重试"
                    return@launch
                }

                // 把收集到的音频数据转成 FloatArray
                val audioArray = collectedAudioData.toFloatArray()

                // 调用 ASREngine 识别
                val result = asrEngine.recognize(audioArray)

                if (result.isNotBlank()) {
                    binding.tvAsrResult.text = "🎤 $result"
                    Log.d(TAG, "ASR result: $result")

                    // TODO: Day 8 — 把识别结果送入 LLM 生成回答
                    // llmEngine.generateStream(result).collect { token ->
                    //     binding.tvLlmResponse.append(token)
                    // }
                } else {
                    binding.tvAsrResult.text = "未识别到语音内容，请重试"
                }
            } catch (e: Exception) {
                Log.e(TAG, "ASR recognition failed", e)
                binding.tvAsrResult.text = "识别失败: ${e.message}"
            }
        }
    }

    companion object {
        private const val TAG = "VoiceFragment"
    }
}

package com.nihildigit.tongxing

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.lightwayllama.llm.LlmSession
import com.nihildigit.lightwayllama.llm.GenerationStats
import com.nihildigit.lightwayllama.llm.ProgressListener
import com.nihildigit.lightwayllama.llm.StructuredChatMessage
import com.nihildigit.lightwayllama.llm.LlamaRuntimeConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * OpenCL Benchmark ViewModel (legacy UI).
 *
 * After migrating to llama.cpp, backend/thread selections are treated as labels only.
 * Benchmarks run through the llama.cpp local backend with GGUF models.
 */
class OpenClBenchmarkViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OpenClBenchmarkVM"
        
        // Long prompt for testing Prefill performance (~500+ tokens)
        private const val TEST_PROMPT_LONG = """你是一位资深的人工智能专家和计算机科学教授。请详细、全面地解释以下概念，并给出具体的应用场景、代码示例和最佳实践：

第一部分：深度学习基础
1. 什么是机器学习中的梯度下降算法？它是如何工作的？请解释批量梯度下降、随机梯度下降和小批量梯度下降的区别，以及各自的优缺点。
2. 深度学习中的反向传播算法的基本原理是什么？请用数学公式和直观的解释来说明链式法则在反向传播中的应用。
3. 神经网络中的激活函数有哪些常见类型？请详细比较 ReLU、Sigmoid、Tanh、LeakyReLU、GELU 和 Swish 等激活函数的特点、优缺点和适用场景。

第二部分：模型优化与正则化
4. 什么是过拟合和欠拟合？如何通过 Dropout、L1/L2 正则化、数据增强、早停等技术来避免这些问题？请给出具体的实现建议。
5. 解释批量归一化（Batch Normalization）和层归一化（Layer Normalization）的原理和区别，以及它们在不同场景下的应用。
6. 学习率调度策略有哪些？请比较恒定学习率、学习率衰减、余弦退火、warmup 等策略的特点。

第三部分：卷积神经网络
7. 解释一下卷积神经网络（CNN）的基本结构和工作原理，包括卷积层、池化层、全连接层的作用。
8. 什么是残差网络（ResNet）？跳跃连接是如何解决深层网络的梯度消失问题的？
9. 介绍一下常见的 CNN 架构演进：从 LeNet、AlexNet、VGG 到 ResNet、DenseNet、EfficientNet 的发展历程。

第四部分：Transformer 与注意力机制
10. 详细解释 Transformer 架构中的自注意力机制（Self-Attention）是如何工作的？Query、Key、Value 分别代表什么？
11. 什么是多头注意力（Multi-Head Attention）？它相比单头注意力有什么优势？
12. 解释位置编码（Positional Encoding）的必要性和常见实现方式，包括绝对位置编码和相对位置编码。

第五部分：大语言模型
13. GPT 系列模型的架构特点是什么？它与 BERT 有什么区别？
14. 什么是指令微调（Instruction Tuning）和人类反馈强化学习（RLHF）？它们如何提升模型的对话能力？
15. 解释大语言模型的推理优化技术，包括 KV Cache、量化、剪枝、知识蒸馏等。

请用简洁明了但全面的语言回答以上所有问题，确保涵盖理论基础、实际应用和代码实现建议。"""
        
        private const val TEST_PROMPT_SHORT = "9.11和9.9谁大"
        
        // Medium prompt for Decode performance testing (short input, expects long output)
        private const val TEST_PROMPT_MEDIUM = "写一篇500字左右的短文，主题是：人工智能如何改变我们的日常生活。要求结构清晰，有引言、正文和结论。"
        
        // Prompt types for selection
        enum class PromptType(val label: String, val description: String) {
            SHORT("极短", "9.11和9.9谁大 - 快速测试"),
            MEDIUM("中等", "写500字短文 - 测试Decode性能"),
            LONG("长文", "15道AI问题 - 测试Prefill性能")
        }
        
        // OpenCL numThread mask configurations with descriptions
        val OPENCL_CONFIGS = listOf(
            GpuConfig(1, "禁用AutoTune"),
            GpuConfig(4, "开启AutoTune"),
            GpuConfig(64, "Buffer模式"),
            GpuConfig(128, "Image模式"),
            GpuConfig(65, "Buffer+禁用AutoTune"),      // 64 + 1
            GpuConfig(68, "Buffer+AutoTune"),          // 64 + 4
            GpuConfig(129, "Image+禁用AutoTune"),      // 128 + 1
            GpuConfig(132, "Image+AutoTune"),          // 128 + 4
            GpuConfig(576, "BatchRecord+Buffer"),      // 512 + 64
            GpuConfig(580, "BatchRecord+Buffer+AutoTune"), // 512 + 64 + 4
            GpuConfig(640, "BatchRecord+Image"),       // 512 + 128
            GpuConfig(644, "BatchRecord+Image+AutoTune"),  // 512 + 128 + 4
        )
        
        // CPU thread counts
        val CPU_THREAD_COUNTS = listOf(1, 2, 4, 8)
    }

    data class GpuConfig(val mask: Int, val description: String) {
        override fun toString(): String = "$mask ($description)"
    }

    private val context = application.applicationContext
    val preferences = AppPreferences(context)

    // UI State
    private val _benchmarkResults = MutableStateFlow<List<BenchmarkResult>>(emptyList())
    val benchmarkResults: StateFlow<List<BenchmarkResult>> = _benchmarkResults.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentStatus = MutableStateFlow("准备就绪")
    val currentStatus: StateFlow<String> = _currentStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    // Benchmark configuration
    private val _selectedBackends = MutableStateFlow(setOf("opencl")) // Default to OpenCL only
    val selectedBackends: StateFlow<Set<String>> = _selectedBackends.asStateFlow()

    // For OpenCL: use GPU configs (mask values)
    private val _selectedGpuConfigs = MutableStateFlow(setOf(68, 580)) // Default: buffer+autotune, full
    val selectedGpuConfigs: StateFlow<Set<Int>> = _selectedGpuConfigs.asStateFlow()
    
    // For CPU: use thread counts
    private val _selectedCpuThreads = MutableStateFlow(setOf(4)) // Default: 4 threads
    val selectedCpuThreads: StateFlow<Set<Int>> = _selectedCpuThreads.asStateFlow()
    
    // Prompt type selection (replaces useLongPrompt)
    private val _selectedPromptType = MutableStateFlow(PromptType.MEDIUM) // Default: medium for decode testing
    val selectedPromptType: StateFlow<PromptType> = _selectedPromptType.asStateFlow()
    
    // Legacy: Use long prompt for prefill testing (kept for compatibility)
    private val _useLongPrompt = MutableStateFlow(true)
    val useLongPrompt: StateFlow<Boolean> = _useLongPrompt.asStateFlow()

    // Legacy: kept for UI compatibility
    private val _threadCounts = MutableStateFlow(listOf(1, 2, 4, 8, 64, 128, 512, 580))
    val threadCounts: StateFlow<List<Int>> = _threadCounts.asStateFlow()

    private var benchmarkJob: Job? = null

    /**
     * Toggle backend selection
     */
    fun toggleBackend(backend: String) {
        val current = _selectedBackends.value.toMutableSet()
        if (current.contains(backend)) {
            current.remove(backend)
        } else {
            current.add(backend)
        }
        _selectedBackends.value = current
    }

    /**
     * Toggle GPU config selection
     */
    fun toggleGpuConfig(mask: Int) {
        val current = _selectedGpuConfigs.value.toMutableSet()
        if (current.contains(mask)) {
            current.remove(mask)
        } else {
            current.add(mask)
        }
        _selectedGpuConfigs.value = current
    }
    
    /**
     * Toggle CPU thread count selection
     */
    fun toggleCpuThread(count: Int) {
        val current = _selectedCpuThreads.value.toMutableSet()
        if (current.contains(count)) {
            current.remove(count)
        } else {
            current.add(count)
        }
        _selectedCpuThreads.value = current
    }
    
    /**
     * Toggle long prompt usage (legacy)
     */
    fun toggleLongPrompt() {
        _useLongPrompt.value = !_useLongPrompt.value
    }
    
    /**
     * Set prompt type for testing
     */
    fun setPromptType(type: PromptType) {
        _selectedPromptType.value = type
    }

    /**
     * Set thread counts to test (legacy)
     */
    fun setThreadCounts(counts: List<Int>) {
        _threadCounts.value = counts.filter { it > 0 }.distinct().sorted()
    }

    /**
     * Start benchmark tests
     */
    fun startBenchmark() {
        if (_isRunning.value) {
            Log.w(TAG, "Benchmark already running")
            return
        }

        val configPath = preferences.llmConfigPath
        if (configPath.isEmpty()) {
            _errorMessage.value = "请先在设置中配置 GGUF 模型路径"
            return
        }

        val modelFile = File(configPath)
        if (!modelFile.exists() || !modelFile.canRead()) {
            _errorMessage.value = "无法读取模型文件: $configPath"
            return
        }
        
        val backends = _selectedBackends.value.toList()
        if (backends.isEmpty()) {
            _errorMessage.value = "请至少选择一个后端"
            return
        }

        benchmarkJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _isRunning.value = true
                _errorMessage.value = null
                _benchmarkResults.value = emptyList()
                _progress.value = 0f

                // Build test configurations
                val testConfigs = mutableListOf<TestConfig>()
                
                for (backend in backends) {
                    if (backend == "opencl") {
                        // Use GPU configs for OpenCL
                        val gpuConfigs = _selectedGpuConfigs.value
                        if (gpuConfigs.isEmpty()) {
                            // Default to buffer+autotune if nothing selected
                            testConfigs.add(TestConfig("opencl", 68, "Buffer+AutoTune"))
                        } else {
                            for (mask in gpuConfigs.sorted()) {
                                val desc = OPENCL_CONFIGS.find { it.mask == mask }?.description ?: "mask=$mask"
                                testConfigs.add(TestConfig("opencl", mask, desc))
                            }
                        }
                    } else {
                        // Use thread counts for CPU
                        val cpuThreads = _selectedCpuThreads.value
                        if (cpuThreads.isEmpty()) {
                            testConfigs.add(TestConfig("cpu", 4, "4线程"))
                        } else {
                            for (count in cpuThreads.sorted()) {
                                testConfigs.add(TestConfig("cpu", count, "${count}线程"))
                            }
                        }
                    }
                }
                
                val totalTests = testConfigs.size
                var completedTests = 0

                for (config in testConfigs) {
                    if (!isActive) break

                    val testName = "${config.backend.uppercase()} / ${config.description}"
                    _currentStatus.value = "测试: $testName"
                    Log.i(TAG, "Starting test: $testName (threadNum=${config.threadNum})")

                    try {
                        val result = runSingleTest(modelFile, config.backend, config.threadNum, config.description)
                        _benchmarkResults.value = _benchmarkResults.value + result
                        Log.i(TAG, "Test completed: $testName - prefill=${result.prefillSpeed} tok/s, decode=${result.decodeSpeed} tok/s")
                    } catch (e: Exception) {
                        Log.e(TAG, "Test failed: $testName", e)
                        _benchmarkResults.value = _benchmarkResults.value + BenchmarkResult(
                            backendType = config.backend,
                            threadCount = config.threadNum,
                            configDescription = config.description,
                            error = e.message ?: "Unknown error"
                        )
                    }

                    completedTests++
                    _progress.value = completedTests.toFloat() / totalTests
                }

                _currentStatus.value = "测试完成"
                Log.i(TAG, "All benchmarks completed")

            } catch (e: CancellationException) {
                _currentStatus.value = "测试已取消"
                Log.i(TAG, "Benchmark cancelled")
            } catch (e: Exception) {
                _errorMessage.value = "测试失败: ${e.message}"
                Log.e(TAG, "Benchmark failed", e)
            } finally {
                _isRunning.value = false
            }
        }
    }

    /**
     * Stop running benchmark
     */
    fun stopBenchmark() {
        benchmarkJob?.cancel()
        _currentStatus.value = "正在停止..."
    }

    /**
     * Clear results
     */
    fun clearResults() {
        _benchmarkResults.value = emptyList()
        _errorMessage.value = null
        _progress.value = 0f
        _currentStatus.value = "准备就绪"
    }

    /**
     * Run a single benchmark test with specific configuration
     */
    private suspend fun runSingleTest(
        modelFile: File,
        backendType: String,
        threadNum: Int,
        configDescription: String
    ): BenchmarkResult {
        return withContext(Dispatchers.IO) {
            var session: LlmSession? = null
            try {
                val timings = mutableMapOf<String, Long>()
                var stepStart = System.currentTimeMillis()

                val runtimeConfig = LlamaRuntimeConfig(
                    contextLength = 4096,
                    maxNewTokens = 512,
                    temperature = preferences.llmTemperature,
                    topP = preferences.llmTopP,
                    topK = preferences.llmTopK,
                    minP = preferences.llmMinP,
                    repeatPenalty = 1.1f,
                    enableThinking = preferences.llmThinkingMode
                )

                Log.d(TAG, "Test config (llama): $configDescription")

                // Step 1: Create session object
                stepStart = System.currentTimeMillis()
                session = LlmSession(
                    context = context,
                    modelPath = modelFile,
                    runtimeConfig = runtimeConfig
                )
                timings["1_session_create"] = System.currentTimeMillis() - stepStart

                // Step 2: Load model
                Log.i(TAG, "Loading llama model...")
                stepStart = System.currentTimeMillis()
                session.load()
                timings["2_session_load"] = System.currentTimeMillis() - stepStart
                Log.i(TAG, "LlmSession loaded in ${timings["2_session_load"]}ms")

                // Select prompt based on prompt type setting
                val testPrompt = when (_selectedPromptType.value) {
                    PromptType.SHORT -> TEST_PROMPT_SHORT
                    PromptType.MEDIUM -> TEST_PROMPT_MEDIUM
                    PromptType.LONG -> TEST_PROMPT_LONG
                }
                Log.i(TAG, "Using ${_selectedPromptType.value.label} prompt (${testPrompt.length} chars)")

                // Step 5: First token generation (warmup / kernel compilation)
                stepStart = System.currentTimeMillis()
                var responseText = StringBuilder()
                var firstTokenTime: Long = 0
                var firstTokenReceived = false
                
                val stats = session.generateStructured(
                    messages = listOf(
                        StructuredChatMessage(role = "user", content = testPrompt)
                    ),
                    toolsJson = null,
                    listener = object : ProgressListener {
                    override fun onProgress(chunk: String?): Boolean {
                        if (!firstTokenReceived && chunk != null && chunk.isNotEmpty()) {
                            firstTokenTime = System.currentTimeMillis() - stepStart
                            firstTokenReceived = true
                            Log.i(TAG, "First token received at ${firstTokenTime}ms")
                        }
                        chunk?.let { responseText.append(it) }
                        return false // Don't stop
                    }
                }
                )
                val totalGenerateTime = System.currentTimeMillis() - stepStart
                timings["3_generate_total"] = totalGenerateTime
                timings["3a_first_token"] = firstTokenTime
                timings["3b_rest_tokens"] = totalGenerateTime - firstTokenTime

                // Calculate speeds
                val prefillSpeed = if (stats.prefillTimeUs > 0 && stats.promptLen > 0) {
                    (stats.promptLen * 1_000_000.0 / stats.prefillTimeUs)
                } else 0.0

                val decodeSpeed = if (stats.decodeTimeUs > 0 && stats.decodeLen > 0) {
                    (stats.decodeLen * 1_000_000.0 / stats.decodeTimeUs)
                } else 0.0

                // Log all timings
                Log.i(TAG, "=== Timing breakdown for llama/$configDescription ===")
                timings.forEach { (step, time) ->
                    Log.i(TAG, "  $step: ${time}ms")
                }
                Log.i(TAG, "  Total wall time: ${timings.values.sum()}ms")
                Log.i(TAG, "  Stats: prefillTime=${stats.prefillTimeUs/1000}ms, decodeTime=${stats.decodeTimeUs/1000}ms")
                Log.i(TAG, "  Prefill speed: ${"%.2f".format(prefillSpeed)} tok/s, Decode speed: ${"%.2f".format(decodeSpeed)} tok/s")
                Log.i(TAG, "  promptLen=${stats.promptLen}, decodeLen=${stats.decodeLen}")
                Log.i(TAG, "================================================")
                
                // Log the full response text
                val fullResponse = responseText.toString()
                Log.i(TAG, "=== LLM Response (${fullResponse.length} chars) ===")
                // Split into chunks to avoid logcat truncation (max ~4000 chars per log line)
                fullResponse.chunked(3000).forEachIndexed { index, chunk ->
                    Log.i(TAG, "Response part ${index + 1}: $chunk")
                }
                Log.i(TAG, "=== End of Response ===")

                BenchmarkResult(
                    backendType = "llama",
                    threadCount = threadNum,
                    configDescription = "$configDescription (llama)",
                    promptLen = stats.promptLen,
                    decodeLen = stats.decodeLen,
                    prefillTimeUs = stats.prefillTimeUs,
                    decodeTimeUs = stats.decodeTimeUs,
                    prefillSpeed = prefillSpeed,
                    decodeSpeed = decodeSpeed,
                    loadTimeMs = timings["2_session_load"] ?: 0,
                    firstTokenTimeMs = firstTokenTime,
                    totalGenerateTimeMs = totalGenerateTime,
                    responsePreview = responseText.toString().take(50)
                )

            } finally {
                // Clean up session
                try {
                    session?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing session", e)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        benchmarkJob?.cancel()
    }
}

/**
 * Test configuration for a single benchmark run
 */
private data class TestConfig(
    val backend: String,
    val threadNum: Int,
    val description: String
)

/**
 * Benchmark result for a single test
 */
data class BenchmarkResult(
    val backendType: String,
    val threadCount: Int,
    val configDescription: String = "",
    val promptLen: Long = 0,
    val decodeLen: Long = 0,
    val prefillTimeUs: Long = 0,
    val decodeTimeUs: Long = 0,
    val prefillSpeed: Double = 0.0,
    val decodeSpeed: Double = 0.0,
    val loadTimeMs: Long = 0,
    val firstTokenTimeMs: Long = 0,  // Time to first token (includes prefill + warmup overhead)
    val totalGenerateTimeMs: Long = 0,  // Total generation wall time
    val responsePreview: String = "",
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null

    val prefillSpeedFormatted: String
        get() = if (prefillSpeed > 0) String.format("%.2f tok/s", prefillSpeed) else "N/A"

    val decodeSpeedFormatted: String
        get() = if (decodeSpeed > 0) String.format("%.2f tok/s", decodeSpeed) else "N/A"

    val prefillTimeFormatted: String
        get() = if (prefillTimeUs > 0) String.format("%.2f ms", prefillTimeUs / 1000.0) else "N/A"

    val decodeTimeFormatted: String
        get() = if (decodeTimeUs > 0) String.format("%.2f ms", decodeTimeUs / 1000.0) else "N/A"
    
    // Overhead = wall time to first token - MNN reported prefill time
    val warmupOverheadMs: Long
        get() = if (firstTokenTimeMs > 0 && prefillTimeUs > 0) {
            firstTokenTimeMs - (prefillTimeUs / 1000)
        } else 0
}

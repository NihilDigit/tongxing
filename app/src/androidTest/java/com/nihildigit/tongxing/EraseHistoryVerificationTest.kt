package com.nihildigit.tongxing

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nihildigit.lightwayllama.llm.LlmSession
import com.nihildigit.lightwayllama.llm.LlamaRuntimeConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for verifying MNN's eraseHistory() API behavior.
 * 
 * Prerequisites:
 * - Model files must be accessible at one of the configured paths
 * - Device with sufficient RAM for the model
 * 
 * Note: Due to Android 13+ Scoped Storage restrictions, tests may need manual
 * model setup. Copy model files to the app's private storage if needed:
 *   adb push model_files/. /data/data/com.nihildigit.tongxing/files/MNN/
 */
@RunWith(AndroidJUnit4::class)
@Ignore("MNN-specific eraseHistory tests are disabled after llama.cpp migration")
class EraseHistoryVerificationTest {
    
    companion object {
        private const val TAG = "EraseHistoryTest"
        
        // Try multiple paths in order of preference
        private val MODEL_PATHS = listOf(
            { ctx: Context -> File(ctx.filesDir, "models/model.gguf") },
            { ctx: Context -> ctx.getExternalFilesDir(null)?.let { File(it, "models/model.gguf") } },
            { _: Context -> File(Environment.getExternalStorageDirectory(), "model.gguf") }
        )
    }
    
    private var llmSession: LlmSession? = null
    private lateinit var modelConfigPath: File
    
    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        Log.i(TAG, "Setup starting...")
        Log.i(TAG, "App package: ${context.packageName}")
        Log.i(TAG, "filesDir: ${context.filesDir}")
        Log.i(TAG, "externalFilesDir: ${context.getExternalFilesDir(null)}")
        
        // Find an accessible model config
        var foundPath: File? = null
        for (pathProvider in MODEL_PATHS) {
            val path = pathProvider(context)
            if (path != null) {
                Log.i(TAG, "Checking path: $path (exists=${path.exists()}, canRead=${path.canRead()})")
                if (path.exists() && path.canRead()) {
                    foundPath = path
                    break
                }
            }
        }
        
        // Skip tests if model is not available at any location
        if (foundPath == null) {
            Log.w(TAG, "Model not found at any location. To run these tests, copy model files to:")
            Log.w(TAG, "  ${context.filesDir}/MNN/config.json")
            Log.w(TAG, "Using: adb push local_mnn_dir/. \${context.filesDir}/MNN/")
        }
        
        assumeTrue(
            "Model config not found at any accessible location. See logcat for setup instructions.",
            foundPath != null
        )
        
        modelConfigPath = foundPath!!
        Log.i(TAG, "Using model config at: $modelConfigPath")
        
        llmSession = LlmSession(
            context = context,
            modelPath = modelConfigPath,
            runtimeConfig = LlamaRuntimeConfig(
                maxNewTokens = 100,
                temperature = 0.7f,
                topP = 0.8f,
                topK = 20,
                minP = 0.0f,
                enableThinking = false
            )
        )
        
        try {
            llmSession?.load()
            Log.i(TAG, "LlmSession loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LlmSession", e)
            throw e
        }
    }
    
    @After
    fun teardown() {
        llmSession?.release()
        llmSession = null
    }
    
    @Test
    fun testGetCurrentSeqLen_initiallyZero() {
        val session = llmSession ?: return
        
        // After load, seqLen should be 0 or minimal
        val initialSeqLen = session.getCurrentSeqLen()
        Log.i(TAG, "Initial seqLen: $initialSeqLen")
        
        assertTrue("Initial seqLen should be >= 0", initialSeqLen >= 0)
    }
    
    @Test
    fun testPrefillOnly_increasesSeqLen() {
        val session = llmSession ?: return
        
        val beforeSeqLen = session.getCurrentSeqLen()
        Log.i(TAG, "SeqLen before prefill: $beforeSeqLen")
        
        // Prefill a simple prompt
        val prompt = "You are a helpful assistant."
        val newSeqLen = session.prefillOnly(prompt)
        
        Log.i(TAG, "SeqLen after prefill: $newSeqLen")
        
        assertTrue("prefillOnly should return positive seqLen", newSeqLen > 0)
        assertTrue("prefillOnly should increase seqLen", newSeqLen > beforeSeqLen)
        
        // Verify getCurrentSeqLen matches
        val currentSeqLen = session.getCurrentSeqLen()
        assertEquals("getCurrentSeqLen should match prefillOnly result", newSeqLen, currentSeqLen)
    }
    
    @Test
    fun testEraseHistory_resetToBase() {
        val session = llmSession ?: return
        
        // Step 1: Prefill stem (system prompt)
        val stemPrompt = "You are a helpful assistant. You can use tools."
        val baseSeqLen = session.prefillOnly(stemPrompt)
        Log.i(TAG, "Base seqLen after stem prefill: $baseSeqLen")
        
        assertTrue("Stem prefill should return positive seqLen", baseSeqLen > 0)
        
        // Step 2: Prefill task (user query simulation)
        val taskPrompt = "User: What is 2+2? Please calculate."
        val taskSeqLen = session.prefillOnly(taskPrompt)
        Log.i(TAG, "SeqLen after task prefill: $taskSeqLen")
        
        assertTrue("Task prefill should increase seqLen", taskSeqLen > baseSeqLen)
        
        // Step 3: Erase task tokens (keep stem)
        Log.i(TAG, "Erasing history from $baseSeqLen to $taskSeqLen")
        session.eraseHistory(baseSeqLen, taskSeqLen)
        
        // Step 4: Verify seqLen is back to base
        val afterEraseSeqLen = session.getCurrentSeqLen()
        Log.i(TAG, "SeqLen after erase: $afterEraseSeqLen")
        
        // This is the critical verification for MNN eraseHistory behavior
        assertEquals(
            "After eraseHistory($baseSeqLen, $taskSeqLen), seqLen should return to baseSeqLen",
            baseSeqLen,
            afterEraseSeqLen
        )
    }
    
    @Test
    fun testEraseHistory_withZeroEnd() {
        val session = llmSession ?: return
        
        // Step 1: Prefill stem
        val stemPrompt = "System: You are helpful."
        val baseSeqLen = session.prefillOnly(stemPrompt)
        Log.i(TAG, "Base seqLen: $baseSeqLen")
        
        // Step 2: Prefill task
        val taskPrompt = "User: Hello there!"
        val taskSeqLen = session.prefillOnly(taskPrompt)
        Log.i(TAG, "Task seqLen: $taskSeqLen")
        
        // Step 3: Erase with end=0 (should erase from begin to current end)
        Log.i(TAG, "Erasing history from $baseSeqLen with end=0")
        session.eraseHistory(baseSeqLen, 0)
        
        val afterEraseSeqLen = session.getCurrentSeqLen()
        Log.i(TAG, "SeqLen after erase (end=0): $afterEraseSeqLen")
        
        assertEquals(
            "eraseHistory with end=0 should erase to current end",
            baseSeqLen,
            afterEraseSeqLen
        )
    }
    
    @Test
    fun testReset_clearsAllHistory() {
        val session = llmSession ?: return
        
        // Prefill something
        val prompt = "System: You are a test assistant."
        session.prefillOnly(prompt)
        
        val beforeReset = session.getCurrentSeqLen()
        Log.i(TAG, "SeqLen before reset: $beforeReset")
        
        assertTrue("Should have some tokens before reset", beforeReset > 0)
        
        // Reset
        session.reset()
        
        val afterReset = session.getCurrentSeqLen()
        Log.i(TAG, "SeqLen after reset: $afterReset")
        
        assertEquals("Reset should clear all history", 0L, afterReset)
    }
    
    @Test
    fun testStemWorkflow_fullCycle() {
        val session = llmSession ?: return
        
        Log.i(TAG, "=== Full Stem Workflow Test ===")
        
        // Step 1: Warmup stem
        val stemPrompt = """
            You are a helpful AI assistant. You have access to the following tools:
            
            1. calculator: Performs arithmetic calculations
            2. weather: Gets weather information
            
            When using tools, respond with JSON.
        """.trimIndent()
        
        val baseSeqLen = session.prefillOnly(stemPrompt)
        Log.i(TAG, "Step 1 - Stem warmup complete. baseSeqLen=$baseSeqLen")
        
        assertTrue("Stem should produce tokens", baseSeqLen > 0)
        
        // Step 2: Simulate Task 1
        val task1Prompt = "User: What is the weather in Beijing?"
        val task1SeqLen = session.prefillOnly(task1Prompt)
        Log.i(TAG, "Step 2 - Task 1 prefilled. seqLen=$task1SeqLen")
        
        val task1Tokens = task1SeqLen - baseSeqLen
        Log.i(TAG, "Task 1 used $task1Tokens tokens")
        
        // Step 3: Prune Task 1
        session.eraseHistory(baseSeqLen, task1SeqLen)
        val afterTask1Prune = session.getCurrentSeqLen()
        Log.i(TAG, "Step 3 - Task 1 pruned. seqLen=$afterTask1Prune")
        
        assertEquals("After Task 1 prune, should return to base", baseSeqLen, afterTask1Prune)
        
        // Step 4: Simulate Task 2
        val task2Prompt = "User: Calculate 15 * 7 + 3"
        val task2SeqLen = session.prefillOnly(task2Prompt)
        Log.i(TAG, "Step 4 - Task 2 prefilled. seqLen=$task2SeqLen")
        
        val task2Tokens = task2SeqLen - baseSeqLen
        Log.i(TAG, "Task 2 used $task2Tokens tokens")
        
        // Step 5: Prune Task 2
        session.eraseHistory(baseSeqLen, task2SeqLen)
        val afterTask2Prune = session.getCurrentSeqLen()
        Log.i(TAG, "Step 5 - Task 2 pruned. seqLen=$afterTask2Prune")
        
        assertEquals("After Task 2 prune, should return to base", baseSeqLen, afterTask2Prune)
        
        Log.i(TAG, "=== Full Stem Workflow Test PASSED ===")
    }
    
    @Test
    fun testEraseHistory_boundaryConditions() {
        val session = llmSession ?: return
        
        // Test 1: Erase empty range (begin == end)
        val prompt = "Test prompt"
        val seqLen = session.prefillOnly(prompt)
        
        Log.i(TAG, "Testing erase with begin==end at position $seqLen")
        session.eraseHistory(seqLen, seqLen)
        
        val afterEmptyErase = session.getCurrentSeqLen()
        Log.i(TAG, "After empty erase: $afterEmptyErase")
        
        // Empty erase should not change anything
        assertEquals("Empty erase should not change seqLen", seqLen, afterEmptyErase)
        
        // Test 2: Erase from 0 (full clear)
        Log.i(TAG, "Testing erase from 0")
        session.eraseHistory(0, afterEmptyErase)
        
        val afterFullErase = session.getCurrentSeqLen()
        Log.i(TAG, "After full erase: $afterFullErase")
        
        assertEquals("Erase from 0 should clear all", 0L, afterFullErase)
    }
    
    /**
     * 验证 MNN eraseHistory 的懒执行行为
     * 
     * 核心验证：eraseHistory 只是调度删除，
     * 真正的删除在下一次推理时应用
     */
    @Test
    fun testEraseHistory_lazyExecution_appliedOnNextPrefill() {
        val session = llmSession ?: return
        
        Log.i(TAG, "=== Testing Lazy Erase Execution ===")
        
        // Step 1: Prefill stem
        val stemPrompt = "You are a helpful assistant."
        val baseSeqLen = session.prefillOnly(stemPrompt)
        Log.i(TAG, "Step 1 - Base seqLen: $baseSeqLen")
        
        // Step 2: Prefill task1
        val task1Prompt = "User: What is 2+2?"
        val afterTask1 = session.prefillOnly(task1Prompt)
        val task1Tokens = afterTask1 - baseSeqLen
        Log.i(TAG, "Step 2 - After task1: $afterTask1 (task1 added $task1Tokens tokens)")
        
        // Step 3: Erase task1 (lazy - won't apply immediately)
        session.eraseHistory(baseSeqLen, afterTask1)
        val afterErase = session.getCurrentSeqLen()
        Log.i(TAG, "Step 3 - After eraseHistory: $afterErase (expected still $afterTask1 due to lazy)")
        
        // Verify lazy behavior: seqLen should NOT change immediately
        assertEquals(
            "eraseHistory should be lazy - seqLen unchanged immediately",
            afterTask1,
            afterErase
        )
        
        // Step 4: Prefill task2 - this should trigger the lazy deletion
        val task2Prompt = "User: Hello!"
        val afterTask2 = session.prefillOnly(task2Prompt)
        Log.i(TAG, "Step 4 - After task2: $afterTask2")
        
        // 验证关键点：
        // 如果懒删除生效：afterTask2 ≈ baseSeqLen + task2_tokens (大约 baseSeqLen + 5)
        // 如果懒删除没生效：afterTask2 ≈ afterTask1 + task2_tokens (大约 afterTask1 + 5)
        
        // task2 很短 ("User: Hello!") 大约 5 个 token
        // 所以如果懒删除生效了，afterTask2 应该接近 baseSeqLen + 5
        // 而不是 afterTask1 + 5
        
        val expectedIfEraseApplied = baseSeqLen + 10  // baseSeqLen + task2 tokens (估计)
        val expectedIfEraseNotApplied = afterTask1 + 3  // afterTask1 + task2 tokens (最小)
        
        Log.i(TAG, "Expected if erase applied: around $expectedIfEraseApplied")
        Log.i(TAG, "Expected if erase NOT applied: around $expectedIfEraseNotApplied")
        
        assertTrue(
            "Lazy erase should be applied on next prefill: afterTask2($afterTask2) should be < $expectedIfEraseNotApplied",
            afterTask2 < expectedIfEraseNotApplied
        )
        
        // 更精确的验证：afterTask2 应该接近 baseSeqLen + task2_tokens
        val actualTask2Tokens = afterTask2 - baseSeqLen
        Log.i(TAG, "Actual task2 tokens (relative to base): $actualTask2Tokens")
        
        assertTrue(
            "After lazy erase applied, task2 should be relative to baseSeqLen, got $actualTask2Tokens tokens",
            actualTask2Tokens in 1..15  // task2 应该是少量 token
        )
        
        Log.i(TAG, "=== Lazy Erase Verification PASSED ===")
    }
    
    /**
     * 验证完整的 Stem 工作流（考虑懒删除）
     * 
     * 这模拟了实际使用场景：
     * 1. warmup stem
     * 2. 处理 task1, 然后 prune
     * 3. 处理 task2, 验证 task2 是基于 stem 而不是累加
     */
    @Test
    fun testStemWorkflow_withLazyErase() {
        val session = llmSession ?: return
        
        Log.i(TAG, "=== Full Stem Workflow with Lazy Erase ===")
        
        // Warmup stem
        val stemPrompt = "You are a helpful AI assistant with tools."
        val baseSeqLen = session.prefillOnly(stemPrompt)
        Log.i(TAG, "Stem warmup complete. baseSeqLen=$baseSeqLen")
        
        // Task 1
        val task1Prompt = "User: Calculate 1+1"
        val task1SeqLen = session.prefillOnly(task1Prompt)
        Log.i(TAG, "Task 1 complete. seqLen=$task1SeqLen")
        
        // Prune task 1 (lazy)
        session.eraseHistory(baseSeqLen, task1SeqLen)
        Log.i(TAG, "Task 1 pruned (lazy). seqLen still=${session.getCurrentSeqLen()}")
        
        // Task 2 - lazy deletion should be applied here
        val task2Prompt = "User: What is the weather?"
        val task2SeqLen = session.prefillOnly(task2Prompt)
        Log.i(TAG, "Task 2 complete. seqLen=$task2SeqLen")
        
        // 验证 task2 是基于 stem，而不是 task1 之上
        val task2TokensFromBase = task2SeqLen - baseSeqLen
        val task1Tokens = task1SeqLen - baseSeqLen
        
        Log.i(TAG, "Task 1 tokens: $task1Tokens")
        Log.i(TAG, "Task 2 tokens from base: $task2TokensFromBase")
        
        // 如果懒删除生效，task2SeqLen 应该远小于 task1SeqLen + task1Tokens
        assertTrue(
            "Task 2 should build on stem (baseSeqLen=$baseSeqLen), not task1. Got seqLen=$task2SeqLen",
            task2SeqLen < task1SeqLen + task1Tokens
        )
        
        // Prune task 2 (lazy)
        session.eraseHistory(baseSeqLen, task2SeqLen)
        
        // Task 3 - verify the pattern continues
        val task3Prompt = "User: Hello"
        val task3SeqLen = session.prefillOnly(task3Prompt)
        Log.i(TAG, "Task 3 complete. seqLen=$task3SeqLen")
        
        val task3TokensFromBase = task3SeqLen - baseSeqLen
        Log.i(TAG, "Task 3 tokens from base: $task3TokensFromBase")
        
        assertTrue(
            "Task 3 should also build on stem, not accumulated. Got seqLen=$task3SeqLen",
            task3SeqLen < task2SeqLen + task2TokensFromBase
        )
        
        Log.i(TAG, "=== Stem Workflow with Lazy Erase PASSED ===")
    }
}

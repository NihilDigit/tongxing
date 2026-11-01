package com.nihildigit.tongxing.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File

internal object ModelImportManager {
    private val lock = Any()
    private var generation = 0L
    private var activeJob: Job? = null

    fun start(
        scope: CoroutineScope,
        operation: suspend ((() -> Unit) -> Unit) -> File?,
        onResult: (File?) -> Unit
    ): Job = synchronized(lock) {
        val request = ++generation
        activeJob?.cancel()
        scope.launch {
            val requestContext = coroutineContext
            val result = operation { publish ->
                // 文件提交与新选择共用锁，旧任务无法在新选择之后覆盖同名模型。
                synchronized(lock) {
                    requestContext.ensureActive()
                    if (request != generation) throw CancellationException("Model selection superseded")
                    publish()
                }
            }
            synchronized(lock) {
                requestContext.ensureActive()
                if (request == generation) onResult(result)
            }
        }.also { job ->
            activeJob = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (activeJob === job) activeJob = null
                }
            }
        }
    }
}

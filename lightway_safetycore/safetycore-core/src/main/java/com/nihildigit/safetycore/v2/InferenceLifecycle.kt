package com.nihildigit.safetycore.v2

import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class InferenceLifecycle {
    private val lock = ReentrantLock(true)
    private val closing = AtomicBoolean(false)
    private var closed = false

    fun <T> run(operation: () -> T): T = lock.withLock {
        check(!closing.get()) { "Frame processor is closed" }
        operation()
    }

    fun close(release: () -> Unit) {
        closing.set(true)
        lock.withLock {
            if (!closed) {
                try { release() } finally { closed = true }
            }
        }
    }
}

internal fun awaitInferenceTasks(tasks: List<Future<*>>) {
    var interrupted = false
    var failure: Exception? = null
    for (task in tasks) {
        while (true) {
            try {
                task.get()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (error: Exception) {
                if (failure == null) failure = error
                break
            }
        }
    }
    // 原生推理完成前保持资源所有权；中断状态在全部任务退出后恢复。
    if (interrupted) Thread.currentThread().interrupt()
    failure?.let { throw it }
}

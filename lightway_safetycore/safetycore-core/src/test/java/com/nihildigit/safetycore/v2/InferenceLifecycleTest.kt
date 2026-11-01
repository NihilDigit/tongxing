package com.nihildigit.safetycore.v2

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class InferenceLifecycleTest {
    @Test
    fun closeWaitsForOperationAndReleasesOnce() {
        val lifecycle = InferenceLifecycle()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val releases = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val operation = pool.submit {
                lifecycle.run { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = pool.submit {
                closeStarted.countDown()
                lifecycle.close { releases.incrementAndGet() }
            }
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            try { close.get(100, TimeUnit.MILLISECONDS); fail("Close finished during inference") }
            catch (_: TimeoutException) { }
            assertEquals(0, releases.get())
            finish.countDown()
            operation.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)
            lifecycle.close { releases.incrementAndGet() }
            assertEquals(1, releases.get())
            try { lifecycle.run { fail("Closed processor accepted work") }; fail("Expected closed state") }
            catch (_: IllegalStateException) { }
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun failedTaskStillDrainsOtherInference() {
        val workers = Executors.newFixedThreadPool(2)
        val waiter = Executors.newSingleThreadExecutor()
        val running = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val joiningPending = CountDownLatch(1)
        val observedFailure = AtomicBoolean(false)
        try {
            val failed = workers.submit { throw IllegalStateException("model failed") }
            val pending = workers.submit<Boolean> { running.countDown(); finish.await(5, TimeUnit.SECONDS) }
            val observedPending = object : Future<Boolean> by pending {
                override fun get(): Boolean {
                    joiningPending.countDown()
                    return pending.get()
                }
            }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            val joined = waiter.submit {
                try { awaitInferenceTasks(listOf(failed, observedPending)); fail("Expected inference error") }
                catch (_: java.util.concurrent.ExecutionException) { observedFailure.set(true) }
            }
            assertTrue(joiningPending.await(5, TimeUnit.SECONDS))
            assertFalse(observedFailure.get())
            finish.countDown()
            joined.get(5, TimeUnit.SECONDS)
            assertTrue(pending.isDone)
            assertTrue(observedFailure.get())
        } finally {
            finish.countDown()
            workers.shutdownNow()
            waiter.shutdownNow()
        }
    }

    @Test
    fun interruptIsRestoredAfterPendingTaskFinishes() {
        val workers = Executors.newSingleThreadExecutor()
        val pending = workers.submit { 7 }
        try {
            Thread.currentThread().interrupt()
            awaitInferenceTasks(listOf(pending))
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(pending.isDone)
        } finally {
            Thread.interrupted()
            workers.shutdownNow()
        }
    }
}

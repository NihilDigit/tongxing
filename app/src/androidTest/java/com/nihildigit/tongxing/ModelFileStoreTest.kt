package com.nihildigit.tongxing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nihildigit.tongxing.util.ModelFileStore
import com.nihildigit.tongxing.util.ModelImportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ModelFileStoreTest {
    private lateinit var directory: File
    private lateinit var target: File
    private val original = "existing model".toByteArray()
    private val model = ByteArray(300_000).apply {
        "GGUF".toByteArray().copyInto(this)
        this[4] = 3
    }

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "model-store-test-").toFile()
        target = ModelFileStore.destination(directory, "model.gguf")
        target.writeBytes(original)
    }

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    private fun assertOriginalPreserved() {
        assertArrayEquals(original, target.readBytes())
        assertEquals(listOf("model.gguf"), directory.list()!!.toList())
    }

    @Test
    fun publishesCompleteFileAfterCopyAndClosesInput() = runBlocking {
        var closed = false
        val input = object : ByteArrayInputStream(model) {
            override fun close() { closed = true; super.close() }
        }
        ModelFileStore.save({ input }, target, model.size.toLong()) {
            assertArrayEquals(original, target.readBytes())
        }
        assertTrue(closed)
        assertArrayEquals(model, target.readBytes())
        assertEquals(1, directory.list()!!.size)
    }

    @Test
    fun truncatedResponsePreservesOriginal() = runBlocking {
        try {
            ModelFileStore.save({ model.inputStream() }, target, model.size.toLong() + 1)
            fail("Truncated response must fail")
        } catch (_: IOException) {
            assertOriginalPreserved()
        }
    }

    @Test
    fun inputCloseFailurePreservesOriginal() = runBlocking {
        val input = object : ByteArrayInputStream(model) {
            override fun close() {
                super.close()
                throw IOException("Simulated provider close failure")
            }
        }
        try {
            ModelFileStore.save({ input }, target)
            fail("Closing the input must complete before publication")
        } catch (_: IOException) {
            assertOriginalPreserved()
        }
    }

    @Test
    fun newerSelectionOwnsSameFileAndCallback() = runBlocking {
        val waitingToCommit = CountDownLatch(1)
        val resumeOldCommit = CountDownLatch(1)
        val newerModel = model.copyOf().apply { this[lastIndex] = 42 }
        val callbacks = mutableListOf<String>()
        val first = ModelImportManager.start(this, { commit ->
            ModelFileStore.save({ model.inputStream() }, target, commit = { publish ->
                waitingToCommit.countDown()
                check(resumeOldCommit.await(10, TimeUnit.SECONDS))
                commit(publish)
            })
        }) { callbacks.add("old") }
        try {
            withContext(Dispatchers.IO) { assertTrue(waitingToCommit.await(5, TimeUnit.SECONDS)) }
            val second = ModelImportManager.start(this, { commit ->
                ModelFileStore.save({ newerModel.inputStream() }, target, commit = commit)
            }) { callbacks.add("new") }
            second.join()
        } finally {
            resumeOldCommit.countDown()
        }
        first.join()
        assertTrue(first.isCancelled)
        assertArrayEquals(newerModel, target.readBytes())
        assertEquals(listOf("new"), callbacks)
        assertEquals(1, directory.list()!!.size)
    }

    @Test
    fun invalidHeaderPreservesOriginal() = runBlocking {
        try {
            ModelFileStore.save({ ByteArray(64).inputStream() }, target)
            fail("Non-GGUF response must fail")
        } catch (_: IOException) {
            assertOriginalPreserved()
        }
    }

    @Test
    fun readFailurePreservesOriginal() = runBlocking {
        val input = object : ByteArrayInputStream(model) {
            private var reads = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw IOException("Simulated interrupted source")
                return super.read(buffer, offset, length)
            }
        }
        try {
            ModelFileStore.save({ input }, target)
            fail("Interrupted source must fail")
        } catch (_: IOException) {
            assertOriginalPreserved()
        }
    }

    @Test
    fun cancellationPreservesOriginal() = runBlocking {
        val job = launch {
            ModelFileStore.save({ model.inputStream() }, target) {
                currentCoroutineContext().cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertOriginalPreserved()
    }

    @Test
    fun rejectsPathsOutsideModelDirectory() {
        for (name in listOf("../outside.gguf", "folder/model.gguf", "..\\outside.gguf", "/outside.gguf")) {
            try {
                ModelFileStore.destination(directory, name)
                fail("Unexpectedly accepted $name")
            } catch (_: IllegalArgumentException) {
                assertOriginalPreserved()
            }
        }
    }

    @Test
    fun replacesModelOnExternalAppStorage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir(null))
        val externalDirectory = Files.createTempDirectory(root.toPath(), "model-store-test-").toFile()
        try {
            val externalTarget = ModelFileStore.destination(externalDirectory, "model.gguf")
            externalTarget.writeBytes(original)
            ModelFileStore.save({ model.inputStream() }, externalTarget, model.size.toLong())
            assertArrayEquals(model, externalTarget.readBytes())
            assertEquals(1, externalDirectory.list()!!.size)
        } finally {
            externalDirectory.deleteRecursively()
        }
    }
}

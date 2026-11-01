package com.nihildigit.tongxing.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

internal object ModelFileStore {
    fun destination(directory: File, name: String): File {
        require(name.endsWith(".gguf", ignoreCase = true) &&
            '/' !in name && '\\' !in name && ':' !in name) { "Invalid GGUF file name" }
        val root = directory.canonicalFile
        val target = File(root, name).canonicalFile
        require(target.parentFile == root) { "Model path is outside the model directory" }
        return target
    }

    suspend fun save(
        openInput: () -> InputStream,
        target: File,
        expectedBytes: Long = -1,
        commit: (() -> Unit) -> Unit = { it() },
        onProgress: suspend (Long) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val directory = requireNotNull(target.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create model directory")
        }
        val temporary = File.createTempFile("model-", ".part", directory)
        try {
            openInput().use { source ->
                var copied = 0L
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied)
                    }
                    if (expectedBytes >= 0 && copied != expectedBytes) {
                        throw IOException("Incomplete model file")
                    }
                    if (copied < 24 || DataInputStream(temporary.inputStream()).use {
                            it.readInt() != 0x47475546
                        }) {
                        throw IOException("Invalid GGUF header")
                    }
                    output.fd.sync()
                }
            }
            coroutineContext.ensureActive()
            // 输入和输出均关闭后才提交，同目录原子替换保留完整文件边界。
            commit {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            }
            target
        } finally {
            temporary.delete()
        }
    }
}

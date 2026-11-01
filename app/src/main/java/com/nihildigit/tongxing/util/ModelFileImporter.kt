package com.nihildigit.tongxing.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object ModelFileImporter {
    private const val MODEL_DIR_NAME = "models"

    internal suspend fun importGgufFromUri(
        context: Context,
        uri: Uri,
        commit: (() -> Unit) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            val displayName = documentFile?.name ?: return@withContext null
            if (!displayName.endsWith(".gguf", ignoreCase = true)) {
                return@withContext null
            }

            val targetDir = context.getExternalFilesDir(MODEL_DIR_NAME)
                ?: File(context.filesDir, MODEL_DIR_NAME)
            val targetFile = ModelFileStore.destination(targetDir, displayName)
            val size = documentFile.length().takeIf { it > 0 } ?: -1
            ModelFileStore.save(
                { context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open model") },
                targetFile, size, commit
            )
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

package com.nihildigit.tongxing.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nihildigit.tongxing.util.ModelFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloadWorker {
    const val KEY_MODEL_URL = "model_url"
    const val KEY_MODEL_ID = "model_id"
    const val KEY_FILE_NAME = "file_name"
    const val KEY_PROGRESS = "progress"
    const val KEY_STATUS = "status"
    const val KEY_ERROR = "error"
    const val KEY_OUTPUT_PATH = "output_path"

    const val CHANNEL_ID = "model_download_channel"
    const val NOTIFICATION_ID = 1001

    fun enqueue(context: Context, modelId: String, modelUrl: String, fileName: String? = null): java.util.UUID {
        val inputData = Data.Builder()
            .putString(KEY_MODEL_ID, modelId)
            .putString(KEY_MODEL_URL, modelUrl)
            .apply { fileName?.let { putString(KEY_FILE_NAME, it) } }
            .build()

        val request = androidx.work.OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                java.util.concurrent.TimeUnit.SECONDS
            )
            .addTag("model_download_$modelId")
            .build()

        androidx.work.WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "model_download_$modelId",
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )

        return request.id
    }

    fun cancel(context: Context, modelId: String) {
        androidx.work.WorkManager.getInstance(context)
            .cancelUniqueWork("model_download_$modelId")
    }
}

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ModelDownloadWorker"
        private const val MODEL_DIR_NAME = "models"
    }

    override suspend fun doWork(): Result {
        val modelUrl = inputData.getString(ModelDownloadWorker.KEY_MODEL_URL) ?: return Result.failure(
            workDataOf(ModelDownloadWorker.KEY_ERROR to "Missing model URL")
        )
        val modelId = inputData.getString(ModelDownloadWorker.KEY_MODEL_ID) ?: "unknown"
        val fileName = inputData.getString(ModelDownloadWorker.KEY_FILE_NAME)
            ?: extractFileNameFromUrl(modelUrl)
            ?: "$modelId.gguf"

        Log.i(TAG, "Starting download for model: $modelId")

        return try {
            setForeground(createForegroundInfo("正在下载模型: $modelId", 0))

            val modelsDir = applicationContext.getExternalFilesDir(MODEL_DIR_NAME)
                ?: File(applicationContext.filesDir, MODEL_DIR_NAME)
            if (!modelsDir.exists()) modelsDir.mkdirs()

            val destFile = ModelFileStore.destination(modelsDir, fileName)

            downloadGgufFile(modelUrl, destFile) { progress ->
                setForeground(createForegroundInfo("正在下载模型: $modelId", progress))
                setProgress(workDataOf(ModelDownloadWorker.KEY_PROGRESS to progress))
            }

            Log.i(TAG, "Download completed for model: $modelId, path: ${destFile.absolutePath}")
            Result.success(workDataOf(
                ModelDownloadWorker.KEY_STATUS to "SUCCESS",
                ModelDownloadWorker.KEY_PROGRESS to 100,
                ModelDownloadWorker.KEY_OUTPUT_PATH to destFile.absolutePath
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for model: $modelId", e)
            Result.failure(workDataOf(
                ModelDownloadWorker.KEY_STATUS to "FAILED",
                ModelDownloadWorker.KEY_ERROR to (e.message ?: "Unknown error")
            ))
        }
    }

    private suspend fun downloadGgufFile(
        url: String,
        destFile: File,
        onProgress: suspend (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "TongXing-Android/1.0")
        }

        try {
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP error: $responseCode")
            }

            val total = conn.contentLengthLong
            var lastProgress = -1
            ModelFileStore.save({ conn.inputStream }, destFile, total) { downloaded ->
                if (total > 0) {
                    val progress = (downloaded * 100 / total).toInt().coerceIn(0, 99)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        onProgress(progress)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractFileNameFromUrl(url: String): String? {
        return try {
            val path = URL(url).path
            val fileName = path.substringAfterLast('/')
            if (fileName.isNotBlank() && fileName.lowercase().endsWith(".gguf")) {
                fileName
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createForegroundInfo(progressText: String, progress: Int): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, ModelDownloadWorker.CHANNEL_ID)
            .setContentTitle("模型下载")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                ModelDownloadWorker.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(ModelDownloadWorker.NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ModelDownloadWorker.CHANNEL_ID,
            "模型下载",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于显示模型下载进度"
        }

        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}

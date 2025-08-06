/*
 * Copyright 2025 Braille Bridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.braillebridge2.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloadWorker"
private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel"
private var channelCreated = false

const val KEY_MODEL_URL = "model_url"
const val KEY_MODEL_FILE_NAME = "model_file_name"
const val KEY_ACCESS_TOKEN = "access_token"
const val KEY_TOTAL_BYTES = "total_bytes"
const val KEY_RECEIVED_BYTES = "received_bytes"
const val KEY_DOWNLOAD_RATE = "download_rate"
const val KEY_REMAINING_MS = "remaining_ms"
const val KEY_ERROR_MESSAGE = "error_message"

@RequiresApi(Build.VERSION_CODES.O)
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val externalFilesDir = context.getExternalFilesDir(null)
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notificationId: Int = params.id.hashCode()

    init {
        if (!channelCreated) {
            val channel = NotificationChannel(
                FOREGROUND_NOTIFICATION_CHANNEL_ID,
                "Model Downloading",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for model downloading"
            }
            notificationManager.createNotificationChannel(channel)
            channelCreated = true
        }
    }

    override suspend fun doWork(): Result {
        val modelUrl = inputData.getString(KEY_MODEL_URL)
        val fileName = inputData.getString(KEY_MODEL_FILE_NAME)
        val accessToken = inputData.getString(KEY_ACCESS_TOKEN)
        val totalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)

        if (modelUrl == null || fileName == null) {
            return Result.failure()
        }

        return withContext(Dispatchers.IO) {
            try {
                setForeground(createForegroundInfo(0, "Gemma-3n Model"))

                val url = URL(modelUrl)
                val connection = url.openConnection() as HttpURLConnection

                if (accessToken != null) {
                    Log.d(TAG, "Using access token for authentication")
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                }

                val outputFile = File(externalFilesDir, fileName)
                val outputFileBytes = outputFile.length()

                if (outputFileBytes > 0) {
                    Log.d(TAG, "Resuming download from byte: $outputFileBytes")
                    connection.setRequestProperty("Range", "bytes=$outputFileBytes-")
                }

                connection.connect()
                Log.d(TAG, "HTTP Response code: ${connection.responseCode}")

                if (connection.responseCode == HttpURLConnection.HTTP_OK ||
                    connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {

                    val inputStream = connection.inputStream
                    val outputStream = FileOutputStream(outputFile, true)

                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesRead: Int
                    var downloadedBytes = outputFileBytes
                    var lastProgressUpdate = 0L
                    var deltaBytes = 0L

                    val bytesReadBuffer: MutableList<Long> = mutableListOf()
                    val latencyBuffer: MutableList<Long> = mutableListOf()

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        deltaBytes += bytesRead

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastProgressUpdate > 500) { // Update every 500ms
                            var bytesPerMs = 0f
                            if (lastProgressUpdate != 0L) {
                                if (bytesReadBuffer.size == 5) {
                                    bytesReadBuffer.removeAt(0)
                                }
                                bytesReadBuffer.add(deltaBytes)
                                if (latencyBuffer.size == 5) {
                                    latencyBuffer.removeAt(0)
                                }
                                latencyBuffer.add(currentTime - lastProgressUpdate)
                                deltaBytes = 0L
                                bytesPerMs = bytesReadBuffer.sum().toFloat() / latencyBuffer.sum()
                            }

                            val remainingMs = if (bytesPerMs > 0f && totalBytes > 0L) {
                                (totalBytes - downloadedBytes) / bytesPerMs
                            } else 0f

                            val progress = if (totalBytes > 0L) {
                                (downloadedBytes * 100 / totalBytes).toInt()
                            } else 0

                            setProgress(
                                Data.Builder()
                                    .putLong(KEY_RECEIVED_BYTES, downloadedBytes)
                                    .putLong(KEY_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                                    .putLong(KEY_REMAINING_MS, remainingMs.toLong())
                                    .build()
                            )

                            setForeground(createForegroundInfo(progress, "Gemma-3n Model"))
                            lastProgressUpdate = currentTime
                        }
                    }

                    outputStream.close()
                    inputStream.close()
                    Log.d(TAG, "Download completed successfully")
                    Result.success()
                } else {
                    throw IOException("HTTP error code: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR_MESSAGE, e.message ?: "Unknown error")
                        .build()
                )
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0, "Model")
    }

    private fun createForegroundInfo(progress: Int, modelName: String): ForegroundInfo {
        val title = "Downloading $modelName"
        val content = "Download progress: $progress%"

        val notification = NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}

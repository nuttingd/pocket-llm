package dev.nutting.pocketllm.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloader {

    data class Progress(val bytesDownloaded: Long, val totalBytes: Long)

    private val cancelled = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun download(url: String, destinationFile: File): Flow<Progress> = flow {
        cancelled.set(false)

        val requestBuilder = Request.Builder().url(url)

        // Support resume via Range header
        var existingBytes = 0L
        if (destinationFile.exists()) {
            existingBytes = destinationFile.length()
            requestBuilder.addHeader("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw Exception("Download failed with status ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty response body")
        val contentLength = body.contentLength()
        val totalBytes = if (response.code == 206) {
            existingBytes + contentLength
        } else {
            contentLength
        }

        val raf = RandomAccessFile(destinationFile, "rw")
        if (response.code == 206) {
            raf.seek(existingBytes)
        } else {
            existingBytes = 0
        }

        val buffer = ByteArray(128 * 1024) // 128 KB buffer
        var downloaded = existingBytes
        var lastEmitTime = System.currentTimeMillis()

        body.byteStream().use { input ->
            while (true) {
                if (cancelled.get()) {
                    raf.close()
                    throw Exception("Download cancelled")
                }

                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                raf.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastEmitTime >= 250) {
                    emit(Progress(downloaded, totalBytes))
                    lastEmitTime = now
                }
            }
        }
        raf.close()
        response.close()
        emit(Progress(downloaded, totalBytes))
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        cancelled.set(true)
    }
}

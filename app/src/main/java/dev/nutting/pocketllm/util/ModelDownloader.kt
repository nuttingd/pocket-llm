package dev.nutting.pocketllm.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloader {

    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    )

    companion object {
        private const val TAG = "ModelDownloader"
        private const val BUFFER_SIZE = 1_048_576 // 1 MB
        private const val EMIT_INTERVAL_MS = 250L
    }

    private val cancelled = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .socketFactory(object : javax.net.SocketFactory() {
            private val default = getDefault()
            override fun createSocket() = (default.createSocket() as java.net.Socket).apply {
                receiveBufferSize = 1_048_576 // 1 MB receive buffer
            }
            override fun createSocket(host: String, port: Int) = (default.createSocket(host, port) as java.net.Socket).apply {
                receiveBufferSize = 1_048_576
            }
            override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) = (default.createSocket(host, port, localHost, localPort) as java.net.Socket).apply {
                receiveBufferSize = 1_048_576
            }
            override fun createSocket(host: java.net.InetAddress, port: Int) = (default.createSocket(host, port) as java.net.Socket).apply {
                receiveBufferSize = 1_048_576
            }
            override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int) = (default.createSocket(host, port, localHost, localPort) as java.net.Socket).apply {
                receiveBufferSize = 1_048_576
            }
        })
        .build()

    fun download(url: String, destinationFile: File): Flow<Progress> = flow {
        cancelled.set(false)

        val existingBytes = if (destinationFile.exists()) destinationFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.addHeader("Range", "bytes=$existingBytes-")
            Log.i(TAG, "Resuming download from byte $existingBytes")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw RuntimeException("Download failed: HTTP ${response.code}")
        }

        val isResuming = response.code == 206
        val contentLength = response.body?.contentLength() ?: -1L
        val totalBytes = if (isResuming) existingBytes + contentLength else contentLength

        val body = response.body ?: throw RuntimeException("Empty response body")

        val outputStream = FileOutputStream(destinationFile, isResuming)
        var bytesWritten = if (isResuming) existingBytes else 0L
        var lastEmitMs = 0L

        body.byteStream().use { inputStream ->
            outputStream.use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelled.get()) {
                        Log.i(TAG, "Download cancelled")
                        response.close()
                        return@flow
                    }

                    output.write(buffer, 0, bytesRead)
                    bytesWritten += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= EMIT_INTERVAL_MS) {
                        lastEmitMs = now
                        emit(Progress(bytesWritten, totalBytes))
                    }
                }

                // Final emit to ensure 100% progress is reported
                emit(Progress(bytesWritten, totalBytes))
            }
        }

        response.close()
        Log.i(TAG, "Download complete: ${destinationFile.name} ($bytesWritten bytes)")
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        cancelled.set(true)
    }
}

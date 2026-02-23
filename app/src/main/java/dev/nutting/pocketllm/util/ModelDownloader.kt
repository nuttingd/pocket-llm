package dev.nutting.pocketllm.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloader {

    data class Progress(val bytesDownloaded: Long, val totalBytes: Long)

    companion object {
        private const val TAG = "ModelDownloader"
        private const val BUFFER_SIZE = 1_048_576 // 1 MB
        private const val MAX_RETRIES = 10
        private const val RETRY_DELAY_MS = 2_000L
    }

    private val cancelled = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .socketFactory(object : javax.net.SocketFactory() {
            private val default = getDefault()
            override fun createSocket() = (default.createSocket() as java.net.Socket).apply {
                receiveBufferSize = BUFFER_SIZE
            }
            override fun createSocket(host: String, port: Int) = (default.createSocket(host, port) as java.net.Socket).apply {
                receiveBufferSize = BUFFER_SIZE
            }
            override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) = (default.createSocket(host, port, localHost, localPort) as java.net.Socket).apply {
                receiveBufferSize = BUFFER_SIZE
            }
            override fun createSocket(host: java.net.InetAddress, port: Int) = (default.createSocket(host, port) as java.net.Socket).apply {
                receiveBufferSize = BUFFER_SIZE
            }
            override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int) = (default.createSocket(host, port, localHost, localPort) as java.net.Socket).apply {
                receiveBufferSize = BUFFER_SIZE
            }
        })
        .build()

    fun download(url: String, destinationFile: File): Flow<Progress> = flow {
        cancelled.set(false)

        var totalBytes = -1L
        var retries = 0

        while (true) {
            if (cancelled.get()) throw Exception("Download cancelled")

            val existingBytes = if (destinationFile.exists()) destinationFile.length() else 0L

            // If we already know the total and have it all, we're done
            if (totalBytes > 0 && existingBytes >= totalBytes) {
                emit(Progress(existingBytes, totalBytes))
                break
            }

            val requestBuilder = Request.Builder().url(url)
            if (existingBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingBytes-")
            }

            try {
                val response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful && response.code != 206) {
                    response.close()
                    throw Exception("Download failed with status ${response.code}")
                }

                val body = response.body ?: throw Exception("Empty response body")
                val contentLength = body.contentLength()

                if (totalBytes < 0) {
                    totalBytes = if (response.code == 206) {
                        existingBytes + contentLength
                    } else {
                        contentLength
                    }
                }

                val raf = RandomAccessFile(destinationFile, "rw")
                val startOffset = if (response.code == 206) existingBytes else 0L
                raf.seek(startOffset)
                var downloaded = startOffset

                val buffer = ByteArray(BUFFER_SIZE)
                var lastEmitTime = System.currentTimeMillis()

                try {
                    body.byteStream().use { input ->
                        while (true) {
                            if (cancelled.get()) {
                                raf.close()
                                response.close()
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

                    // Completed successfully
                    emit(Progress(downloaded, totalBytes))
                    break
                } catch (e: IOException) {
                    raf.close()
                    response.close()
                    // Connection dropped — retry with resume
                    retries++
                    Log.w(TAG, "Connection lost at $downloaded/$totalBytes bytes (attempt $retries/$MAX_RETRIES)", e)
                    if (retries >= MAX_RETRIES) throw e
                    emit(Progress(downloaded, totalBytes))
                    delay(RETRY_DELAY_MS)
                }
            } catch (e: IOException) {
                // Connection failed before reading body
                retries++
                Log.w(TAG, "Connection failed (attempt $retries/$MAX_RETRIES)", e)
                if (retries >= MAX_RETRIES) throw e
                delay(RETRY_DELAY_MS)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        cancelled.set(true)
    }
}

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

    companion object {
        private const val BUFFER_SIZE = 1_048_576 // 1 MB
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

        val buffer = ByteArray(BUFFER_SIZE)
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

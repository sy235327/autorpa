package com.firerpa.update

import com.firerpa.service.DownloadProgress
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal object ReleaseHttp {
    fun getText(urls: List<String>, accept: String): String {
        var lastError: Throwable? = null
        val failures = mutableListOf<String>()

        for (url in urls) {
            try {
                return requestText(url, accept)
            } catch (error: Throwable) {
                lastError = error
                failures += "$url -> ${error.message ?: error::class.java.simpleName}"
            }
        }

        throw IllegalStateException(
            "All sources failed: ${failures.joinToString("; ")}",
            lastError,
        )
    }

    fun downloadToFile(
        urls: List<String>,
        outputFile: File,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        var lastError: Throwable? = null
        val failures = mutableListOf<String>()

        for (url in urls) {
            runCatching {
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                onProgress(DownloadProgress(0L, -1L))
                download(url, outputFile, onProgress)
            }.onSuccess {
                return
            }.onFailure { error ->
                lastError = error
                failures += "$url -> ${error.message ?: error::class.java.simpleName}"
            }
        }

        throw IllegalStateException(
            "All download sources failed: ${failures.joinToString("; ")}",
            lastError,
        )
    }

    private fun requestText(url: String, accept: String): String {
        val connection = openConnection(url).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
        }

        try {
            connection.connect()
            ensureSuccess(connection)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(
        url: String,
        outputFile: File,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val connection = openConnection(url)

        try {
            connection.connect()
            ensureSuccess(connection)

            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(DownloadProgress(downloaded, total))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
        }
    }

    private fun ensureSuccess(connection: HttpURLConnection) {
        val statusCode = connection.responseCode
        if (statusCode in 200..299) {
            return
        }

        val errorBody = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }?.trim()
        }.getOrNull().orEmpty()

        throw IllegalStateException(
            if (errorBody.isBlank()) "HTTP $statusCode" else "HTTP $statusCode: $errorBody",
        )
    }
}

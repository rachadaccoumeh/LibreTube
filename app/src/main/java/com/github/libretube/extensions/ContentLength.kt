package com.github.libretube.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

suspend fun URL.getContentLength(): Long? {
    try {
        return withContext(Dispatchers.IO) {
            val connection = openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=0-")

            val contentLength = connection.getHeaderField("content-length")
            val contentRange = connection.getHeaderField("content-range")
            android.util.Log.i("ContentLength", "getContentLength: code=${connection.responseCode}, content-length=$contentLength, content-range=$contentRange")

            val value = contentLength
                ?: contentRange?.split("/")?.getOrNull(1)

            connection.disconnect()
            if (value != null) value.toLong() else null
        }
    } catch (e: Exception) {
        android.util.Log.i("ContentLength", "getContentLength failed: ${e.message}")
    }

    return null
}

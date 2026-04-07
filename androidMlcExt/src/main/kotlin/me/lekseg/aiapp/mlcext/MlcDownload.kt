package me.lekseg.aiapp.mlcext

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal object MlcDownload {
    private const val RESOLVE_MAIN = "resolve/main/"
    private const val BUFFER_SIZE = 65536

    fun normalizeBaseUrl(url: String): String {
        val u = url.trim()
        val withScheme = if (u.startsWith("HF://")) {
            val rest = u.removePrefix("HF://").trim().trimEnd('/')
            "https://huggingface.co/$rest"
        } else {
            u.trimEnd('/')
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    fun downloadToFile(
        baseUrl: String,
        relativePath: String,
        dest: File,
        onProgress: ((bytesRead: Long, contentLength: Long) -> Unit)? = null,
    ) {
        dest.parentFile?.mkdirs()
        val fullUrl = "${normalizeBaseUrl(baseUrl)}$RESOLVE_MAIN$relativePath"
        val tmp = File(dest.parentFile, "${dest.name}.tmp-${System.currentTimeMillis()}")
        val conn = URL(fullUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 60_000
        conn.readTimeout = 0
        conn.instanceFollowRedirects = true
        conn.connect()
        val code = conn.responseCode
        require(code in 200..299) { "HTTP $code для $relativePath" }
        val contentLength = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { output ->
                val buf = ByteArray(BUFFER_SIZE)
                var readTotal = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    readTotal += n.toLong()
                    onProgress?.invoke(readTotal, contentLength)
                }
            }
        }
        conn.disconnect()
        require(tmp.exists() && tmp.length() > 0L) { "Пустой файл: $relativePath" }
        if (dest.exists()) dest.delete()
        require(tmp.renameTo(dest))
    }
}

internal fun formatDataSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f КБ".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f МБ".format(mb)
    return "%.2f ГБ".format(mb / 1024.0)
}

internal fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return "—"
    return "${formatDataSize(bytesPerSecond)}/с"
}

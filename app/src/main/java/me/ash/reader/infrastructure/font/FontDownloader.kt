package me.ash.reader.infrastructure.font

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class FontDownloader(private val context: Context) {
    val fontsDir: File = File(context.filesDir, "fonts")

    fun getFontFile(fontName: String): File? {
        val file = File(fontsDir, fontName)
        return if (file.exists()) file else null
    }

    suspend fun downloadFont(fontName: String, url: String): File {
        fontsDir.mkdirs()
        val file = File(fontsDir, fontName)
        if (file.exists()) return file
        withContext(Dispatchers.IO) {
            val tempFile = File(fontsDir, "$fontName.tmp")
            try {
                var connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connect()
                var redirectCount = 0
                while (connection.responseCode in 301..302 && redirectCount < 5) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    connection = URL(newUrl).openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connect()
                    redirectCount++
                }
                if (connection.responseCode != 200) {
                    throw java.io.IOException("HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                connection.disconnect()
                tempFile.renameTo(file)
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }
        return file
    }
}

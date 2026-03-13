package me.ash.reader.infrastructure.font

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
                URL(url).openStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.renameTo(file)
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }
        return file
    }
}

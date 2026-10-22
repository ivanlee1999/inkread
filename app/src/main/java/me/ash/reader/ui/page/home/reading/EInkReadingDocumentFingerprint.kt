package me.ash.reader.ui.page.home.reading

import java.security.MessageDigest

/** Metadata is part of the rendered text-node order. Formatting deliberately is not. */
internal object EInkReadingDocumentFingerprint {
    fun create(content: String, title: String, renderedMetadata: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("inkread-dom-v1", content, title, renderedMetadata).forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

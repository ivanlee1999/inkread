package me.ash.reader.infrastructure.annotation

import java.security.MessageDigest
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.infrastructure.library.normalizedSavedUrl

internal fun annotationUrl(url: String?): String? =
    url?.let { runCatching { normalizedSavedUrl(it) }.getOrNull() }

internal fun annotationIdentity(source: AnnotationSource): String {
    require(source.sourceId.isNotBlank()) { "Missing annotation source identity" }
    require(source.kind != AnnotationSourceKind.RSS || source.accountId != null) {
        "RSS annotation identity must include its account"
    }
    return annotationUrl(source.url)?.let { "url:$it" }
        ?: "source:${source.kind}:${source.accountId ?: "none"}:${source.sourceId.length}:${source.sourceId}"
}

internal fun annotationDocumentId(source: AnnotationSource): String =
    MessageDigest.getInstance("SHA-256").digest(annotationIdentity(source).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

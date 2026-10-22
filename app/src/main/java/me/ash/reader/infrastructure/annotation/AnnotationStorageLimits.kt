package me.ash.reader.infrastructure.annotation

import me.ash.reader.domain.annotation.AnnotationDraft
import me.ash.reader.domain.annotation.AnnotationExportPayload
import me.ash.reader.domain.annotation.AnnotationSizeException

/** Conservative per-row budget, well below CursorWindow limits. Rejection is explicit, never clipping. */
internal object AnnotationStorageLimits {
    const val MAX_ROW_BYTES = 256 * 1024
    private const val FIXED_ROW_BYTES = 4 * 1024

    fun validate(draft: AnnotationDraft) {
        val source = draft.source
        var aggregate = FIXED_ROW_BYTES
        listOf(draft.quote, draft.anchorJson, draft.note, source.kind.name, source.sourceId,
            source.url.orEmpty(), source.title, source.author.orEmpty(), source.sourceName.orEmpty()).forEach {
            // Avoid allocating an arbitrarily large byte array merely to reject it.
            if (it.length > MAX_ROW_BYTES) throw AnnotationSizeException()
            aggregate += it.toByteArray(Charsets.UTF_8).size
            if (aggregate > MAX_ROW_BYTES) throw AnnotationSizeException()
        }
        // JSON escaping can turn a single control character into six ASCII bytes. Bound the actual
        // persisted outbox representation too, even while disconnected, so later opt-in is safe.
        val payload = AnnotationExportPayload(
            annotationId = "00000000-0000-0000-0000-000000000000", revision = Long.MAX_VALUE,
            sourceUrl = annotationUrl(source.url), title = source.title, author = source.author,
            sourceName = source.sourceName, quote = draft.quote, note = draft.note,
            highlightedAt = Long.MAX_VALUE, location = Int.MAX_VALUE,
        )
        val serialized = AnnotationPayloadJson.encode(payload)
        if (serialized.length > MAX_ROW_BYTES || serialized.toByteArray(Charsets.UTF_8).size + FIXED_ROW_BYTES > MAX_ROW_BYTES) {
            throw AnnotationSizeException()
        }
    }
}

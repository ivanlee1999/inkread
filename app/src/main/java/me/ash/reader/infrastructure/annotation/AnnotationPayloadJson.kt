package me.ash.reader.infrastructure.annotation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.ash.reader.domain.annotation.AnnotationExportPayload

internal object AnnotationPayloadJson {
    fun validateAnchor(anchorJson: String) {
        require(runCatching { Json.parseToJsonElement(anchorJson) is JsonObject }.getOrDefault(false)) {
            "Annotation selection anchor must be a JSON object"
        }
    }

    /** A resize must be a fresh, coherent selection from the same captured article body. */
    fun validateReplacementAnchor(anchorJson: String, quote: String, previousAnchorJson: String) {
        require(location(anchorJson, quote) != null) { "Highlight resize requires a matching v1 selection anchor" }
        val anchor = Json.parseToJsonElement(anchorJson).jsonObject
        val previous = Json.parseToJsonElement(previousAnchorJson).jsonObject
        val previousFingerprint = (previous["documentFingerprint"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        // Older saved anchors may not have a body fingerprint. Never rewrite their source identity;
        // the reader still has to resolve their exact quote/context before allowing a resize.
        require(previousFingerprint.isNullOrBlank() || previousFingerprint == anchor["documentFingerprint"]?.jsonPrimitive?.content) {
            "Highlight resize must stay in the original article body"
        }
        require(listOf("prefix", "suffix").all { (anchor[it] as? JsonPrimitive)?.isString == true }) {
            "Highlight resize requires selection context"
        }
        fun integer(key: String): Int? = (anchor[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val startNode = integer("startNode")
        val endNode = integer("endNode")
        val startOffset = integer("startOffset")
        val endOffset = integer("endOffset")
        require(startNode != null && startNode >= 0 && endNode != null && endNode >= startNode &&
            startOffset != null && startOffset >= 0 && endOffset != null && endOffset >= 0 &&
            (startNode != endNode || endOffset.toLong() - startOffset == anchor.getValue("rangeQuote").jsonPrimitive.content.length.toLong())) {
            "Highlight resize requires coherent selection endpoints"
        }
    }

    /** Extract only a validated v1 raw-body UTF-16 start offset; never export context/anchor JSON. */
    fun location(anchorJson: String, quote: String): Int? = runCatching {
        val anchor = Json.parseToJsonElement(anchorJson).jsonObject
        fun integer(key: String): Int? = (anchor[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        fun string(key: String): String? = (anchor[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (integer("version") != 1 || integer("textMapVersion") != 1 ||
            string("documentFingerprint").isNullOrBlank() || string("quote") != quote) return@runCatching null
        val rawQuote = string("rangeQuote") ?: return@runCatching null
        val start = integer("start") ?: return@runCatching null
        val end = integer("end") ?: return@runCatching null
        if (start < 0 || end <= start || end.toLong() - start != rawQuote.length.toLong() || rawQuote.isBlank()) null else start
    }.getOrNull()

    fun encode(payload: AnnotationExportPayload): String = buildJsonObject {
        put("version", 1)
        put("annotationId", payload.annotationId)
        put("revision", payload.revision)
        put("sourceUrl", payload.sourceUrl?.let(::JsonPrimitive) ?: JsonNull)
        put("title", payload.title)
        put("author", payload.author?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceName", payload.sourceName?.let(::JsonPrimitive) ?: JsonNull)
        put("quote", payload.quote)
        put("note", payload.note)
        put("highlightedAt", payload.highlightedAt)
        payload.location?.let { put("location", it) }
    }.toString()

    fun decode(encoded: String): AnnotationExportPayload {
        val value = Json.parseToJsonElement(encoded).jsonObject
        require(value.getValue("version").jsonPrimitive.long == 1L) { "Unknown annotation payload version" }
        return AnnotationExportPayload(
            annotationId = value.getValue("annotationId").jsonPrimitive.content,
            revision = value.getValue("revision").jsonPrimitive.long,
            sourceUrl = value["sourceUrl"]?.jsonPrimitive?.contentOrNull,
            title = value.getValue("title").jsonPrimitive.content,
            author = value["author"]?.jsonPrimitive?.contentOrNull,
            sourceName = value["sourceName"]?.jsonPrimitive?.contentOrNull,
            quote = value.getValue("quote").jsonPrimitive.content,
            note = value.getValue("note").jsonPrimitive.content,
            highlightedAt = value.getValue("highlightedAt").jsonPrimitive.long,
            location = value["location"]?.jsonPrimitive?.intOrNull,
        )
    }
}

package me.ash.reader.infrastructure.rss

import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Display-only copy. Delivered HTML remains authoritative and is never rewritten in storage. */
internal object NewsletterBodyPresentation {
    private const val MAX_HTML_CHARS = 4 * 1024 * 1024
    private const val MAX_NODES = 20_000
    private const val MAX_DEPTH = 128
    private val hiddenStyle = Regex("(?i)(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\s*(?:!important\\s*)?(?:;|$)")

    fun withoutRemoteMedia(html: String, allowImages: Boolean = false): Result<String> {
        if (html.length > MAX_HTML_CHARS) return Result.failure(DisplayLimit())
        return try {
            val document = Jsoup.parseBodyFragment(html)
            var nodes = 0
            NodeTraversor.traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    if (++nodes > MAX_NODES || depth > MAX_DEPTH) throw DisplayLimit()
                }
                override fun tail(node: Node, depth: Int) = Unit
            }, document)
            // Do not reveal content that was hidden by a style when removing network-bearing CSS.
            document.select("[hidden], [aria-hidden=true]").remove()
            document.select("[style]").filter { hiddenStyle.containsMatchIn(it.attr("style")) }.forEach { it.remove() }
            document.select("script, style, link, meta, base, iframe, object, embed, video, audio, source, track, svg, math").remove()
            document.select("img").forEach { image ->
                if (allowImages) {
                    safeImageSource(image.attr("src"))?.let { image.attr("src", it) } ?: image.removeAttr("src")
                    val sourceSet = image.attr("srcset")
                    // Commas inside data URLs are not srcset separators. Keep data images in src
                    // only, rather than accidentally treating their payload as another URL.
                    val candidates = if (sourceSet.contains("data:", ignoreCase = true)) "" else sourceSet.split(',').mapNotNull { candidate ->
                        val parts = candidate.trim().split(Regex("\\s+"))
                        val source = parts.firstOrNull()?.let(::safeImageSource) ?: return@mapNotNull null
                        if (source.startsWith("data:", ignoreCase = true) || parts.size > 2 ||
                            (parts.size == 2 && !parts[1].matches(Regex("(?:[1-9][0-9]*w|(?:[0-9]+(?:\\.[0-9]+)?)x)")))) null
                        else parts.drop(1).joinToString(" ", prefix = source + if (parts.size > 1) " " else "")
                    }.joinToString(", ")
                    if (candidates.isEmpty()) image.removeAttr("srcset") else image.attr("srcset", candidates)
                } else {
                    val alt = image.attr("alt").trim()
                    if (alt.isEmpty()) image.remove() else image.replaceWith(TextNode("[$alt]"))
                }
            }
            for (element in document.allElements) {
                element.attributes().asList().forEach { attribute ->
                    val key = attribute.key.lowercase()
                    val allowedImageSource = allowImages && element.normalName() == "img" && key in setOf("src", "srcset")
                    if (!allowedImageSource && (key in setOf("style", "src", "srcset", "poster", "background", "srcdoc", "ping") || key.startsWith("on"))) {
                        element.removeAttr(attribute.key)
                    }
                }
            }
            document.outputSettings().prettyPrint(false)
            Result.success(document.body().html())
        } catch (limit: DisplayLimit) {
            Result.failure(limit)
        } catch (_: RuntimeException) {
            // The failure is UI state, never a replacement article that could be saved or annotated.
            Result.failure(IllegalStateException("Newsletter cannot be displayed safely"))
        } catch (_: StackOverflowError) {
            Result.failure(DisplayLimit())
        }
    }

    private fun safeImageSource(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) return null
        if (value.startsWith("data:", ignoreCase = true)) {
            return value.takeIf { it.matches(Regex("(?i)data:image/(?:png|jpe?g|gif|webp|avif|bmp);base64,[a-z0-9+/]+=*")) }
        }
        val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").find(value)?.value
        return value.takeIf { scheme == null || scheme.equals("https:", true) || scheme.equals("http:", true) }
    }

    private class DisplayLimit : IllegalArgumentException("Newsletter exceeds reader display limits")
}

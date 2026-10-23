package me.ash.reader.infrastructure.rss

import org.jsoup.Jsoup

/** Conservative, language-independent comparison. Never rewrites either source's HTML. */
internal object ArticleContentSelection {
    enum class Source { FEED, EXTRACTED, UNAVAILABLE }
    enum class Reason { EXTRACTED, FEED_ONLY, EXTRACTION_FAILED, FEED_IS_BETTER, EMPTY }
    data class Selection(val source: Source, val html: String?, val reason: Reason)

    fun select(feedHtml: String, extractedHtml: String?, failed: Boolean = false): Selection {
        val feed = measure(feedHtml)
        val extracted = measure(extractedHtml.orEmpty())
        val feedUsable = feed.text.isNotEmpty() || feed.images > 0
        val extractedUsable = extracted.text.isNotEmpty() || extracted.images > 0
        if (!extractedUsable) return if (feedUsable) {
            Selection(Source.FEED, feedHtml, if (failed) Reason.EXTRACTION_FAILED else Reason.FEED_ONLY)
        } else Selection(Source.UNAVAILABLE, null, Reason.EMPTY)
        if (!feedUsable) return Selection(Source.EXTRACTED, extractedHtml, Reason.EXTRACTED)

        // Similar text should retain server-proxied images, markup and existing annotation anchors.
        // Character counts, not whitespace-delimited words, work for both Chinese and English.
        val sameText = feed.text == extracted.text && feed.images >= extracted.images
        val losesText = extracted.text.length < feed.text.length * 0.85
        val losesImages = feed.images > extracted.images && extracted.text.length <= feed.text.length * 1.25
        // Only compare overlap for substantial feed bodies: short legitimate feeds and editorial
        // summaries are not reliably comparable to the original article and need no minimum length.
        val unrelated = feed.text.length >= 800 && feed.paragraphs >= 3 && overlap(feed.text, extracted.text) < 0.35
        if (sameText || losesText || losesImages || unrelated) {
            return Selection(Source.FEED, feedHtml, Reason.FEED_IS_BETTER)
        }
        return Selection(Source.EXTRACTED, extractedHtml, Reason.EXTRACTED)
    }

    private data class Measure(val text: String, val images: Int, val paragraphs: Int)
    private fun measure(html: String): Measure {
        val document = Jsoup.parseBodyFragment(html)
        document.select("script,style,noscript").remove()
        return Measure(document.text().filter { it.isLetterOrDigit() }.lowercase(),
            document.select("img[src],img[data-src],img[srcset],img[data-srcset]").size +
                document.select("picture").count { it.selectFirst("img") == null && it.selectFirst("source[srcset],source[data-srcset]") != null },
            document.select("p").count { it.text().length >= 40 })
    }

    private fun overlap(original: String, candidate: String): Double {
        // Bounded representative samples avoid a quadratic all-substring comparison.
        val size = 24
        val step = maxOf(size, original.length / 32)
        val samples = (0..(original.length - size) step step).map { original.substring(it, it + size) }
        return samples.count { candidate.contains(it) }.toDouble() / samples.size.coerceAtLeast(1)
    }
}

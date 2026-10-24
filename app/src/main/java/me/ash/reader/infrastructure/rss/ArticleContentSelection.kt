package me.ash.reader.infrastructure.rss

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Conservative, language-independent comparison. The selected HTML is never rewritten. */
internal object ArticleContentSelection {
    enum class Source { FEED, EXTRACTED, UNAVAILABLE }
    enum class Reason { EXTRACTED, FEED_ONLY, EXTRACTION_FAILED, FEED_IS_BETTER, EMPTY }
    data class Selection(val source: Source, val html: String?, val reason: Reason)

    // A comparison exceeding these budgets is inconclusive, not permission to replace saved content.
    private const val MAX_HTML_CHARS = 2 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 128 * 1024
    private const val MAX_ELEMENTS = 20_000
    private const val MAX_BLOCKS = 256
    private const val MAX_UI_ELEMENTS = 256
    private const val MAX_IMAGES = 256
    private const val SUBSTANTIAL_PARAGRAPH_CHARS = 80
    private val nonArticleUi = "script,style,noscript,template,nav,form,[hidden],[aria-hidden=true]," +
        "[role=navigation],[role=dialog],[role=menu],[role=toolbar]"
    private val nonEditorialImage = Regex("(^|[\\s_-])(avatar|icon|author-photo|author-image|profile-photo|profile-pic)([\\s_-]|$)")
    private val authorProfilePath = Regex("(?i)(^|/)authors?/")
    private val terminalContinuation = Regex(
        "readmore|continuereading|read(the)?(full)?(article|story|post)|" +
            "阅读全文|继续阅读|繼續閱讀|閱讀全文|阅读原文|閱讀原文|查看全文"
    )
    private val feedAttribution = Regex("(?i)^The post .+ appeared first on .+[.!]?$")
    private val continuationCue = Regex(
        "(?i)\\b(read\\s+(the\\s+)?(full\\s+)?(article|story|post|more)|continue\\s+reading|" +
            "subscribe\\s+to\\s+(read|continue|unlock)|sign\\s+in\\s+to\\s+(read|continue)|" +
            "subscription\\s+required|article\\s+(is\\s+)?truncated)\\b|" +
            "阅读全文|继续阅读|繼續閱讀|閱讀全文|阅读原文|閱讀原文|查看全文|订阅后阅读|訂閱後閱讀"
    )

    fun select(feedHtml: String, extractedHtml: String?, failed: Boolean = false): Selection {
        val feed = measure(feedHtml)
        // A failed or unmeasurable extraction must not replace existing bytes, even if it has text.
        val feedUsable = feed?.usable ?: feedHtml.isNotBlank()
        fun keepFeed(reason: Reason) = if (feedUsable) Selection(Source.FEED, feedHtml, reason)
            else Selection(Source.UNAVAILABLE, null, Reason.EMPTY)
        if (failed) return keepFeed(Reason.EXTRACTION_FAILED)
        val extracted = measure(extractedHtml.orEmpty()) ?: return keepFeed(Reason.FEED_IS_BETTER)
        if (!extracted.usable) return keepFeed(Reason.FEED_ONLY)
        if (feed == null) return keepFeed(Reason.FEED_IS_BETTER)
        if (!feedUsable) return Selection(Source.EXTRACTED, extractedHtml, Reason.EXTRACTED)

        // Duplicate avatars, responsive sources, hidden popovers and navigation are not article photos.
        if (extracted.images < feed.images) return keepFeed(Reason.FEED_IS_BETTER)
        val mostlyLinks = extracted.linkDensity > 0.65 && extracted.text.length > 80
        val addsDuplicates = extracted.duplicateChars > maxOf(160, extracted.paragraphChars / 3) &&
            extracted.duplicateChars > feed.duplicateChars
        if (mostlyLinks || addsDuplicates) return keepFeed(Reason.FEED_IS_BETTER)

        val comparable = feed.core.size >= 3 && feed.core.sumOf { it.text.length } >= 400 ||
            feed.core.any { indexOf(extracted.text, it.text, 0) >= 0 }
        if (comparable) {
            // Match complete paragraphs in order, including small paragraphs inside/after the body.
            // This also catches lost retailer labels, a missing ending, and reordered body text.
            if (!retainsInOrder(feed.core, extracted.text) || !extracted.links.containsAll(feed.coreLinks)) {
                return keepFeed(Reason.FEED_IS_BETTER)
            }
        } else if (extracted.text.length < feed.text.length) {
            // A differently worded summary is not comparable to the full story. Only here use size
            // as a fallback, rather than requiring a cleaner article to retain 85% of page chrome.
            return keepFeed(Reason.FEED_IS_BETTER)
        }

        val sameText = feed.text == extracted.text
        if (sameText && !extracted.links.containsAll(feed.links)) return keepFeed(Reason.FEED_IS_BETTER)
        val sameParagraphs = feed.core.isNotEmpty() && feed.core.map { it.text } == extracted.core.map { it.text }
        val removesUi = extracted.ignoredChars < feed.ignoredChars
        // Preserve proxy URLs, original markup and annotation anchors unless there is an improvement.
        if ((sameText || sameParagraphs) && extracted.images <= feed.images && !removesUi) {
            return keepFeed(Reason.FEED_IS_BETTER)
        }
        return Selection(Source.EXTRACTED, extractedHtml, Reason.EXTRACTED)
    }

    /** A conservative network-avoidance hint, not proof of completeness. Explicit fetch stays available. */
    fun isLikelyComplete(html: String): Boolean {
        val content = measure(html) ?: return false
        return content.meaningfulParagraphs >= 3 && content.paragraphChars >= 1_200 &&
            content.linkDensity < 0.35 && content.duplicateChars <= content.paragraphChars / 10 &&
            !content.hasContinuationCue
    }

    private data class Paragraph(
        val text: String,
        val links: Set<String>,
        val isCredit: Boolean,
        val isFeedFooter: Boolean,
    )
    private data class Measure(
        val text: String,
        val images: Int,
        val visibleImages: Int,
        val core: List<Paragraph>,
        val links: Set<String>,
        val ignoredChars: Int,
        val paragraphChars: Int,
        val duplicateChars: Int,
        val meaningfulParagraphs: Int,
        val linkDensity: Double,
        val hasContinuationCue: Boolean,
    ) {
        val usable get() = text.isNotEmpty() || visibleImages > 0
        val coreLinks get() = core.flatMap { it.links }.toSet()
    }

    private fun measure(html: String): Measure? {
        if (html.length > MAX_HTML_CHARS) return null
        return try {
            val document = Jsoup.parseBodyFragment(html)
            if (document.getAllElements().size > MAX_ELEMENTS) return null
            val originalChars = normalize(document.text()).length
            val ui = document.select(nonArticleUi)
            if (ui.size > MAX_UI_ELEMENTS) return null
            ui.remove() // Scoring copy only; never returned to the reader.
            val visibleText = document.text()
            val text = normalize(visibleText)
            if (text.length > MAX_TEXT_CHARS) return null
            val elements = document.select("p,pre,li,td,th,blockquote")
            if (elements.size > MAX_BLOCKS) return null
            // Keep leaf blocks, so <blockquote><p> and <li><p> do not double-count the same text.
            val enclosingBlocks = HashSet<Element>()
            for (element in elements) {
                var parent = element.parent()
                var depth = 0
                while (parent != null) {
                    if (++depth > 128) return null
                    enclosingBlocks.add(parent)
                    parent = parent.parent()
                }
            }
            val paragraphs = elements.filter { it !in enclosingBlocks }.map {
                val normalized = normalize(it.text())
                val links = linkKeys(it)
                val feedFooter = links.isNotEmpty() && it.text().length <= 400 &&
                    (terminalContinuation.matches(normalized) || feedAttribution.matches(it.text()))
                Paragraph(normalized, links, normalize(it.select("cite").text()) == normalized, feedFooter)
            }.filter { it.text.isNotEmpty() }
            // A linked feed continuation/attribution at the actual end is not an article body link.
            // Do not discard the same words in a paragraph, or even a matching block mid-article.
            var withoutFooters = paragraphs.size
            var comparisonText = text
            while (withoutFooters > 0) {
                val last = paragraphs[withoutFooters - 1]
                if (!last.isFeedFooter || !comparisonText.endsWith(last.text)) break
                comparisonText = comparisonText.dropLast(last.text.length)
                withoutFooters--
            }
            val bodyParagraphs = paragraphs.take(withoutFooters)
            val unique = bodyParagraphs.distinctBy { it.text }
            val firstSubstantial = bodyParagraphs.indexOfFirst { it.text.length >= SUBSTANTIAL_PARAGRAPH_CHARS }
            val occurrences = bodyParagraphs.groupingBy { it.text }.eachCount()
            // Short leading names, duplicated standfirsts and explicit credits are framing, not
            // evidence that the body was lost. Retain other introductions and every later block,
            // including short endings and intentional repeated passages.
            val core = if (firstSubstantial >= 0) {
                bodyParagraphs.take(firstSubstantial).filter {
                    it.text.length >= 24 && !it.isCredit && occurrences[it.text] == 1
                } + bodyParagraphs.drop(firstSubstantial)
            } else bodyParagraphs
            val paragraphChars = bodyParagraphs.sumOf { it.text.length }
            val images = document.select("img[src],img[data-src],img[srcset],img[data-srcset]")
                .filter { imageKey(it).isNotEmpty() }
            val pictures = document.select("picture")
            if (images.size > MAX_IMAGES || pictures.size > MAX_IMAGES) return null
            val photoKeys = images.filterNot(::isNonEditorialImage).map { imageKey(it) }.toMutableSet()
            val sourceOnlyPictures: List<Element> = pictures.filter { it.selectFirst("img") == null }
                .mapNotNull { it.selectFirst("source[srcset],source[data-srcset]") }
                .filter { imageKey(it).isNotEmpty() }
            photoKeys.addAll(sourceOnlyPictures.map { imageKey(it) })
            val anchorChars = document.select("a[href]").sumOf { normalize(it.text()).length }
            val cueLinks = document.select("a[href]").any { continuationCue.containsMatchIn(it.text()) }
            Measure(
                text, photoKeys.size, images.size + sourceOnlyPictures.size, core, linkKeys(document),
                (originalChars - comparisonText.length).coerceAtLeast(0), paragraphChars,
                paragraphChars - unique.sumOf { it.text.length },
                unique.count { it.text.length >= SUBSTANTIAL_PARAGRAPH_CHARS },
                anchorChars.toDouble() / text.length.coerceAtLeast(1),
                cueLinks || continuationCue.containsMatchIn(visibleText) ||
                    visibleText.trimEnd().let { it.endsWith("…") || it.endsWith("...") },
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun normalize(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()

    private fun linkKeys(element: Element): Set<String> = element.select("a[href]").mapNotNull {
        val label = normalize(it.text())
        val href = it.attr("href").trim()
        if (label.isEmpty() || href.isEmpty()) null else "$label\n$href"
    }.toSet()

    private fun imageKey(image: Element): String = sequenceOf("data-src", "src", "data-srcset", "srcset")
        .map { image.attr(it).trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun isNonEditorialImage(image: Element): Boolean {
        // Explicit figures are content, even when their image happens to be small.
        var ancestor: Element? = image
        repeat(8) {
            if (ancestor?.normalName() == "figure") return false
            ancestor = ancestor?.parent()
        }
        val width = image.attr("width").toIntOrNull()
        val height = image.attr("height").toIntOrNull()
        if (width == 1 && height == 1) return true
        val small = width != null && height != null && width in 1..64 && height in 1..64
        if (image.attr("role") == "presentation") return true
        ancestor = image
        repeat(3) {
            val element = ancestor ?: return false
            if (nonEditorialImage.containsMatchIn("${element.id()} ${element.className()}".lowercase()) ||
                element.attr("rel").split(' ').contains("author") ||
                small && element.normalName() == "a" &&
                authorProfilePath.containsMatchIn(element.attr("href"))) return true
            ancestor = element.parent()
        }
        return false
    }

    private fun retainsInOrder(paragraphs: List<Paragraph>, candidate: String): Boolean {
        var offset = 0
        for (paragraph in paragraphs) {
            val found = indexOf(candidate, paragraph.text, offset)
            if (found < 0) return false
            offset = found + paragraph.text.length
        }
        return true
    }

    /** KMP keeps matching linear even for adversarial repeated text. Paragraphs and input are bounded. */
    private fun indexOf(text: String, needle: String, start: Int): Int {
        if (needle.isEmpty()) return start
        val prefix = IntArray(needle.length)
        var matched = 0
        for (i in 1 until needle.length) {
            while (matched > 0 && needle[i] != needle[matched]) matched = prefix[matched - 1]
            if (needle[i] == needle[matched]) matched++
            prefix[i] = matched
        }
        matched = 0
        for (i in start until text.length) {
            while (matched > 0 && text[i] != needle[matched]) matched = prefix[matched - 1]
            if (text[i] == needle[matched]) matched++
            if (matched == needle.length) return i - needle.length + 1
        }
        return -1
    }
}

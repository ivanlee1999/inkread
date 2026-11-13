package me.ash.reader.infrastructure.html

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Checks the source's access signals before any article extraction or publisher cleanup.
 * Jsoup parses inert markup only: this never runs page scripts, loads resources, or unlocks content.
 * Ready means no supported restriction was found, not proof of entitlement. Its HTML still needs
 * the ordinary reader sanitizer before display.
 */
object ArticleExtractionPreparation {
    enum class AccessReason { PUBLISHER_RESTRICTED, ACCESS_GATE, BROWSER_CHALLENGE, SOURCE_LIMIT }

    internal const val MAX_INPUT_BYTES = 4 * 1024 * 1024
    internal const val MAX_DOM_NODES = 20_000
    internal const val MAX_DOM_DEPTH = 128
    internal const val MAX_METADATA_BYTES = 512 * 1024
    internal const val MAX_METADATA_TOKENS = 20_000
    internal const val MAX_METADATA_DEPTH = 64

    sealed class Result {
        data class Ready(val html: String, val articleHtml: String? = null) : Result()
        // Deliberately carries no source HTML, especially not a hidden subscription-only body.
        data class RequiresBrowser(val reason: AccessReason) : Result()
    }

    fun prepare(htmlContent: String, uri: String?): Result {
        if (exceedsUtf8Limit(htmlContent, MAX_INPUT_BYTES)) {
            return Result.RequiresBrowser(AccessReason.SOURCE_LIMIT)
        }
        return try {
            val document = Jsoup.parse(htmlContent, uri.orEmpty())
            checkDocumentBounds(document)
            val restricted = hasRestrictedMetadata(document)
            val publicNewYorker = !restricted && hasPublicNewYorkerArticle(document, uri)
            when {
                restricted -> Result.RequiresBrowser(AccessReason.PUBLISHER_RESTRICTED)
                hasBrowserChallenge(document, publicNewYorker) -> Result.RequiresBrowser(AccessReason.BROWSER_CHALLENGE)
                hasAccessGate(document, publicNewYorker) -> Result.RequiresBrowser(AccessReason.ACCESS_GATE)
                else -> {
                    if (isVerge(uri)) removePublicVergeInterface(document)
                    val articleBody = when {
                        isVerge(uri) -> publicVergeArticleBody(document)
                        publicNewYorker -> publicNewYorkerArticleBody(document)
                        else -> null
                    }
                    Result.Ready(document.outerHtml(), articleBody)
                }
            }
        } catch (_: PreparationLimitException) {
            Result.RequiresBrowser(AccessReason.SOURCE_LIMIT)
        } catch (_: StackOverflowError) {
            // Neither a DOM/parser stack limit nor malformed deep metadata proves public access.
            Result.RequiresBrowser(AccessReason.SOURCE_LIMIT)
        }
    }

    fun prepareOrThrow(htmlContent: String, uri: String?): String = prepareArticleOrThrow(htmlContent, uri).html

    fun prepareArticleOrThrow(htmlContent: String, uri: String?): Result.Ready =
        when (val result = prepare(htmlContent, uri)) {
            is Result.Ready -> result
            is Result.RequiresBrowser -> throw ArticleRequiresBrowserException(result.reason)
        }

    private fun hasRestrictedMetadata(document: Document): Boolean {
        if (document.select("[itemprop=isAccessibleForFree]").any {
                it.attr("content").trim().equals("false", ignoreCase = true)
            }) return true
        val budget = MetadataBudget()
        return document.select("script[type]").any { script ->
            if (!script.attr("type").substringBefore(';').trim()
                    .equals("application/ld+json", ignoreCase = true)) return@any false
            val json = script.data()
            if (exceedsUtf8Limit(json, MAX_METADATA_BYTES)) throw PreparationLimitException()
            try {
                containsRestrictedProperty(json, budget)
            } catch (error: PreparationLimitException) {
                throw error
            } catch (_: IOException) {
                // Malformed metadata is not a reason to ignore an explicit restriction.
                restrictedMetadataFallback.containsMatchIn(json)
            } catch (_: IllegalStateException) {
                restrictedMetadataFallback.containsMatchIn(json)
            }
        }
    }

    /** Streaming traversal never constructs a recursive JSON tree or skips nested access flags. */
    private fun containsRestrictedProperty(json: String, budget: MetadataBudget): Boolean {
        JsonReader(StringReader(json)).use { reader ->
            var depth = 0
            var accessProperty = false
            while (true) {
                if (++budget.tokens > MAX_METADATA_TOKENS) throw PreparationLimitException()
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> {
                        if (++depth > MAX_METADATA_DEPTH) throw PreparationLimitException()
                        reader.beginArray()
                        accessProperty = false
                    }
                    JsonToken.BEGIN_OBJECT -> {
                        if (++depth > MAX_METADATA_DEPTH) throw PreparationLimitException()
                        reader.beginObject()
                        accessProperty = false
                    }
                    JsonToken.END_ARRAY -> { reader.endArray(); depth--; accessProperty = false }
                    JsonToken.END_OBJECT -> { reader.endObject(); depth--; accessProperty = false }
                    JsonToken.NAME -> accessProperty = reader.nextName() == "isAccessibleForFree"
                    JsonToken.STRING -> {
                        val value = reader.nextString()
                        if (accessProperty && value.trim().equals("false", ignoreCase = true)) return true
                        accessProperty = false
                    }
                    JsonToken.BOOLEAN -> {
                        val value = reader.nextBoolean()
                        if (accessProperty && !value) return true
                        accessProperty = false
                    }
                    JsonToken.NUMBER -> { reader.nextString(); accessProperty = false }
                    JsonToken.NULL -> { reader.nextNull(); accessProperty = false }
                    JsonToken.END_DOCUMENT -> return false
                    else -> throw IllegalStateException("Invalid article metadata")
                }
            }
        }
    }

    private fun checkDocumentBounds(document: Document) {
        var nodes = 0
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (++nodes > MAX_DOM_NODES || depth > MAX_DOM_DEPTH) throw PreparationLimitException()
            }
            override fun tail(node: Node, depth: Int) = Unit
        }, document)
    }

    /** Counts UTF-8 without allocating another potentially oversized copy of untrusted input. */
    private fun exceedsUtf8Limit(text: String, limit: Int): Boolean {
        if (text.length > limit) return true
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            bytes += when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                Character.isHighSurrogate(char) && index < text.length && Character.isLowSurrogate(text[index]) -> {
                    index++
                    4
                }
                else -> 3
            }
            if (bytes > limit) return true
        }
        return false
    }

    private class MetadataBudget(var tokens: Int = 0)
    private class PreparationLimitException : RuntimeException()

    private fun hasAccessGate(document: Document, publicNewYorker: Boolean = false): Boolean {
        // Structural names are intentionally narrow. "subscribe" in a navigation link, article,
        // newsletter, or CSS class is not an access restriction.
        if (document.select("[id], [class], [data-testid], [data-paywall]").any { element ->
                !isExplicitlyHidden(element) && element.text().isNotBlank() &&
                    (gateTokens.any { token ->
                        element.id().equals(token, ignoreCase = true) ||
                            element.classNames().any {
                                it.equals(token, ignoreCase = true) &&
                                    !(token == "paywall" && publicNewYorker && isNewYorkerEditorialMarker(element))
                            } ||
                            element.attr("data-testid").equals(token, ignoreCase = true)
                    } || element.attr("data-paywall").trim().equals("true", ignoreCase = true))
            }) return true
        return document.select("h1, h2, h3, h4, h5, h6, p, ul, ol, li, blockquote, figure, figcaption, div, section, aside, form").any { element ->
            !isExplicitlyHidden(element) &&
                (element.text().length <= 350 || publicNewYorker && isNewYorkerEditorialMarker(element)) &&
                accessNotice.containsMatchIn(element.text())
        }
    }

    private fun hasBrowserChallenge(document: Document, publicNewYorker: Boolean = false): Boolean {
        if (document.select("#challenge-form, #challenge-running, #cf-challenge-running, .cf-challenge, #px-captcha").any {
                !isExplicitlyHidden(it)
            }) return true
        return document.select("h1, h2, h3, h4, h5, h6, p, ul, ol, li, blockquote, figure, figcaption, div, noscript").any { element ->
            !isExplicitlyHidden(element) &&
                (element.text().length <= 250 || publicNewYorker && isNewYorkerEditorialMarker(element)) &&
                browserChallenge.containsMatchIn(element.text())
        }
    }

    private fun isExplicitlyHidden(element: Element): Boolean =
        generateSequence(element) { it.parent() }.any {
            it.hasAttr("hidden") || it.attr("aria-hidden").equals("true", ignoreCase = true) ||
                hiddenStyle.containsMatchIn(it.attr("style"))
        }

    private fun isVerge(uri: String?): Boolean {
        val host = runCatching { URI(uri.orEmpty()).host?.lowercase() }.getOrNull()
        return host == "theverge.com" || host == "www.theverge.com"
    }

    /** Publisher-declared public main article only; related/schema examples cannot grant access. */
    private fun hasPublicNewYorkerArticle(document: Document, uri: String?): Boolean {
        val source = runCatching { URI(uri.orEmpty()) }.getOrNull() ?: return false
        if (source.host?.lowercase() !in setOf("newyorker.com", "www.newyorker.com")) return false
        // The streaming access scan above already enforces shared JSON byte/token/depth budgets.
        // Only the observed top-level NewsArticle, its exact URL, and a boolean true are accepted.
        return document.select("script[type]").count { script ->
            if (!script.attr("type").substringBefore(';').trim().equals("application/ld+json", ignoreCase = true)) return@count false
            runCatching {
                val value = JsonParser.parseString(script.data())
                if (!value.isJsonObject) return@runCatching false
                val article = value.asJsonObject
                val type = article.get("@type")
                val accessible = article.get("isAccessibleForFree")
                val url = article.get("url")
                type?.isJsonPrimitive == true && type.asJsonPrimitive.isString && type.asString == "NewsArticle" &&
                    accessible?.isJsonPrimitive == true && accessible.asJsonPrimitive.isBoolean && accessible.asBoolean &&
                    url?.isJsonPrimitive == true && url.asJsonPrimitive.isString && URI(url.asString) == source
            }.getOrDefault(false)
        } == 1
    }

    private val newYorkerEditorialTags = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "figure", "figcaption", "hr")

    /** New Yorker's public prose uses a metering class named paywall; a barrier does not. */
    private fun isNewYorkerEditorialMarker(element: Element): Boolean =
        element.normalName() in newYorkerEditorialTags && element.classNames().any { it.equals("paywall", ignoreCase = true) } &&
            element.parents().any { it.hasClass("body__inner-container") &&
                it.parents().any { parent -> parent.attr("data-testid") == "BodyWrapper" } }

    /** Keep visible prose, citations, and the feed's header summary before generic link pruning. */
    private fun publicNewYorkerArticleBody(document: Document): String? {
        val bodies = document.select("[data-testid=BodyWrapper] .body__inner-container").filter { body ->
            !isExplicitlyHidden(body) && body.parents().none { it.hasClass("body__inner-container") }
        }
        if (bodies.none { body -> body.getAllElements().any {
                isNewYorkerEditorialMarker(it) && !isExplicitlyHidden(it) && it.text().isNotBlank()
            } }) return null
        val output = Element("article")
        val bodyText = bodies.joinToString(" ") { it.text() }
        document.select("[data-testid=ContentHeaderContainer] [class*=ContentHeaderDek-]")
            .firstOrNull { !isExplicitlyHidden(it) && it.text().isNotBlank() && !bodyText.contains(it.text()) }
            ?.let { output.appendChild(it.clone()) }
        document.select("[data-testid=ContentHeaderLeadAsset] figure").firstOrNull { !isExplicitlyHidden(it) }
            ?.let { output.appendChild(it.clone()) }
        bodies.forEach { output.appendChild(it.clone()) }
        output.getAllElements().filter { isExplicitlyHidden(it) }.forEach { it.remove() }
        output.select("script,style,noscript,template,nav,form,button").remove()
        return output.takeIf { it.text().isNotBlank() }?.outerHtml()
    }

    /** Only the observed, opt-in follow/newsletter widgets. Never removes access-control elements. */
    private fun removePublicVergeInterface(document: Document) {
        document.select("aside[id^=popover-]").filter { aside ->
            aside.select("p").any { it.text() in followNotices }
        }.forEach { it.remove() }

        document.select("div.tly2fw0").filter { footer ->
            footer.select("span.tly2fw2 > strong").any { it.text() == "Follow topics and authors" } &&
                footer.select("li[id^=follow-author-article_footer-], li[id^=follow-category-article_footer-]").isNotEmpty()
        }.forEach { it.remove() }

        // Remove only the signup form, not its article-body or block-placement ancestors.
        document.select("form").filter { form ->
            form.children().size == 1 && form.child(0).hasClass("duet--cta--newsletter") &&
                form.select("input[type=email]").isNotEmpty() &&
                form.select("button[type=submit]").any { it.text().equals("Sign Up", ignoreCase = true) }
        }.forEach { it.remove() }
    }

    /** Preserve publisher-labelled visible body blocks that Readability prunes as link-heavy UI.
     * Called only after the whole source passed the access checks above. Never reads hydration data.
     */
    private fun publicVergeArticleBody(document: Document): String? {
        val article = document.selectFirst("article") ?: return null
        val componentClass = "duet--article--article-body-component"
        val components = article.getElementsByClass(componentClass).filter { component ->
            !isExplicitlyHidden(component) &&
                generateSequence(component.parent()) { it.parent() }.none { it.hasClass(componentClass) }
        }
        if (components.isEmpty()) return null
        val output = Element("article")
        // The lead image/caption precede the body components. The first responsive variant is
        // sufficient; copying the entire lede would bring duplicated mobile/desktop summaries.
        article.selectFirst(".duet--article--lede")?.let { lead ->
            val image = lead.select("img").firstOrNull { !isExplicitlyHidden(it) }
            if (image != null) {
                val figure = output.appendElement("figure").appendChild(image.clone())
                lead.select("cite, figcaption").firstOrNull { !isExplicitlyHidden(it) }?.let {
                    figure.appendElement("figcaption").text(it.text())
                }
            }
        }
        var hasBody = false
        for (component in components) {
            val copy = component.clone()
            copy.getAllElements().filter { isExplicitlyHidden(it) }.forEach { it.remove() }
            copy.select("script,style,noscript,template,nav,form,button,.duet--article--related").remove()
            if (copy.text().isNotBlank() || copy.select("img, picture, video, audio").isNotEmpty()) {
                output.appendChild(copy)
                hasBody = true
            }
        }
        return output.takeIf { hasBody && it.text().isNotBlank() }?.outerHtml()
    }

    private val followNotices = setOf(
        "Posts from this topic will be added to your daily email digest and your homepage feed.",
        "Posts from this author will be added to your daily email digest and your homepage feed.",
    )
    private val gateTokens = setOf(
        "paywall", "paywall-container", "paywall-overlay", "paywall-modal", "paywall-gate",
        "subscription-wall", "subscribe-wall", "regwall", "registration-wall", "login-wall",
        "access-gate", "content-gate",
    )
    private val restrictedMetadataFallback = Regex(
        "\"isAccessibleForFree\"\\s*:\\s*(?:false\\b|\"\\s*false\\s*\")",
        RegexOption.IGNORE_CASE,
    )
    private val hiddenStyle = Regex(
        "(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\s*(?:!important\\s*)?(?:;|$)",
        RegexOption.IGNORE_CASE,
    )
    private val accessNotice = Regex(
        "(?:^|[.!?]\\s+)(?:subscribe|sign\\s*in|log\\s*in|register|become\\s+(?:a\\s+)?member)" +
            "\\s+(?:now\\s+)?to\\s+(?:(?:continue|keep)\\s+reading|read\\s+(?:this\\s+(?:article|story)|the\\s+(?:full\\s+(?:article|story)|rest))|unlock\\s+(?:this|the)\\s+(?:article|story))" +
            "|(?:this|the)\\s+(?:article|story|content)\\s+is\\s+(?:only\\s+)?(?:available\\s+to|for)\\s+(?:paid\\s+)?(?:subscribers|members)" +
            "|(?:you(?:'|’)ve|you\\s+have)\\s+reached\\s+(?:your|the)\\s+(?:free\\s+)?article\\s+limit" +
            "|(?:订阅|訂閱|登录|登入|登錄)后(?:继续阅读|閱讀|阅读|查看全文)|(?:訂閱|登入|登錄)後(?:繼續閱讀|閱讀|查看全文)" +
            "|仅限(?:订阅|会员)用户|僅限(?:訂閱|會員)用戶",
        RegexOption.IGNORE_CASE,
    )
    private val browserChallenge = Regex(
        "^(?:verify\\s+(?:that\\s+)?you\\s+are\\s+human|checking\\s+your\\s+browser|" +
            "(?:please\\s+)?enable\\s+javascript\\s+and\\s+cookies\\s+to\\s+continue|" +
            "请验证您是人类|請驗證您是人類)(?:[.!…\\s]|$)",
        RegexOption.IGNORE_CASE,
    )
}

class ArticleRequiresBrowserException(val reason: ArticleExtractionPreparation.AccessReason) :
    IllegalStateException("This page requires browser access. Original content is unchanged.")

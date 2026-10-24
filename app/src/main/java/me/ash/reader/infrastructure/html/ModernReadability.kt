package me.ash.reader.infrastructure.html

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Mozilla Readability 0.6.0 in an isolated, interpreter-only Rhino context.
 *
 * Publisher HTML is only a string passed to JSDOMParser. It is never evaluated and there is no
 * browser, network, Android/Java object bridge, or shared JS global. The return value is NOT
 * sanitized: callers must retain their existing render sanitizer and access-eligibility checks.
 * Run off the main thread and pass coroutineContext.ensureActive through [checkCancelled].
 *
 * Input size bounds the Jsoup parse; traversal and JS execution also have node/depth, instruction,
 * and cooperative elapsed-time limits. The deadline is checked during Reader reads and at JS
 * instruction checkpoints, not a process-level memory limit or a hard real-time guarantee.
 */
class ModernReadability(private val limits: Limits = Limits()) {
    data class Limits(
        val maxInputBytes: Int = 4 * 1024 * 1024,
        val maxNodes: Int = 20_000,
        val maxDepth: Int = 128,
        val maxMillis: Long = 8_000,
        val maxInstructions: Long = 500_000_000,
        val maxOutputChars: Int = 4 * 1024 * 1024,
    ) {
        init {
            require(maxInputBytes > 0 && maxNodes > 0 && maxDepth > 0)
            require(maxMillis > 0 && maxMillis <= 60_000 && maxInstructions > 0)
            require(maxOutputChars > 0)
        }
    }

    data class Article(
        val content: String,
        val title: String?,
        val byline: String?,
        val excerpt: String?,
        val siteName: String?,
        val lang: String?,
        val dir: String?,
        val publishedTime: String?,
        val textContent: String,
        val baseUri: String,
    )

    enum class Failure { INPUT_LIMIT, NODE_LIMIT, DEPTH_LIMIT, TIME_LIMIT, INSTRUCTION_LIMIT, OUTPUT_LIMIT, ENGINE }

    class ExtractionException(val reason: Failure, cause: Throwable? = null) :
        RuntimeException("Article extraction failed: $reason", cause)

    fun extract(html: String, finalUrl: String, checkCancelled: () -> Unit = {}): Article? {
        val budget = Budget(limits, checkCancelled)
        budget.check()
        // UTF-8 byte counting avoids allocating another potentially oversized byte array.
        if (html.length > limits.maxInputBytes || utf8Length(html, budget) > limits.maxInputBytes) {
            throw ExtractionException(Failure.INPUT_LIMIT)
        }
        try {
            val document = Parser.htmlParser().parseInput(CheckedReader(html, budget), finalUrl)
            checkTree(document, budget)
            val baseUri = document.baseUri().ifBlank { finalUrl }
            // JSON-LD is metadata, not code. Escape it as XML text so '<'/'&' cannot corrupt the DOM.
            document.select("script").forEach { script ->
                budget.check()
                if (script.attr("type").equals("application/ld+json", ignoreCase = true)) {
                    val json = script.data()
                    script.empty().appendChild(TextNode(json))
                } else {
                    script.remove()
                }
            }
            document.select("style").remove()
            resolveUrls(document, baseUri, finalUrl, budget)
            document.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .charset(StandardCharsets.UTF_8)
                .prettyPrint(false)
            val normalized = document.outerHtml()
            if (normalized.length > limits.maxOutputChars) throw ExtractionException(Failure.OUTPUT_LIMIT)
            budget.check()

            val result = BoundedContextFactory(budget).call { cx ->
                val scope = cx.initSafeStandardObjects()
                // Only primitive strings/numbers enter JS. Do not use javaToJS or initStandardObjects.
                ScriptableObject.putProperty(scope, "__inkreadHtml", normalized)
                ScriptableObject.putProperty(scope, "__inkreadUrl", finalUrl)
                ScriptableObject.putProperty(scope, "__inkreadBase", baseUri)
                ScriptableObject.putProperty(scope, "__inkreadMaxNodes", limits.maxNodes)
                cx.evaluateString(scope, domSource, "JSDOMParser-0.6.0.js", 1, null)
                cx.evaluateString(scope, readabilitySource, "Readability-0.6.0.js", 1, null)
                val parsed = cx.evaluateString(scope, ADAPTER, "InkReadReadabilityAdapter.js", 1, null)
                if (parsed !is Scriptable) null else {
                    fun field(name: String): String? {
                        val value = ScriptableObject.getProperty(parsed, name)
                        return if (value == null || value === Scriptable.NOT_FOUND || value is Undefined) null
                        else Context.toString(value)
                    }
                    val content = field("content") ?: return@call null
                    if (content.length > limits.maxOutputChars) throw ExtractionException(Failure.OUTPUT_LIMIT)
                    Article(content, field("title"), field("byline"), field("excerpt"), field("siteName"),
                        field("lang"), field("dir"), field("publishedTime"), field("textContent") ?: "", baseUri)
                }
            } ?: return null
            budget.check()
            // Mozilla may promote data-src/data-srcset during extraction. Resolve again after promotion.
            val output = Jsoup.parseBodyFragment(result.content, baseUri)
            checkTree(output, budget)
            resolveUrls(output, baseUri, finalUrl, budget)
            output.outputSettings().prettyPrint(false)
            val content = output.body().html()
            if (content.length > limits.maxOutputChars) throw ExtractionException(Failure.OUTPUT_LIMIT)
            budget.check()
            return result.copy(content = content)
        } catch (e: Abort) {
            throw e.failure
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtractionException) {
            throw e
        } catch (e: StackOverflowError) {
            throw ExtractionException(Failure.DEPTH_LIMIT, e)
        } catch (e: LinkageError) {
            // Unsupported runtime APIs/resources must preserve the caller's legacy/feed fallback.
            throw ExtractionException(Failure.ENGINE, e)
        } catch (e: Exception) {
            throw ExtractionException(Failure.ENGINE, e)
        }
    }

    private fun checkTree(document: Document, budget: Budget) {
        var nodes = 0
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                budget.check()
                if (++nodes > limits.maxNodes) throw ExtractionException(Failure.NODE_LIMIT)
                if (depth > limits.maxDepth) throw ExtractionException(Failure.DEPTH_LIMIT)
            }
            override fun tail(node: Node, depth: Int) = Unit
        }, document)
    }

    private fun resolveUrls(document: Document, baseUri: String, finalUrl: String, budget: Budget) {
        document.setBaseUri(baseUri)
        for (element in document.allElements) {
            budget.check()
            for (attribute in listOf("href", "src", "poster")) {
                if (!element.hasAttr(attribute)) continue
                val value = element.attr(attribute)
                if (value.startsWith("#") && baseUri == finalUrl) continue
                val absolute = element.absUrl(attribute)
                if (absolute.isNotBlank()) element.attr(attribute, absolute)
            }
            if (element.hasAttr("srcset")) {
                element.attr("srcset", SRCSET.replace(element.attr("srcset")) { match ->
                    val url = org.jsoup.internal.StringUtil.resolve(baseUri, match.groupValues[1])
                    (url.ifBlank { match.groupValues[1] }) + match.groupValues[2] + match.groupValues[3]
                })
            }
        }
    }

    private fun utf8Length(value: String, budget: Budget): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            if (index % 4096 == 0) budget.check()
            val char = value[index++]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) && index < value.length && Character.isLowSurrogate(value[index]) -> { index++; 4 }
                Character.isSurrogate(char) -> 1 // UTF-8 encoder replacement byte
                else -> 3
            }
            if (bytes > limits.maxInputBytes) return bytes
        }
        return bytes
    }

    private class CheckedReader(html: String, private val budget: Budget) : StringReader(html) {
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            budget.check()
            return super.read(buffer, offset, minOf(length, 4096))
        }
        override fun read(): Int { budget.check(); return super.read() }
    }

    private class Budget(private val limits: Limits, private val checkCancelled: () -> Unit) {
        private val started = System.nanoTime()
        private var instructions = 0L
        fun check() {
            checkCancelled()
            if (Thread.currentThread().isInterrupted) throw CancellationException("Article extraction interrupted")
            if (System.nanoTime() - started >= limits.maxMillis * 1_000_000L) throw ExtractionException(Failure.TIME_LIMIT)
        }
        fun instructions(count: Int) {
            try {
                check()
                instructions += count
                if (instructions > limits.maxInstructions) throw ExtractionException(Failure.INSTRUCTION_LIMIT)
            } catch (failure: RuntimeException) {
                // Error escapes JS catch clauses, unlike ordinary script exceptions.
                throw Abort(failure)
            }
        }
    }

    private class Abort(val failure: RuntimeException) : Error()

    private class BoundedContextFactory(private val budget: Budget) : ContextFactory() {
        @Suppress("DEPRECATION")
        override fun makeContext(): Context = super.makeContext().apply {
            languageVersion = Context.VERSION_ES6
            optimizationLevel = -1 // No generated bytecode/classes on Android; observer always runs.
            maximumInterpreterStackDepth = 1_000
            instructionObserverThreshold = 10_000
            setClassShutter { false }
        }
        override fun observeInstructionCount(cx: Context, instructionCount: Int) = budget.instructions(instructionCount)
    }

    companion object {
        const val ENGINE_VERSION = "mozilla-readability-0.6.0-rhino-1"
        // Same srcset tokenization as pinned Readability 0.6.0, including commas in image URLs.
        private val SRCSET = Regex("(\\S+)(\\s+[\\d.]+[xw])?(\\s*(?:,|$))")
        private val domSource: String by lazy { resource("JSDOMParser.js") }
        private val readabilitySource: String by lazy { resource("Readability.js") }
        private fun resource(name: String): String = checkNotNull(
            ModernReadability::class.java.getResourceAsStream("/readability/$name")
        ) { "Missing bundled Readability resource: $name" }.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        private val ADAPTER = """
            // Rhino has no browser URL class. Jsoup resolves document URLs before and after parsing.
            // This absolute-only helper supplies the one remaining use: rejecting URL-valued bylines.
            // Relative links are returned untouched by Readability and resolved in Kotlin afterward.
            function URL(value) {
                value = String(value);
                if (!/^[a-z][a-z0-9+.-]*:/i.test(value)) throw new TypeError('Relative URL');
                this.href = value;
            }
            var document = new JSDOMParser().parse(__inkreadHtml, __inkreadUrl);
            document._baseURI = __inkreadBase;
            new Readability(document, {maxElemsToParse: __inkreadMaxNodes}).parse();
        """.trimIndent()
    }
}

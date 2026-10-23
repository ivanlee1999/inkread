package me.ash.reader.infrastructure.html

import net.dankito.readability4j.extended.processor.PostprocessorExtended
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** URL-resolution correction for Readability4J 1.0.8; all other postprocessing is inherited. */
internal class ArticleUrlPostprocessor : PostprocessorExtended() {
    override fun fixRelativeUris(originalDocument: Document, element: Element, articleUri: String) {
        val finalUrl = articleUri.toHttpUrlOrNull()
            ?: return super.fixRelativeUris(originalDocument, element, articleUri)
        val base = originalDocument.selectFirst("head base[href]")?.attr("href")
            ?.takeIf { it.isNotBlank() }?.let(finalUrl::resolve) ?: finalUrl
        // Upstream concatenates URI.host and loses non-default ports. Resolve against the
        // complete effective base instead, including redirect host, port and HTML base href.
        val prePath = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
        fixRelativeAnchorUris(element, base.scheme, prePath, base.toString())
        fixRelativeImageUris(element, base.scheme, prePath, base.toString())
    }

    override fun toAbsoluteURI(uri: String, scheme: String, prePath: String, pathBase: String): String {
        // Preserve upstream's absolute and fragment-only references unchanged. The inherited
        // anchor/image processing remains in charge; this only resolves relative locations.
        if (isAbsoluteUri(uri) || uri.startsWith('#')) return uri
        return pathBase.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri
    }
}

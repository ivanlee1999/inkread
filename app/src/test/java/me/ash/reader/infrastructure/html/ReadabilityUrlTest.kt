package me.ash.reader.infrastructure.html

import org.junit.Assert.*
import org.junit.Test

class ReadabilityUrlTest {
    @Test fun `actual extractor preserves bilingual body and final URL locations`() {
        val paragraph = "中文报道与 English reporting remain intact. The garden has many trees and winding paths, and local visitors describe their history. This synthetic article records seasonal changes, planting choices, and community projects."
        val html = "<html><head><title>Fixture headline</title></head><body><article><h1>Fixture headline</h1>" +
            (1..5).joinToString("") { "<p>$paragraph</p>" } +
            "<p><a href='../related.html'>More about the garden</a></p><img src='images/garden.jpg' alt='Garden'></article></body></html>"
        val result = Readability.parseToElement(html, "http://localhost:8765/news/2026/story.html")!!
        assertEquals("http://localhost:8765/news/2026/images/garden.jpg", result.selectFirst("img")!!.attr("src"))
        assertEquals("http://localhost:8765/news/related.html", result.selectFirst("a")!!.attr("href"))
        assertTrue(result.text().contains(paragraph))
    }
}

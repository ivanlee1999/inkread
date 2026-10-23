package me.ash.reader.infrastructure.html

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleUrlPostprocessorTest {
    private fun process(html: String, url: String = "http://localhost:8765/news/2026/story.html") =
        Jsoup.parse(html, url).let { document ->
            document.body().also { ArticleUrlPostprocessor().postProcessContent(document, it, url) }
        }

    @Test fun `relative image and parent link preserve final host and nondefault port`() {
        val body = process("<img src='images/photo.jpg'><a href='../related.html'>Related</a>")
        assertEquals("http://localhost:8765/news/2026/images/photo.jpg", body.selectFirst("img")!!.attr("src"))
        assertEquals("http://localhost:8765/news/related.html", body.selectFirst("a")!!.attr("href"))
    }
    @Test fun `HTML base changes both relative and root relative locations`() {
        val body = process("<head><base href='https://cdn.example:9443/assets/'></head><body><img src='photo.jpg'><a href='/related'>Related</a></body>")
        assertEquals("https://cdn.example:9443/assets/photo.jpg", body.selectFirst("img")!!.attr("src"))
        assertEquals("https://cdn.example:9443/related", body.selectFirst("a")!!.attr("href"))
    }
    @Test fun `query and fragment references resolve without losing document path`() {
        val body = process("<a href='?page=2'>Next</a><a href='#section'>Section</a>")
        assertEquals("http://localhost:8765/news/2026/story.html?page=2", body.select("a")[0].attr("href"))
        assertEquals("#section", body.select("a")[1].attr("href"))
    }
    @Test fun `absolute proxy URL remains byte for byte unchanged`() {
        val proxy = "https://rss.example/image?url=a%2Fb&size=1200,800"
        assertEquals(proxy, process("<img src='$proxy'>").selectFirst("img")!!.attr("src"))
    }
    @Test fun `relative HTML base resolves against final document URL`() {
        val body = process("<head><base href='../images/'></head><body><img data-src='photo.jpg'></body>")
        assertEquals("http://localhost:8765/news/images/photo.jpg", body.selectFirst("img")!!.attr("src"))
    }
}

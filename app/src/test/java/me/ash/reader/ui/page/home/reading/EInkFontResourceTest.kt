package me.ash.reader.ui.page.home.reading

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EInkFontResourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `only the selected exact HTTPS font URL opens a stream`() {
        val file = temporary.newFile("reader.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val font = EInkFontResource(file)
        assertEquals("font/ttf", font.mimeType)
        font.open(font.url)!!.use { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
        assertNull(font.open("file://${file.absolutePath}"))
        assertNull(font.open(font.url.replace("reader.ttf", "../reader.ttf")))
        assertNull(font.open(font.url.replace("inkread.local", "example.com")))
        assertNull(font.open(font.url + "&other=1"))
    }

    @Test fun `font URL is small even for large downloaded CJK fonts`() {
        val file = temporary.newFile("cjk.otf")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(20L * 1024 * 1024) }
        val font = EInkFontResource(file)
        assertEquals("font/otf", font.mimeType)
        assertTrue(font.url.length < 100)
        font.open(font.url)!!.use { assertEquals(0, it.read()) }
        file.delete()
        assertNull(font.open(font.url))
    }
}

package me.ash.reader.ui.page.library

import java.io.File
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedLibraryImageSlotsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingFirstAndMiddleImagesKeepSlotsAndRetainedLaterImagesWhenDownloadsArrive() {
        val root = temporary.newFolder("saved-library")
        val first = image(root, "first.img")
        val retained = image(root, "retained.img")
        val middle = image(root, "middle.img")
        val last = image(root, "last.img")
        val before = """<p>Text remains in the same DOM order.</p>
            <img alt="missing first" data-inkread-saved-source="https://example.invalid/first">
            <img alt="retained second" src="${retained.toURI()}">
            <p>Text between images remains unchanged.</p>
            <img alt="missing middle" data-inkread-saved-source="https://example.invalid/middle">
            <img alt="retained last" src="${last.toURI()}">"""
        val after = Jsoup.parseBodyFragment(before).body().also { body ->
            body.select("img")[0].attr("src", first.toURI().toString())
            body.select("img")[2].attr("src", middle.toURI().toString())
        }.html()
        val initial = SavedLibraryImageSlots(before, root, "saved-A")
        val completed = SavedLibraryImageSlots(after, root, "saved-A")
        val initialUrls = urls(initial.html)
        val completedUrls = urls(completed.html)
        assertEquals((0..3).map { "https://inkread.local/saved-library-assets/saved-A/$it" }, initialUrls)
        assertEquals(initialUrls, completedUrls)
        assertEquals("Image arrival cannot change the reader's immutable HTML", initial.html, completed.html)
        assertNull(initial.fileFor(initialUrls[0]))
        assertEquals(retained.canonicalFile, initial.fileFor(initialUrls[1]))
        assertNull(initial.fileFor(initialUrls[2]))
        assertEquals(last.canonicalFile, initial.fileFor(initialUrls[3]))
        assertEquals(first.canonicalFile, completed.fileFor(completedUrls[0]))
        assertEquals(retained.canonicalFile, completed.fileFor(completedUrls[1]))
        assertEquals(middle.canonicalFile, completed.fileFor(completedUrls[2]))
        assertEquals(last.canonicalFile, completed.fileFor(completedUrls[3]))
        assertEquals(last.canonicalFile, completed.fileFor(completedUrls[3] + "?revision=new-mapping"))
    }

    @Test fun allMissingImagesGetSyntheticSlotsBeforeAnyFileExists() {
        val root = temporary.newFolder("saved-library")
        val slots = SavedLibraryImageSlots("<img alt='first'><img alt='middle'><img alt='last'>", root, "saved-A")
        val urls = urls(slots.html)
        assertEquals(3, urls.size)
        urls.forEachIndexed { index, url ->
            assertEquals("https://inkread.local/saved-library-assets/saved-A/$index", url)
            assertTrue(slots.owns(url))
            assertNull(slots.fileFor(url))
        }
    }

    @Test fun differentItemsCannotReuseEachOthersResourceSlot() {
        val root = temporary.newFolder("saved-library")
        val file = image(root, "retained.img")
        val a = SavedLibraryImageSlots("<img src='${file.toURI()}'>", root, "saved-A")
        val b = SavedLibraryImageSlots("<img src='${file.toURI()}'>", root, "saved-B")
        val aUrl = urls(a.html).single()
        assertFalse(b.owns(aUrl))
        assertNull(b.fileFor(aUrl))
    }

    private fun image(root: File, name: String): File = File(root, name).apply { writeBytes(byteArrayOf(1)) }
    private fun urls(html: String): List<String> = Jsoup.parseBodyFragment(html).select("img").map { it.attr("src") }
}

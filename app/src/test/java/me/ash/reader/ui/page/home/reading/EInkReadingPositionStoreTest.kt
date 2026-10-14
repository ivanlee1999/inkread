package me.ash.reader.ui.page.home.reading

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EInkReadingPositionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fingerprint = EInkReadingPositionStore.fingerprint("Example article")
    private fun position(offset: Int = 30, fraction: Double = .5) = EInkReadingPosition(fingerprint, offset, fraction)

    @Test fun `streamed fingerprint matches UTF8 across surrogate chunk boundaries`() {
        for (prefix in 2046..2049) {
            val content = "x".repeat(prefix) + "😀中文é" + "y".repeat(4097) + "𝄞"
            val expected = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals("Prefix length $prefix", expected, EInkReadingPositionStore.fingerprint(content))
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            EInkReadingPositionStore.fingerprint(""))
    }

    @Test fun `persists current position across new instances and isolates accounts and articles`() {
        val directory = temporary.newFolder()
        val store = EInkReadingPositionStore(directory)
        store.save(1, "article", position())
        store.save(2, "article", position(80, .8))
        store.save(1, "other", position(5, .1))
        // Moving backwards must replace furthest progress.
        store.save(1, "article", position(10, .2))
        val reopened = EInkReadingPositionStore(directory)
        assertEquals(position(10, .2), reopened.load(1, "article"))
        assertEquals(position(80, .8), reopened.load(2, "article"))
        assertEquals(position(5, .1), reopened.load(1, "other"))
        assertNull(reopened.load(3, "article"))
        val disk = File(directory, "eink-reading-positions").readText()
        assertFalse(disk.contains("article"))
        assertFalse(disk.contains("Example"))
    }

    @Test fun `invalid values and corrupt journal are ignored`() {
        val directory = temporary.newFolder()
        File(directory, "eink-reading-positions").writeText("corrupt=not-a-fingerprint,-1,NaN")
        val store = EInkReadingPositionStore(directory)
        assertNull(store.load(1, "article"))
        store.save(1, "article", position(-1))
        store.save(1, "article", position(fraction = Double.NaN))
        store.save(1, "article", position(fraction = 1.1))
        assertNull(store.load(1, "article"))
        store.save(1, "article", position())
        assertEquals(position(), EInkReadingPositionStore(directory).load(1, "article"))
    }

    @Test fun `journal is bounded and keeps recently saved articles`() {
        val directory = temporary.newFolder()
        val store = EInkReadingPositionStore(directory)
        repeat(EInkReadingPositionStore.MAX_ENTRIES + 2) { store.save(1, "$it", position(it)) }
        val reopened = EInkReadingPositionStore(directory)
        assertNull(reopened.load(1, "0"))
        assertNull(reopened.load(1, "1"))
        assertEquals(position(129), reopened.load(1, "129"))
        assertEquals(EInkReadingPositionStore.MAX_ENTRIES, File(directory, "eink-reading-positions").readLines().size)
        assertEquals(1, directory.listFiles()!!.size)
    }
}

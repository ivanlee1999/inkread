package me.ash.reader.infrastructure.rss

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class FeedSourceHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun history() = FeedSourceHistory(mock<Context> { on { filesDir } doReturn temporary.root }, Dispatchers.IO, mock())

    @Test fun exactServerBodiesSurviveRecreationAndRemainAccountScoped() = runBlocking {
        val first = "<div class='server-original'>中文 &amp; exact\nmarkup.</div>"
        val second = "<p>Second server body.</p>"
        history().archive("item", 1, first)
        history().archive("item", 1, second)
        val reopened = history()
        val earlier = reopened.readEarlier("item", 1).getOrThrow()
        val next = reopened.readEarlier("item", 1, earlier).getOrThrow()
        assertEquals(setOf(first, second), setOf(earlier, next))
        assertEquals(earlier, reopened.readEarlier("item", 1, next).getOrThrow())
        assertTrue(reopened.readEarlier("item", 2).isFailure)
        assertTrue(reopened.readEarlier("another item", 1).isFailure)
        assertEquals(0, temporary.root.walk().count { it.extension == "tmp" })
        assertFalse(temporary.root.resolve("readability").exists())
        assertFalse(temporary.root.resolve("reader-browser-text").exists())
    }

    @Test fun fiveVersionsAreRetainedAndDuplicatesDoNotConsumeCapacity() = runBlocking {
        val history = history()
        repeat(5) { history.archive("item", 1, "<p>Version $it</p>") }
        history.archive("item", 1, "<p>Version 0</p>")
        try {
            history.archive("item", 1, "<p>Version 5</p>")
            fail("Expected bounded history")
        } catch (_: IllegalStateException) { }
        assertEquals(5, temporary.root.walk().count { it.extension == "html" })
    }

    @Test fun oversizedOrMalformedUnicodeBodiesCannotEnterHistory() = runBlocking {
        for (body in listOf("中".repeat((MAX_FEED_BODY_BYTES / 3 + 1).toInt()), "\ud800")) {
            try {
                history().archive("item", 1, body)
                fail("Expected bounded valid source text")
            } catch (_: IOException) { }
        }
        assertFalse(temporary.root.resolve("reader-feed-sources").exists())
    }

    @Test fun corruptHistoryIsNotAcceptedAsDurableProtection() = runBlocking {
        val history = history()
        history.archive("item", 1, "Original")
        temporary.root.walk().single { it.extension == "html" }.writeText("Changed")
        assertTrue(history.readEarlier("item", 1).isFailure)
        try {
            history.archive("item", 1, "Original")
            fail("Expected snapshot integrity failure")
        } catch (_: IOException) { }
    }
}

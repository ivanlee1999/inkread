package me.ash.reader.domain.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedUnreadCountsTest {
    @Test fun `mixed read and unread changes preserve each feed delta`() {
        val diffs = listOf(
            Diff(true, "a", "first"), Diff(false, "b", "first"), Diff(false, "c", "first"),
            Diff(true, "d", "second"), Diff(true, "e", "second"),
        )
        assertEquals(mapOf("first" to -1, "second" to 2), unreadCountDeltasByFeed(diffs))
        assertEquals(emptyMap<String, Int>(), unreadCountDeltasByFeed(emptyList()))
    }

    @Test fun `large pending snapshot is traversed once and matches original counts`() {
        val diffs = List(10_000) { index ->
            Diff(index % 3 == 0, "article-$index", "feed-${index % 500}")
        }
        var visits = 0
        val observed = object : AbstractCollection<Diff>() {
            override val size = diffs.size
            override fun iterator(): Iterator<Diff> {
                val delegate = diffs.iterator()
                return object : Iterator<Diff> {
                    override fun hasNext() = delegate.hasNext()
                    override fun next() = delegate.next().also { visits++ }
                }
            }
        }
        val actual = unreadCountDeltasByFeed(observed)
        assertEquals("Aggregation must not scan pending changes once per feed", 10_000, visits)
        for (feed in 0 until 500) {
            val feedId = "feed-$feed"
            val original = diffs.count { it.feedId == feedId && it.isUnread } -
                diffs.count { it.feedId == feedId && !it.isUnread }
            assertEquals(original, actual[feedId])
        }
    }
}

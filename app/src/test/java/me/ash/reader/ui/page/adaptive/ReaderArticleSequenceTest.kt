package me.ash.reader.ui.page.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderArticleSequenceTest {
    @Test fun `removed identities retain their order and new loads append once`() {
        val pager = Any()
        val sequence = ReaderArticleSequence<String> { it }
        sequence.update(pager, listOf("A", "B", "C"))
        assertEquals(listOf("A", "B", "C", "D"),
            sequence.update(pager, listOf("A", "C", "D", "D")))
        assertEquals("B", sequence.find(pager, "B"))
    }

    @Test fun `new pager and cleared session never inherit old neighbours`() {
        val oldPager = Any()
        val newPager = Any()
        val sequence = ReaderArticleSequence<String> { it }
        sequence.update(oldPager, listOf("A", "B"))
        assertNull(sequence.find(newPager, "A"))
        assertEquals(listOf("C"), sequence.update(newPager, listOf("C")))
        assertNull(sequence.find(newPager, "A"))
        sequence.clear()
        assertNull(sequence.find(newPager, "C"))
        assertEquals(listOf("D"), sequence.update(newPager, listOf("D")))
    }

    @Test fun `latest values update without moving existing identities`() {
        val pager = Any()
        val sequence = ReaderArticleSequence<Pair<String, Int>> { it.first }
        sequence.update(pager, listOf("A" to 0, "B" to 0))
        assertEquals(listOf("A" to 0, "B" to 1), sequence.update(pager, listOf("B" to 1)))
    }

    @Test fun `sync prepends do not become out of order next articles`() {
        val pager = Any()
        val sequence = ReaderArticleSequence<String> { it }
        sequence.update(pager, listOf("A", "B"))
        assertEquals(listOf("A", "B", "C"), sequence.update(pager, listOf("new", "A", "B", "C")))
    }

    @Test fun `disjoint unread continuation remains reachable after entire session is read`() {
        val pager = Any()
        val sequence = ReaderArticleSequence<String> { it }
        sequence.update(pager, listOf("A", "B"))
        assertEquals(listOf("A", "B", "C", "D"), sequence.update(pager, listOf("C", "D")))
    }
}

package me.ash.reader.ui.page.home.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewspaperArticleRestorationTest {
    private val key = listOf("pager", 2, 24, 4)
    private val target = NewspaperArticleRestoreTarget("lower-brief", 51, 1, 4, key)

    @Test fun lowerBriefConsumesItsMeasuredOffsetExactlyOnce() {
        val restoration = NewspaperArticleRestoration()
        restoration.begin(target)
        assertEquals(720, restoration.consumePosition("lower-brief", 51, 1, 4, 720, key))
        assertNull(restoration.target)
        assertNull(restoration.consumePosition("lower-brief", 51, 1, 4, 720, key))
    }

    @Test fun differentArticleRowOrColumnsCannotConsumeTheTarget() {
        val restoration = NewspaperArticleRestoration()
        restoration.begin(target)
        assertNull(restoration.consumePosition("upper-brief", 50, 1, 4, 0, key))
        assertNull(restoration.consumePosition("lower-brief", 52, 1, 4, 720, key))
        assertNull(restoration.consumePosition("lower-brief", 51, 2, 4, 720, key))
        assertNull(restoration.consumePosition("lower-brief", 51, 1, 3, 720, key))
        assertEquals(target, restoration.target)
    }

    @Test fun replacedPagerPageCapacityOrColumnsCancelsObsoleteGeometry() {
        listOf(listOf("other-pager", 2, 24, 4), listOf("pager", 3, 24, 4),
            listOf("pager", 2, 28, 4), listOf("pager", 2, 24, 3)).forEach { current ->
            val restoration = NewspaperArticleRestoration()
            restoration.begin(target)
            assertNull(restoration.consumePosition("lower-brief", 51, 1, 4, 720, current))
            assertNull(restoration.target)
        }
    }

    @Test fun explicitNavigationCancelsAndNewRequestReplacesTheOldOne() {
        val restoration = NewspaperArticleRestoration()
        restoration.begin(target)
        restoration.cancel()
        assertNull(restoration.consumePosition("lower-brief", 51, 1, 4, 720, key))
        val next = target.copy(articleId = "next-brief", sourceIndex = 53, rowIndex = 2)
        restoration.begin(next)
        assertNull(restoration.consumePosition("lower-brief", 51, 1, 4, 720, key))
        assertEquals(next, restoration.target)
        assertEquals(0, restoration.consumePosition("next-brief", 53, 2, 4, -1, key))
    }
}

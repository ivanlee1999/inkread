package me.ash.reader.ui.page.home.reading

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class EInkReadingPositionStoreTest {

    @Before
    fun setUp() = EInkReadingPositionStore.clear()

    @After
    fun tearDown() = EInkReadingPositionStore.clear()

    @Test
    fun `saved position round-trips`() {
        EInkReadingPositionStore.save("article-1", 0.42f)

        assertEquals(0.42f, EInkReadingPositionStore.get("article-1")!!, 0.0001f)
    }

    @Test
    fun `unknown article has no position`() {
        assertNull(EInkReadingPositionStore.get("never-read"))
    }

    @Test
    fun `positions are kept per article`() {
        EInkReadingPositionStore.save("a", 0.1f)
        EInkReadingPositionStore.save("b", 0.9f)

        assertEquals(0.1f, EInkReadingPositionStore.get("a")!!, 0.0001f)
        assertEquals(0.9f, EInkReadingPositionStore.get("b")!!, 0.0001f)
    }

    @Test
    fun `saving the start of an article clears any stored position`() {
        EInkReadingPositionStore.save("a", 0.5f)

        EInkReadingPositionStore.save("a", 0f)

        // Page 1 is the default anyway, so there is nothing worth restoring.
        assertNull(EInkReadingPositionStore.get("a"))
    }

    @Test
    fun `out-of-range fractions are clamped`() {
        EInkReadingPositionStore.save("a", 4f)
        assertEquals(1f, EInkReadingPositionStore.get("a")!!, 0.0001f)

        EInkReadingPositionStore.save("b", -2f)
        assertNull(EInkReadingPositionStore.get("b"))
    }

    @Test
    fun `store is bounded and evicts the least recently used article`() {
        repeat(EInkReadingPositionStore.MAX_ENTRIES) {
            EInkReadingPositionStore.save("article-$it", 0.5f)
        }
        // Touch the oldest entry so it is no longer the eviction candidate.
        EInkReadingPositionStore.get("article-0")

        EInkReadingPositionStore.save("one-too-many", 0.5f)

        assertEquals(0.5f, EInkReadingPositionStore.get("article-0")!!, 0.0001f)
        assertNull(EInkReadingPositionStore.get("article-1"))
        assertEquals(0.5f, EInkReadingPositionStore.get("one-too-many")!!, 0.0001f)
    }
}

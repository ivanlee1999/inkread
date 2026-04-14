package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class EInkPaginationMathTest {

    @Test
    fun `countPages ignores tiny trailing slack`() {
        val pages = EInkPaginationMath.countPages(
            contentWidthPx = 1000.4,
            pageStridePx = 500.0,
        )

        assertEquals(2, pages)
    }

    @Test
    fun `countPages grows when image reflow adds a real page`() {
        val pages = EInkPaginationMath.countPages(
            contentWidthPx = 2450.0,
            pageStridePx = 600.0,
        )

        assertEquals(4, pages)
    }

    @Test
    fun `translationForPage uses stable viewport stride after recount`() {
        val translation = EInkPaginationMath.translationForPage(
            pageIndex = 3,
            totalPages = 4,
            pageStridePx = 600.0,
        )

        assertEquals(1800.0, translation, 0.0)
    }

    @Test
    fun `translationForPage clamps page index into valid range`() {
        val translation = EInkPaginationMath.translationForPage(
            pageIndex = 8,
            totalPages = 4,
            pageStridePx = 600.0,
        )

        assertEquals(1800.0, translation, 0.0)
    }
}

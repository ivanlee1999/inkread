package me.ash.reader.ui.page.home.reading

import kotlin.math.roundToInt

/**
 * Pure helpers for e-ink paginated layout geometry.
 *
 * The page stride must stay tied to the viewport width. Late image reflow can
 * change the number of columns/pages, but it must not retroactively change the
 * translation step between pages or the content will drift horizontally.
 */
internal object EInkPaginationMath {
    const val MAX_REASONABLE_PAGES = 500
    const val TRAILING_SLACK_TOLERANCE_PX = 1.0

    fun sanitizePageStride(pageStridePx: Double): Double = if (
        pageStridePx.isFinite() && pageStridePx > 0.0
    ) {
        pageStridePx
    } else {
        1.0
    }

    fun countPages(
        contentWidthPx: Double,
        pageStridePx: Double,
        maxPages: Int = MAX_REASONABLE_PAGES,
        trailingSlackTolerancePx: Double = TRAILING_SLACK_TOLERANCE_PX,
    ): Int {
        val contentWidth = contentWidthPx.takeIf { it.isFinite() && it > 0.0 } ?: return 1
        val stride = sanitizePageStride(pageStridePx)
        var pages = (contentWidth / stride).roundToInt().coerceAtLeast(1)
        if (pages > maxPages) return 1

        if (pages > 1) {
            val lastPageStart = (pages - 1) * stride
            if (contentWidth <= lastPageStart + trailingSlackTolerancePx) {
                pages -= 1
            }
        }
        return pages.coerceAtLeast(1)
    }

    fun translationForPage(
        pageIndex: Int,
        totalPages: Int,
        pageStridePx: Double,
    ): Double {
        val clampedPage = pageIndex.coerceIn(0, maxOf(0, totalPages - 1))
        return clampedPage * sanitizePageStride(pageStridePx)
    }
}

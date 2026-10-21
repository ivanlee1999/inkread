package me.ash.reader.ui.layout

/** One measured presentation of the same real, indivisible ordered source group. */
internal data class OrderedPageChoice<T>(
    val value: T,
    val heightPx: Int,
    val contentUnits: Int = 1,
    /** Detail loss per real source, not per row; Full presentations use zero. */
    val detailPenalty: Int = 0,
)

internal data class OrderedPageGroup<T>(
    val choices: List<OrderedPageChoice<T>>,
    /** A printed source boundary. Search must not inspect a later group on this page. */
    val breakAfter: Boolean = false,
)

/** Measured loss is deliberately bounded; Compact never changes the user's type size. */
internal fun orderedCompactPenalty(fullHeightPx: Int, compactHeightPx: Int): Int {
    require(fullHeightPx > 0 && compactHeightPx > 0)
    return 30 + (160L * (fullHeightPx - compactHeightPx).coerceAtLeast(0) / fullHeightPx).toInt()
}

/**
 * Original Kotlin bounded whole-page search, not a global optimum or a photo-strip port.
 *
 * Design references, without copied code or dependencies:
 * - Treesaver layout/grid.js and layout/page.js: compare complete page skeletons, penalize empty
 *   space, preserve keep-together blocks, and require forward progress (MIT option).
 *   https://github.com/Treesaver/treesaver/blob/master/src/layout/grid.js
 * - React Photo Album layouts/rows/rows.ts: contiguous ordered candidates, squared deviation,
 *   bounded transitions and deterministic ties (MIT).
 *   https://github.com/igordanchenko/react-photo-album/blob/main/src/layouts/rows/rows.ts
 *
 * Hard constraints are separate from the score: positive measured heights, complete groups,
 * exact source order, <= viewport, and no crossing a locked end. Only callers' Full/Compact
 * choices participate; accessibility fallbacks must be the only choices in their group.
 *
 * Cost = 10000 * (unusedPx / viewportPx)^2 + sum(detailPenalty * realSourceCount)
 *        - 10 * min(realSourceCount, 8).
 * The small capped source reward cannot outweigh arbitrarily lost detail. In particular, adding
 * Full sources never dilutes an already incurred Compact penalty. There is no terminal-page
 * bonus: a locked end and the physical edition end have exactly the same scoring behavior.
 * Ties prefer less detail loss, greater used height, then lexicographic caller choice order
 * (put Full/original templates first). All geometry and score arithmetic are integer/Long.
 *
 * At most 6 choices/group, 24 retained states and 64 groups/page are explored. Each depth keeps
 * 8 short/headroom states, 4 lowest-detail-loss states, and 12 best current page scores, filling
 * duplicate vacancies deterministically. This retains multi-row compaction paths that a greedy
 * fit or score-only beam would discard. A capped prefix is a complete page; every remaining
 * group is planned on subsequent pages, never skipped. Measurements belong to the caller's cache.
 */
internal fun <T> planOrderedPages(
    groups: List<OrderedPageGroup<T>>,
    viewportHeightPx: Int,
): List<List<T>> {
    require(viewportHeightPx > 0)
    groups.forEach { group ->
        require(group.choices.size in 1..MAX_CHOICES)
        require(group.choices.all { it.heightPx in 1..viewportHeightPx &&
            it.contentUnits > 0 && it.detailPenalty in 0..1000 })
        require(group.choices.all { it.contentUnits == group.choices.first().contentUnits })
    }
    val pages = mutableListOf<List<T>>()
    var start = 0
    while (start < groups.size) {
        var states = listOf(OrderedPageState<T>())
        var best: OrderedPageState<T>? = null
        val scoreOrder = Comparator<OrderedPageState<T>> { left, right ->
            compareValues(pageCost(left, viewportHeightPx), pageCost(right, viewportHeightPx))
                .takeIf { it != 0 }
                ?: compareValues(left.detailLoss, right.detailLoss).takeIf { it != 0 }
                ?: compareValues(right.heightPx, left.heightPx).takeIf { it != 0 }
                ?: compareChoiceOrder(left.choiceIndices, right.choiceIndices)
        }
        val heightOrder = compareBy<OrderedPageState<T>> { it.heightPx }
            .thenBy { it.detailLoss }.then(scoreOrder)
        val detailOrder = compareBy<OrderedPageState<T>> { it.detailLoss }.then(scoreOrder)
        for (index in start until minOf(groups.size, start + MAX_GROUPS_PER_PAGE)) {
            val group = groups[index]
            val next = states.flatMap { state ->
                group.choices.mapIndexedNotNull { choiceIndex, choice ->
                    if (choice.heightPx > viewportHeightPx - state.heightPx) null
                    else OrderedPageState(
                        values = state.values + choice.value,
                        choiceIndices = state.choiceIndices + choiceIndex,
                        heightPx = state.heightPx + choice.heightPx,
                        contentUnits = state.contentUnits + choice.contentUnits.toLong(),
                        detailLoss = state.detailLoss + choice.detailPenalty.toLong() * choice.contentUnits,
                    )
                }
            }
            if (next.isEmpty()) break
            // All states at this depth consume exactly the same source prefix. Equivalent
            // geometry/quality retains the original-template path, not whichever hash wins.
            val distinct = next.sortedWith(scoreOrder)
                .distinctBy { Triple(it.heightPx, it.detailLoss, it.contentUnits) }
            val candidate = distinct.first()
            if (best == null || scoreOrder.compare(candidate, best) < 0) best = candidate
            if (group.breakAfter) break
            val retained = LinkedHashSet<OrderedPageState<T>>()
            retained.addAll(distinct.sortedWith(heightOrder).take(8))
            retained.addAll(distinct.sortedWith(detailOrder).take(4))
            retained.addAll(distinct.take(12))
            distinct.forEach { if (retained.size < MAX_STATES) retained += it }
            states = retained.take(MAX_STATES)
        }
        // The first group's individual choices were validated to fit, so progress is guaranteed.
        val selected = checkNotNull(best)
        check(selected.values.isNotEmpty())
        pages += selected.values
        start += selected.values.size
    }
    return pages
}

private const val MAX_CHOICES = 6
private const val MAX_STATES = 24
private const val MAX_GROUPS_PER_PAGE = 64

// The retained beam combines sorted views of the same instances after geometry deduplication.
// Identity membership is sufficient and must not hash arbitrary, potentially large source values.
private class OrderedPageState<T>(
    val values: List<T> = emptyList(),
    val choiceIndices: List<Int> = emptyList(),
    val heightPx: Int = 0,
    val contentUnits: Long = 0,
    val detailLoss: Long = 0,
)

private fun pageCost(state: OrderedPageState<*>, viewportHeightPx: Int): Long {
    val empty = (viewportHeightPx - state.heightPx).toLong()
    // Scale before squaring with a bounded factor to avoid overflow for any Int viewport.
    val emptyBasisPoints = empty * 10_000L / viewportHeightPx
    val emptyCost = emptyBasisPoints * emptyBasisPoints / 10_000L
    return emptyCost + state.detailLoss - 10L * minOf(state.contentUnits, 8L)
}

private fun compareChoiceOrder(left: List<Int>, right: List<Int>): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val difference = left[index].compareTo(right[index])
        if (difference != 0) return difference
    }
    return left.size.compareTo(right.size)
}

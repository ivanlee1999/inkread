package me.ash.reader.ui.page.home.flow

/** One editorial row keeps original paging indices intact for navigation and read actions. */
internal data class NewspaperRow(val indices: List<Int>, val isDate: Boolean, val isLead: Boolean)

internal fun newspaperRows(
    itemCount: Int,
    columns: Int,
    start: Int = 0,
    end: Int = itemCount,
    isDate: (Int) -> Boolean,
): List<NewspaperRow> = buildList {
    var index = start.coerceIn(0, itemCount)
    val limit = end.coerceIn(index, itemCount)
    var lead = true
    while (index < limit) {
        if (isDate(index)) {
            add(NewspaperRow(listOf(index++), isDate = true, isLead = false))
            lead = true
        } else {
            val indices = mutableListOf(index++)
            if (!lead) {
                while (indices.size < columns.coerceIn(1, 2) && index < limit && !isDate(index)) {
                    indices.add(index++)
                }
            }
            add(NewspaperRow(indices, isDate = false, isLead = lead))
            lead = false
        }
    }
}

internal fun List<NewspaperRow>.rowForSourceIndex(index: Int): Int =
    indexOfFirst { index in it.indices }.coerceAtLeast(0)


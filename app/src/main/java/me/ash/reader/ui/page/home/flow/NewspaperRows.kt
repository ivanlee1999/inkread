package me.ash.reader.ui.page.home.flow

/** Editorial emphasis never depends on whether an image has finished loading. */
enum class NewspaperStoryStyle { Standard, Feature, Brief }

/** A lane can span two printed columns, or contain a bounded stack of short briefs. */
internal data class NewspaperSlot(
    val indices: List<Int>,
    val span: Int = 1,
    val style: NewspaperStoryStyle = NewspaperStoryStyle.Standard,
)

/**
 * One lazy row keeps its contiguous, ascending source indices intact for navigation and read
 * actions. Slots describe presentation only; flattening them is always exactly [indices].
 */
internal data class NewspaperRow(
    val indices: List<Int>,
    val isDate: Boolean,
    val isLead: Boolean,
    val slots: List<NewspaperSlot> = indices.map { NewspaperSlot(listOf(it)) },
)

internal fun newspaperRows(
    itemCount: Int,
    columns: Int,
    start: Int = 0,
    end: Int = itemCount,
    isDate: (Int) -> Boolean,
): List<NewspaperRow> = buildList {
    val count = itemCount.coerceAtLeast(0)
    val lanes = columns.coerceIn(1, 4)
    var index = start.coerceIn(0, count)
    val limit = end.coerceIn(index, count)
    var lead = true
    var supportingRow = 0
    while (index < limit) {
        if (isDate(index)) {
            add(NewspaperRow(listOf(index++), isDate = true, isLead = false))
            lead = true
            supportingRow = 0
        } else if (lead) {
            val indices = listOf(index++)
            add(NewspaperRow(indices, isDate = false, isLead = true,
                slots = listOf(NewspaperSlot(indices, span = lanes, style = NewspaperStoryStyle.Feature))))
            lead = false
        } else {
            // Alternate a feature + brief stack with an ordinary column row. The template
            // uses only source positions and date boundaries, never mutable article content.
            val editorialCapacity = 1 + (lanes - 2) * 2
            val tryEditorial = lanes >= 3 && supportingRow % 2 == 0
            val capacity = if (tryEditorial) editorialCapacity else lanes
            val indices = mutableListOf<Int>()
            while (indices.size < capacity && index < limit && !isDate(index)) {
                indices.add(index++)
            }
            val slots = if (tryEditorial && indices.size >= 3) {
                // A short four-lane section has only one brief stack. Give it the remaining
                // two lanes rather than leaving an empty column; source/row identity is unchanged.
                val briefSpan = if (lanes == 4 && indices.size == 3) 2 else 1
                listOf(NewspaperSlot(listOf(indices.first()), span = 2, style = NewspaperStoryStyle.Feature)) +
                    indices.drop(1).chunked(2).map {
                        NewspaperSlot(it, span = briefSpan, style = NewspaperStoryStyle.Brief)
                    }
            } else {
                indices.mapIndexed { position, sourceIndex ->
                    NewspaperSlot(listOf(sourceIndex), style =
                        if (lanes > 1 && (position + supportingRow) % 2 == 1) NewspaperStoryStyle.Brief
                        else NewspaperStoryStyle.Standard)
                }
            }
            add(NewspaperRow(indices, isDate = false, isLead = false, slots = slots))
            supportingRow++
        }
    }
}

internal fun List<NewspaperRow>.rowForSourceIndex(index: Int): Int =
    indexOfFirst { index in it.indices }.coerceAtLeast(0)

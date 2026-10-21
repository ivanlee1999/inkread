package me.ash.reader.domain.data

/** Aggregate once per snapshot instead of scanning every pending change for every feed. */
internal fun unreadCountDeltasByFeed(diffs: Collection<Diff>): Map<String, Int> {
    val deltas = mutableMapOf<String, Int>()
    for (diff in diffs) {
        deltas[diff.feedId] = (deltas[diff.feedId] ?: 0) + if (diff.isUnread) 1 else -1
    }
    return deltas
}

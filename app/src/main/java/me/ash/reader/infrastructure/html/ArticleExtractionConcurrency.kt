package me.ash.reader.infrastructure.html

import kotlinx.coroutines.sync.Semaphore

/** One modern parser at a time: prefetch must not multiply interpreter memory on e-ink devices. */
internal object ArticleExtractionConcurrency {
    val permit = Semaphore(1)
}

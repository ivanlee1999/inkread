package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class NewspaperArticleRestoreTarget(
    val articleId: String,
    val sourceIndex: Int,
    val rowIndex: Int,
    val columns: Int,
    val layoutKey: Any,
)

/** A row jump composes the story; its measured slot offset finishes the return without animation. */
internal class NewspaperArticleRestoration {
    var target: NewspaperArticleRestoreTarget? by mutableStateOf(null)
        private set

    fun begin(target: NewspaperArticleRestoreTarget) {
        this.target = target
    }

    fun request(target: NewspaperArticleRestoreTarget, listState: LazyListState) {
        begin(target)
        listState.requestScrollToItem(target.rowIndex)
    }

    fun cancel() {
        target = null
    }

    /** Consume only the requested source in the same page/layout; obsolete geometry cannot jump. */
    fun consumePosition(
        articleId: String,
        sourceIndex: Int,
        rowIndex: Int,
        columns: Int,
        offsetPx: Int,
        currentLayoutKey: Any,
    ): Int? {
        val pending = target ?: return null
        if (pending.layoutKey != currentLayoutKey) {
            cancel()
            return null
        }
        if (pending.articleId != articleId || pending.sourceIndex != sourceIndex ||
            pending.rowIndex != rowIndex || pending.columns != columns
        ) return null
        cancel()
        return offsetPx.coerceAtLeast(0)
    }

    fun measured(
        articleId: String,
        sourceIndex: Int,
        rowIndex: Int,
        columns: Int,
        offsetPx: Int,
        currentLayoutKey: Any,
        listState: LazyListState,
    ) {
        val offset = consumePosition(articleId, sourceIndex, rowIndex, columns, offsetPx,
            currentLayoutKey) ?: return
        listState.requestScrollToItem(rowIndex, offset)
    }
}

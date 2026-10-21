package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import me.ash.reader.infrastructure.preference.LocalReadingColumns
import me.ash.reader.infrastructure.preference.LocalReadingLayoutPreset
import me.ash.reader.ui.layout.ReadingLayoutMetrics
import me.ash.reader.ui.layout.resolveReadingLayout

/**
 * Resolve from the inset-adjusted content pane before composing its first article frame.
 * An onSizeChanged -> state -> columns feedback loop first renders a one-column fallback
 * whenever the reader disposes/recreates the list, then reflows the retained edition a frame
 * later. Constraints are already known here, including on return and split-pane resizing.
 */
@Composable
internal fun ArticleListPaneLayout(
    modifier: Modifier = Modifier,
    content: @Composable (ReadingLayoutMetrics) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val readingLayout = resolveReadingLayout(
            availableWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
            preset = LocalReadingLayoutPreset.current.preset,
            columnPreference = LocalReadingColumns.current.columns,
        )
        Column(Modifier.fillMaxSize()) { content(readingLayout) }
    }
}

package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import me.ash.reader.infrastructure.preference.LocalReadingColumns
import me.ash.reader.infrastructure.preference.LocalReadingLayoutPreset
import me.ash.reader.ui.layout.ReadingLayoutMetrics
import me.ash.reader.ui.layout.resolveReadingLayout

/**
 * Resolve from the inset-adjusted content pane before composing its first article frame.
 * An onSizeChanged -> state -> columns feedback loop first renders a one-column fallback
 * whenever the reader disposes/recreates the list, then reflows the retained edition a frame
 * later. Resolve and subcompose together during measurement, so every measured/drawn frame
 * uses the current constraints. A live slot may independently recompose its previous metrics
 * before remeasurement; that intermediate composition is not itself a rendered frame.
 */
@Composable
internal fun ArticleListPaneLayout(
    modifier: Modifier = Modifier,
    content: @Composable (ReadingLayoutMetrics) -> Unit,
) {
    val density = LocalDensity.current
    val preset = LocalReadingLayoutPreset.current.preset
    val columnPreference = LocalReadingColumns.current.columns
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val metrics = resolveReadingLayout(
            availableWidthDp = with(density) { constraints.maxWidth.toDp() }.value,
            fontScale = density.fontScale,
            preset = preset,
            columnPreference = columnPreference,
        )
        val pane = subcompose(Unit) {
            Column(Modifier.fillMaxSize()) { content(metrics) }
        }.single().measure(constraints)
        layout(pane.width, pane.height) { pane.placeRelative(0, 0) }
    }
}

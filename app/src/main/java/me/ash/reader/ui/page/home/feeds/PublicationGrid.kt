package me.ash.reader.ui.page.home.feeds

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.ash.reader.infrastructure.preference.LocalReadingColumns
import me.ash.reader.infrastructure.preference.LocalReadingLayoutPreset
import me.ash.reader.ui.layout.resolveReadingLayout

/**
 * Row-major publication columns with newspaper rules, not nested scrolling surfaces.
 * The full pane width enters the shared resolver and its gutters are applied exactly once.
 * Native vertical drags and parent list gestures therefore keep working over every tile.
 */
@Composable
fun PublicationGrid(
    itemCount: Int,
    modifier: Modifier = Modifier,
    itemContent: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = resolveReadingLayout(
            availableWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
            preset = LocalReadingLayoutPreset.current.preset,
            columnPreference = LocalReadingColumns.current.columns,
        )
        val columns = layout.publicationColumns
        Column(
            modifier = Modifier.padding(horizontal = layout.horizontalPaddingDp.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (start in 0 until itemCount step columns) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    repeat(columns) { column ->
                        if (column > 0) {
                            Box(
                                modifier = Modifier.width(layout.columnSpacingDp.dp).fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                VerticalDivider(color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        val index = start + column
                        if (index < itemCount) {
                            itemContent(index, Modifier.weight(1f).fillMaxHeight())
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

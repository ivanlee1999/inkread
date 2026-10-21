package me.ash.reader.ui.component.base

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compact edition furniture using real screen context, never invented news or weather. */
@Composable
fun NewspaperMasthead(
    title: String,
    details: String? = null,
    modifier: Modifier = Modifier,
    detailsContent: (@Composable () -> Unit)? = null,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp)) {
        val wide = maxWidth.value >= 560f * fontScale
        Column(Modifier.testTag("newspaper-masthead")) {
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            if (detailsContent != null) {
                // Interactive edition details replace the existing count, not a new header row.
                // Keep a full touch target alongside the title even on a narrow article pane.
                Row(
                    Modifier.fillMaxWidth().padding(vertical = if (wide) 0.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MastheadTitle(title, Modifier.weight(1f))
                    Box(Modifier.widthIn(max = minOf(240.dp, maxWidth * 0.42f))) {
                        detailsContent()
                    }
                }
            } else if (wide && !details.isNullOrBlank()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    MastheadTitle(title, Modifier.weight(1f))
                    Text(details, modifier = Modifier.widthIn(max = 240.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Column(Modifier.padding(vertical = 6.dp)) {
                    MastheadTitle(title)
                    if (!details.isNullOrBlank()) Text(details,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MastheadTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier.semantics { heading() }, fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface)
}

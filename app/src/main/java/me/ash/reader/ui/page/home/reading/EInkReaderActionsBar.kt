package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R

/**
 * Article actions for the e-ink reader — the equivalent of the standard
 * reader's [BottomBar], which is hidden in e-ink mode because the paginated
 * reader owns the bottom of the screen.
 *
 * State is conveyed by filled vs. outlined icons rather than by tint, because
 * the mid-grey tints Material uses for inactive controls dither badly on a
 * grayscale panel.
 */
@Composable
fun EInkReaderActionsBar(
    isUnread: Boolean,
    isStarred: Boolean,
    isFullContent: Boolean,
    onToggleUnread: () -> Unit,
    onToggleStarred: () -> Unit,
    onToggleFullContent: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: (() -> Unit)? = null,
    onOpenInBrowser: (() -> Unit)? = null,
    ttsButton: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .height(52.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EInkActionButton(
            imageVector = if (isUnread) Icons.Filled.FiberManualRecord else Icons.Outlined.FiberManualRecord,
            contentDescription = stringResource(if (isUnread) R.string.mark_as_read else R.string.mark_as_unread),
            onClick = onToggleUnread,
        )
        EInkActionButton(
            imageVector = if (isStarred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
            contentDescription = stringResource(if (isStarred) R.string.mark_as_unstar else R.string.mark_as_starred),
            onClick = onToggleStarred,
        )
        EInkActionButton(
            imageVector = if (isFullContent) {
                Icons.AutoMirrored.Rounded.Article
            } else {
                Icons.AutoMirrored.Outlined.Article
            },
            contentDescription = stringResource(R.string.parse_full_content),
            onClick = onToggleFullContent,
        )
        ttsButton?.invoke()
        if (onShare != null) {
            EInkActionButton(
                imageVector = Icons.Outlined.Share,
                contentDescription = stringResource(R.string.share),
                onClick = onShare,
            )
        }
        if (onOpenInBrowser != null) {
            EInkActionButton(
                imageVector = Icons.Outlined.OpenInBrowser,
                contentDescription = stringResource(R.string.open_in_browser),
                onClick = onOpenInBrowser,
            )
        }
    }
}

@Composable
private fun EInkActionButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.Black,
            modifier = Modifier.size(22.dp),
        )
    }
}

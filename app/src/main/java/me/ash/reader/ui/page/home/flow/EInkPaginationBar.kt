package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EInkPaginationBar(
    currentPage: Int,
    totalPages: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 18,
    canDecreaseFontSize: Boolean = false,
    canIncreaseFontSize: Boolean = false,
    onDecreaseFontSize: () -> Unit = {},
    onIncreaseFontSize: () -> Unit = {},
    onPrevArticle: (() -> Unit)? = null,
    onNextArticle: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Previous article
        IconButton(
            onClick = { onPrevArticle?.invoke() },
            enabled = onPrevArticle != null,
            modifier = Modifier.size(48.dp),
        ) {
            Text(
                text = "◀◀",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (onPrevArticle != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
        }

        // Font size decrease
        IconButton(
            onClick = onDecreaseFontSize,
            enabled = canDecreaseFontSize,
            modifier = Modifier.size(48.dp),
        ) {
            Text(
                text = "A-",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (canDecreaseFontSize) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
        }

        // Previous page
        IconButton(
            onClick = onPrev,
            enabled = currentPage > 1,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Previous page",
                tint = if (currentPage > 1) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
        }

        Text(
            text = "Page $currentPage of $totalPages",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Next page
        IconButton(
            onClick = onNext,
            enabled = currentPage < totalPages,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "Next page",
                tint = if (currentPage < totalPages) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
        }

        // Font size increase
        IconButton(
            onClick = onIncreaseFontSize,
            enabled = canIncreaseFontSize,
            modifier = Modifier.size(48.dp),
        ) {
            Text(
                text = "A+",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (canIncreaseFontSize) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
        }

        // Next article
        IconButton(
            onClick = { onNextArticle?.invoke() },
            enabled = onNextArticle != null,
            modifier = Modifier.size(48.dp),
        ) {
            Text(
                text = "▶▶",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (onNextArticle != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
        }
    }
}

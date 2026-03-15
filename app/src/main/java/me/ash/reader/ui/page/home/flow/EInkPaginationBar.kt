// InkRead E-Ink Pagination Bar - v0.3.4
// Supports article list and content reader pagination
package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EInkPaginationBar(
    currentPage: Int,
    totalPages: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    totalArticles: Int? = null,
    lastSyncTime: Long? = null,
    fontSize: Int = 18,
    canDecreaseFontSize: Boolean = false,
    canIncreaseFontSize: Boolean = false,
    onDecreaseFontSize: () -> Unit = {},
    onIncreaseFontSize: () -> Unit = {},
    onPrevArticle: (() -> Unit)? = null,
    onNextArticle: (() -> Unit)? = null,
    progress: Int? = null,
    currentArticleIndex: Int? = null,
    totalArticleCount: Int? = null,
) {
    var pageTextBold by remember { mutableStateOf(false) }
    LaunchedEffect(currentPage) {
        pageTextBold = true
        delay(1000)
        pageTextBold = false
    }

    // Bottom bar: page controls, font size, article nav, progress
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = Color.Black,
                    start = Offset(0f, strokeWidth / 2),
                    end = Offset(size.width, strokeWidth / 2),
                    strokeWidth = strokeWidth,
                )
            }
            .navigationBarsPadding(),
    ) {
        Divider(color = Color.Black, thickness = 1.dp)
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Color.Black,
                trackColor = Color(0xFFE0E0E0),
            )
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
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
                text = "⏮",
                fontSize = 14.sp,
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
                text = "A↓",
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
            modifier = Modifier.size(52.dp),
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

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (totalArticles != null) {
                    "$currentPage / $totalPages · $totalArticles articles"
                } else if (progress != null) {
                    "$currentPage / $totalPages ($progress%)"
                } else {
                    "$currentPage / $totalPages"
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (pageTextBold) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (currentArticleIndex != null && totalArticleCount != null) {
                Text(
                    text = "$currentArticleIndex/$totalArticleCount",
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
            }
        }

        // Next page
        IconButton(
            onClick = onNext,
            enabled = currentPage < totalPages,
            modifier = Modifier.size(52.dp),
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
                text = "A↑",
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
                text = "⏭",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (onNextArticle != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
        }
    }
        if (lastSyncTime != null && lastSyncTime > 0L) {
            val elapsed = (System.currentTimeMillis() - lastSyncTime) / 1000
            val timeText = when {
                elapsed < 60 -> "Just now"
                elapsed < 3600 -> "${elapsed / 60} min ago"
                elapsed < 86400 -> "${elapsed / 3600}h ago"
                else -> "${elapsed / 86400}d ago"
            }
            Text(
                text = "Last sync: $timeText",
                fontSize = 11.sp,
                color = Color.DarkGray,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

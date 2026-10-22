// InkRead E-Ink Pagination Bar - v0.3.4
// Supports article list and content reader pagination
package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ash.reader.R

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun EInkPaginationBar(
    currentPage: Int,
    totalPages: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    totalArticles: Int? = null,
    lastSyncTime: Long? = null,
    canDecreaseFontSize: Boolean = false,
    canIncreaseFontSize: Boolean = false,
    onDecreaseFontSize: () -> Unit = {},
    onIncreaseFontSize: () -> Unit = {},
    onPrevArticle: (() -> Unit)? = null,
    onNextArticle: (() -> Unit)? = null,
    progress: Int? = null,
    currentArticleIndex: Int? = null,
    totalArticleCount: Int? = null,
    onBack: (() -> Unit)? = null,
    onFormattingClick: (() -> Unit)? = null,
) {
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
            modifier = Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev, enabled = currentPage > 1, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.previous_page), tint = Color.Black)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (progress != null) "$currentPage / $totalPages ($progress%)" else "$currentPage / $totalPages",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Black,
                )
                if (totalArticles != null) {
                    Text(stringResource(R.string.articles_count, totalArticles), fontSize = 11.sp, color = Color.Black)
                } else if (currentArticleIndex != null && totalArticleCount != null) {
                    Text("$currentArticleIndex/$totalArticleCount", fontSize = 11.sp, color = Color.Black)
                }
            }
            IconButton(onClick = onNext, enabled = currentPage < totalPages, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.next_page), tint = Color.Black)
            }
        }
        if (onBack != null || onPrevArticle != null || onNextArticle != null || canDecreaseFontSize || canIncreaseFontSize || onFormattingClick != null) {
            // Keep all 48dp targets intact in narrow split-screen windows.
            // Only this on-demand action row grows; reading space is never
            // reserved when controls are hidden.
            FlowRow(
                modifier = Modifier.fillMaxWidth().testTag("reader_secondary_controls"),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = Color.Black)
                    }
                }
                IconButton(onClick = { onPrevArticle?.invoke() }, enabled = onPrevArticle != null, modifier = Modifier.size(48.dp)) {
                    Text("⏮", color = Color.Black)
                }
                IconButton(onClick = onDecreaseFontSize, enabled = canDecreaseFontSize, modifier = Modifier.size(48.dp)) {
                    Text("A↓", color = Color.Black)
                }
                IconButton(onClick = onIncreaseFontSize, enabled = canIncreaseFontSize, modifier = Modifier.size(48.dp)) {
                    Text("A↑", color = Color.Black)
                }
                if (onFormattingClick != null) {
                    Box(
                        modifier = Modifier.size(48.dp)
                            .testTag("reader_formatting_open")
                            .semantics { contentDescription = "Reading format" }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onFormattingClick,
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Text("Aa", color = Color.Black, fontSize = 18.sp) }
                }
                IconButton(onClick = { onNextArticle?.invoke() }, enabled = onNextArticle != null, modifier = Modifier.size(48.dp)) {
                    Text("⏭", color = Color.Black)
                }
            }
        }
        if (lastSyncTime != null && lastSyncTime > 0L) {
            val elapsed = (System.currentTimeMillis() - lastSyncTime) / 1000
            val timeText = when {
                elapsed < 60 -> stringResource(R.string.last_sync_just_now)
                elapsed < 3600 -> stringResource(R.string.last_sync_min_ago, elapsed / 60)
                elapsed < 86400 -> stringResource(R.string.last_sync_hours_ago, elapsed / 3600)
                else -> stringResource(R.string.last_sync_days_ago, elapsed / 86400)
            }
            Text(
                text = stringResource(R.string.last_sync_prefix, timeText),
                fontSize = 11.sp,
                color = Color.DarkGray,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
// Pipeline v2 — channel-aware notifications

package me.ash.reader.ui.page.home.reading

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.preference.EInkFontSizePreference
import me.ash.reader.infrastructure.preference.LocalEInkFontSize
import me.ash.reader.infrastructure.preference.LocalReadingSubheadUpperCase
import me.ash.reader.infrastructure.preference.LocalReadingTextFontSize
import me.ash.reader.ui.component.reader.LocalTextContentWidth
import me.ash.reader.ui.component.reader.Reader
import me.ash.reader.ui.ext.roundClick
import me.ash.reader.ui.page.home.flow.EInkPaginationBar

@Composable
fun EInkPaginatedContent(
    modifier: Modifier = Modifier,
    content: String,
    feedName: String,
    title: String,
    author: String? = null,
    link: String? = null,
    publishedDate: Date,
    contentPadding: PaddingValues = PaddingValues(),
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    onPageChanged: ((currentPage: Int, totalPages: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val subheadUpperCase = LocalReadingSubheadUpperCase.current
    val textContentWidth = LocalTextContentWidth.current
    val maxWidthModifier = Modifier.widthIn(max = textContentWidth)
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()

    val einkFontSize = LocalEInkFontSize.current
    val fontSizeIndex = EInkFontSizePreference.values.indexOf(einkFontSize)
        .takeIf { it >= 0 } ?: EInkFontSizePreference.values.indexOf(EInkFontSizePreference.default)

    var currentPage by rememberSaveable(content, einkFontSize) { mutableIntStateOf(1) }
    var totalPages by rememberSaveable(content, einkFontSize) { mutableIntStateOf(1) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var showTapHints by remember { mutableStateOf(true) }

    val listState = rememberSaveable(content, einkFontSize, saver = LazyListState.Saver) {
        LazyListState()
    }

    // Hide tap zone hints after 2 seconds
    LaunchedEffect(Unit) {
        delay(2000)
        showTapHints = false
    }

    // Listen for volume key events (Vol Down = next, Vol Up = prev)
    LaunchedEffect(Unit) {
        VolumeKeyEventBus.events.collect { event ->
            when (event) {
                VolumeKeyEvent.VOLUME_DOWN -> {
                    if (listState.canScrollForward && viewportHeight > 0) {
                        listState.scroll { scrollBy(viewportHeight.toFloat()) }
                        currentPage++
                        if (!listState.canScrollForward) totalPages = currentPage
                    }
                }
                VolumeKeyEvent.VOLUME_UP -> {
                    if (currentPage > 1 && viewportHeight > 0) {
                        listState.scroll { scrollBy(-viewportHeight.toFloat()) }
                        currentPage--
                    }
                }
            }
        }
    }

    // Track page position from layout info
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.layoutInfo.totalItemsCount,
                listState.layoutInfo.visibleItemsInfo,
                listState.layoutInfo.viewportEndOffset,
            )
        }.collect { (totalItems, visibleItems, vpEnd) ->
            if (totalItems > 0 && visibleItems.isNotEmpty() && vpEnd > 0) {
                viewportHeight = vpEnd
                val avgItemHeight = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
                val estimatedTotal = (avgItemHeight * totalItems).toInt()
                val estimated = max(1, ceil(estimatedTotal.toFloat() / vpEnd).toInt())

                if (!listState.canScrollForward) {
                    totalPages = currentPage
                } else {
                    totalPages = max(totalPages, estimated)
                }

                onPageChanged?.invoke(currentPage, totalPages)
            }
        }
    }

    fun nextPage() {
        if (!listState.canScrollForward || viewportHeight <= 0) return
        coroutineScope.launch {
            listState.scroll { scrollBy(viewportHeight.toFloat()) }
            currentPage++
            if (!listState.canScrollForward) totalPages = currentPage
        }
    }

    fun prevPage() {
        if (currentPage <= 1 || viewportHeight <= 0) return
        coroutineScope.launch {
            listState.scroll { scrollBy(-viewportHeight.toFloat()) }
            currentPage--
        }
    }

    fun decreaseFontSize() {
        if (fontSizeIndex > 0) {
            EInkFontSizePreference.put(context, coroutineScope, EInkFontSizePreference.values[fontSizeIndex - 1])
        }
    }

    fun increaseFontSize() {
        if (fontSizeIndex < EInkFontSizePreference.values.lastIndex) {
            EInkFontSizePreference.put(context, coroutineScope, EInkFontSizePreference.values[fontSizeIndex + 1])
        }
    }

    CompositionLocalProvider(LocalReadingTextFontSize provides einkFontSize) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Main content area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            userScrollEnabled = false,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            item {
                                Spacer(modifier = Modifier.height(64.dp))
                                Spacer(modifier = Modifier.height(contentPadding.calculateTopPadding()))
                                Column(
                                    modifier = Modifier.then(maxWidthModifier)
                                        .padding(horizontal = 12.dp)
                                ) {
                                    DisableSelection {
                                        Metadata(
                                            feedName = feedName,
                                            title = title,
                                            author = author,
                                            publishedDate = publishedDate,
                                            modifier = Modifier.roundClick {
                                                link?.let { uriHandler.openUri(it) }
                                            },
                                        )
                                    }
                                }
                            }

                            Reader(
                                context = context,
                                subheadUpperCase = subheadUpperCase.value,
                                link = link ?: "",
                                content = content,
                                onImageClick = onImageClick,
                                onLinkClick = { uriHandler.openUri(it) },
                            )

                            item {
                                Spacer(modifier = Modifier.height(128.dp))
                                Spacer(
                                    modifier = Modifier.height(contentPadding.calculateBottomPadding())
                                )
                            }
                        }
                    }

                    // Tap zones overlay
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left 40% - previous page
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(0.4f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { prevPage() },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Tap zone hint
                            AnimatedVisibility(
                                visible = showTapHints,
                                exit = fadeOut(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "< Prev",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Black.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                        // Right 60% - next page
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(0.6f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { nextPage() },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Tap zone hint
                            AnimatedVisibility(
                                visible = showTapHints,
                                exit = fadeOut(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.05f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "Next >",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Black.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }

                // Page indicator bar at bottom with font size controls
                EInkPaginationBar(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    onPrev = { prevPage() },
                    onNext = { nextPage() },
                    fontSize = einkFontSize,
                    canDecreaseFontSize = fontSizeIndex > 0,
                    canIncreaseFontSize = fontSizeIndex < EInkFontSizePreference.values.lastIndex,
                    onDecreaseFontSize = { decreaseFontSize() },
                    onIncreaseFontSize = { increaseFontSize() },
                )
            }
        }
    }
}

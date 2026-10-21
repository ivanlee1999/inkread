package me.ash.reader.ui.page.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.FlowArticleListImagePreference
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalReadingColumns
import me.ash.reader.infrastructure.preference.LocalReadingLayoutPreset
import me.ash.reader.infrastructure.preference.ReadingColumnsPreference
import me.ash.reader.infrastructure.preference.ReadingLayoutPresetPreference
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.layout.ReadingLayoutMetrics
import me.ash.reader.ui.layout.resolveReadingLayout
import me.ash.reader.ui.page.home.flow.ArticleListPaneLayout
import me.ash.reader.ui.page.home.flow.EInkArticleListHeader
import me.ash.reader.ui.page.home.flow.EInkNewspaperViewport
import me.ash.reader.ui.page.home.flow.NewspaperViewportState
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Date

/**
 * Uses the same constraints-to-layout composition boundary as FlowPage, not a test-only layout
 * resolver. Record every composition/draw, not just the settled semantics tree: a zero-width
 * fallback can look correct by waitForIdle() while already flashing a different printed page.
 */
class FirstFrameArticleLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readerReturnNeverDrawsFallbackColumnsOrMovesTheRetainedEdition() {
        val state = NewspaperViewportState()
        val pager = Any()
        val source = articles()
        state.acceptSnapshot(pager, source)
        val readerVisible = mutableStateOf(false)
        val compositions = mutableListOf<Int>()
        val frames = mutableListOf<EditionFrame>()
        val marked = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(
                LocalEInkMode provides EInkModePreference.ON,
                LocalReadingColumns provides ReadingColumnsPreference.Auto,
                LocalReadingLayoutPreset provides ReadingLayoutPresetPreference.Auto,
                LocalFlowArticleListImage provides FlowArticleListImagePreference.OFF,
            ) {
                AppTheme(useDarkTheme = false) {
                    if (readerVisible.value) {
                        Box(Modifier.fillMaxSize().testTag("reader"))
                    } else {
                        RYScaffold(reserveBottomBarSpace = true, topBar = {
                            EInkArticleListHeader(
                                title = "The Daily Edition", filter = Filter.Unread,
                                currentPage = state.currentPage + 1, totalPages = state.pages.size,
                                totalArticles = source.size, isSyncing = false,
                                onNavigateUp = {}, onFilterChange = {}, onRefresh = {},
                                onMarkAllAsRead = {}, onSearch = {}, onPrev = {}, onNext = {},
                            )
                        }) {
                            ArticleListPaneLayout { layout ->
                                SideEffect { compositions += layout.columns }
                                EInkNewspaperViewport(
                                    state = state, columns = layout.columns,
                                    horizontalPadding = layout.horizontalPaddingDp.dp,
                                    columnSpacing = layout.columnSpacingDp.dp,
                                    modifier = Modifier.drawWithContent {
                                        drawContent()
                                        frames += EditionFrame(layout.columns, size.width / density,
                                            state.currentPage, state.displayedArticles.map { it.article.id })
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Fixture must exercise a multi-page newspaper", state.pages.size > 2)
            assertTrue("The native pane must support a newspaper rather than a one-column fallback",
                frames.isNotEmpty() && frames.all { it.columns >= 2 })
            assertEquals(resolveReadingLayout(frames.first().widthDp).columns, compositions.first())
        }

        repeat(3) {
            compose.runOnIdle {
                assertTrue(state.turn(true, false) { outgoing -> marked += outgoing.map { it.article.id } })
            }
            compose.waitForIdle()
            val before = compose.runOnIdle { frames.last() }
            val pagesBefore = compose.runOnIdle { state.pages.toList() }
            compose.runOnIdle { readerVisible.value = true }
            compose.onNodeWithTag("reader").assertIsDisplayed()
            compose.runOnIdle {
                // Room may remove every opened unread story while the reader is showing.
                before.articleIds.forEach { state.recordReadStatus(it, false) }
                state.acceptSnapshot(pager, source.filterNot {
                    it is ArticleFlowItem.Article && it.articleWithFeed.article.id in before.articleIds
                })
                compositions.clear()
                frames.clear()
                readerVisible.value = false
            }
            compose.onNodeWithTag("newspaper-viewport").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue("The return paints content immediately, not a gated empty frame", frames.isNotEmpty())
                assertTrue("No initial single-column composition is allowed", compositions.all { it == before.columns })
                assertTrue("Every return frame retains exact columns, page number and article IDs",
                    frames.all { it == before })
                assertEquals("No transient geometry may repack the retained edition", pagesBefore, state.pages)
                assertFalse("Reader-return mutations must not mark a page as read", marked.isNotEmpty())
            }
        }
    }

    @Test fun resizeAndExplicitPreferencesApplyOnTheFirstComposedFrame() {
        val width = mutableStateOf(800.dp)
        val scale = mutableStateOf(1f)
        val preference = mutableStateOf<ReadingColumnsPreference>(ReadingColumnsPreference.Auto)
        val preset = mutableStateOf<ReadingLayoutPresetPreference>(ReadingLayoutPresetPreference.Auto)
        val visible = mutableStateOf(true)
        val compositions = mutableListOf<ReadingLayoutMetrics>()
        val measurements = mutableListOf<ReadingLayoutMetrics>()
        val draws = mutableListOf<ReadingLayoutMetrics>()
        val phaseReports = mutableListOf<String>()
        var invalidComposition = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, scale.value),
                LocalReadingColumns provides preference.value,
                LocalReadingLayoutPreset provides preset.value,
                // Turning E-Ink off must not silently reset the user's layout settings.
                LocalEInkMode provides EInkModePreference.OFF,
            ) {
                if (visible.value) {
                    ArticleListPaneLayout(Modifier.requiredWidth(width.value)) { metrics ->
                        SideEffect { compositions += metrics }
                        Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                            measurements += metrics
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                        }.drawWithContent { drawContent(); draws += metrics }) {
                            Text("${metrics.columns} columns")
                        }
                    }
                }
            }
        }
        fun assertEveryComposition(label: String) {
            compose.waitForIdle()
            compose.runOnIdle {
                val expected = resolveReadingLayout(width.value.value, scale.value,
                    preset.value.preset, preference.value.columns)
                assertTrue("The layout must compose without a blank-width waiting frame", compositions.isNotEmpty())
                phaseReports += "$label: expected=$expected; measured=$measurements; drawn=$draws; composed=$compositions"
                invalidComposition = invalidComposition || compositions.any { it != expected }
                // Keep rendering failures immediate. Defer only the composition diagnostic so one
                // native run reveals whether old-slot restarts ever affect any real resize frame.
                assertTrue("The actual pane must be measured without stale geometry: ${phaseReports.joinToString("\n")}",
                    measurements.isNotEmpty() && measurements.all { it == expected })
                assertTrue("Every visible resize frame must use the actual geometry: ${phaseReports.joinToString("\n")}",
                    draws.isNotEmpty() && draws.all { it == expected })
            }
        }
        assertEveryComposition("initial")
        val scenarios = listOf(
            Triple(340.dp, 1f, ReadingColumnsPreference.Auto),
            Triple(800.dp, 1f, ReadingColumnsPreference.One),
            Triple(800.dp, 1f, ReadingColumnsPreference.Two),
            Triple(320.dp, 2f, ReadingColumnsPreference.Two),
        )
        scenarios.forEachIndexed { index, (nextWidth, nextScale, nextPreference) ->
            compose.runOnIdle {
                compositions.clear()
                measurements.clear()
                draws.clear()
                Snapshot.withMutableSnapshot {
                    width.value = nextWidth
                    scale.value = nextScale
                    preference.value = nextPreference
                }
            }
            assertEveryComposition("resize-$index")
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle {
                compositions.clear(); measurements.clear(); draws.clear()
                visible.value = true
            }
            assertEveryComposition("remount-$index")
        }
        compose.runOnIdle {
            compositions.clear()
            measurements.clear()
            draws.clear()
            Snapshot.withMutableSnapshot {
                width.value = 800.dp
                scale.value = 1f
                preference.value = ReadingColumnsPreference.Auto
                preset.value = ReadingLayoutPresetPreference.Phone
            }
        }
        assertEveryComposition("phone-preset")
        compose.runOnIdle {
            assertTrue("Only the actual pane/settings may reach article composition: ${phaseReports.joinToString("\n")}",
                !invalidComposition)
        }
    }

    @Test fun firstFrameUsesScaffoldContentInsetsNotTheWholeDisplayWidth() {
        val layouts = mutableListOf<ReadingLayoutMetrics>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                RYScaffold(modifier = Modifier.requiredWidth(400.dp),
                    contentWindowInsets = WindowInsets(left = 60, right = 60)) {
                    ArticleListPaneLayout { layout ->
                        SideEffect { layouts += layout }
                        Text("Inset content")
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(layouts.isNotEmpty())
            assertTrue("400dp display with 120dp side insets has a 280dp, one-column content pane",
                layouts.all { it == resolveReadingLayout(280f) })
        }
    }

    private data class EditionFrame(val columns: Int, val widthDp: Float, val page: Int,
        val articleIds: List<String>)

    private fun articles(): List<ArticleFlowItem> {
        val feed = Feed(id = "first-frame-feed", name = "The Daily Edition", url = "https://example.com/feed",
            groupId = "first-frame-group", accountId = 1)
        return (0 until 60).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "first-frame-$index", date = Date((60 - index).toLong()),
                title = "A complete newspaper story number $index", rawDescription = "",
                shortDescription = "Reporting and analysis remain on the same printed page when the reader closes. ".repeat(3),
                link = "about:blank", feedId = feed.id, accountId = 1, isUnread = true,
            ).apply { dateString = "09:30" }, feed))
        }
    }
}

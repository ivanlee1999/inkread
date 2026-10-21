package me.ash.reader.ui.page.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.ui.component.FilterBar
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.NewspaperMasthead
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.page.home.feeds.*
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real production folder bodies, measured inside real scaffold/filter-bar safe bounds. */
class ViewportFolderUiTest {
    @get:Rule val compose = createComposeRule()

    private fun sections(count: Int = 18): List<FolderSection> {
        val titles = listOf("World & Society", "Science and the Natural World", "Design & Culture",
            "Independent voices", "世界新闻与文化", "Books and the long view")
        val publications = listOf("The Morning Journal", "Observatory", "Nature Notebook",
            "A very long independent publication title", "世界新闻", "Field Notes", "The Review")
        return (0 until count).map { index ->
            FolderSection("g$index", "${titles[index % titles.size]} ${index + 1}",
                publications.mapIndexed { feed, name ->
                    FolderPublication("g${index}f$feed", name, "source${feed + 1}.example", count = index + feed)
                })
        }
    }

    @Test fun everyCollapsedSectionFitsAndEveryPageHasAnExactSourcePartition() {
        val data = sections()
        val state = FolderNewspaperState()
        val opened = mutableListOf<String>()
        val options = mutableListOf<String>()
        compose.setContent {
            Fixture(data, state, onOpenFolder = { opened += it }, onFolderOptions = { options += it })
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.pageCount > 1) }
        capture("folders-first")
        compose.onNodeWithTag("folder-open:${data.first().name}").performClick().performClick()
        assertEquals(listOf("g0", "g0"), opened)
        compose.onNodeWithTag("folder-open:${data.first().name}").performTouchInput { longClick() }
        assertEquals(listOf("g0"), options)

        val seen = mutableListOf<String>()
        var count = 0
        do {
            assertCompleteRows(state)
            compose.runOnIdle { seen += state.pages[state.currentPage].keys; count = state.pageCount }
            if (state.currentPage == count / 2) capture("folders-middle")
            if (state.currentPage + 1 == count) break
            compose.onNodeWithTag("folder-next-page").performClick()
            compose.waitForIdle()
        } while (true)
        assertEquals(data.map { "folder:${it.id}" }, seen)
        assertEquals(seen.size, seen.distinct().size)
        compose.onNodeWithTag("folder-next-page").assertIsNotEnabled()
        capture("folders-last")
        while (state.currentPage > 0) {
            compose.onNodeWithTag("folder-previous-page").performClick()
            compose.waitForIdle()
            assertCompleteRows(state)
        }
        compose.onNodeWithTag("folder-previous-page").assertIsNotEnabled()
    }

    @Test fun expandCollapsePreservesTappedFolderAndPublicationActions() {
        val data = sections()
        val state = FolderNewspaperState()
        var expanded by mutableStateOf(emptySet<String>())
        val openedFolders = mutableListOf<String>()
        val openedFeeds = mutableListOf<String>()
        val options = mutableListOf<String>()
        compose.setContent {
            Fixture(data, state, expanded,
                onToggleFolder = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
                onOpenFolder = { openedFolders += it },
                onOpenPublication = { openedFeeds += it }, onPublicationOptions = { options += it })
        }
        compose.onNodeWithTag("folder-next-page").performClick()
        compose.waitForIdle()
        val id = state.pages[state.currentPage].rows.first().row.sectionIds.first()
        val target = data.first { it.id == id }
        compose.onNodeWithTag("folder-expand:${target.name}").performClick()
        compose.waitForIdle()
        assertTrue("Disclosure does not navigate", openedFolders.isEmpty())
        assertTrue("Tapped section remains visible", "folder:$id" in state.pages[state.currentPage].keys)
        assertCompleteRows(state)
        capture("folders-expanded")
        val feed = target.publications.first()
        compose.onNodeWithTag("folder-publication:${feed.id}").performClick().performClick()
        assertEquals(listOf(feed.id, feed.id), openedFeeds)
        compose.onNodeWithTag("folder-publication:${feed.id}").performTouchInput { longClick() }
        assertEquals(listOf(feed.id), options)
        compose.onNodeWithTag("folder-expand:${target.name}").performClick()
        compose.waitForIdle()
        assertTrue("Collapsed section remains on screen", "folder:$id" in state.pages[state.currentPage].keys)
        assertCompleteRows(state)
        compose.onNodeWithTag("folder-open:${target.name}").performClick()
        assertEquals(listOf(id), openedFolders)
    }

    @Test fun folderDisclosureRemainsASeparateAccessibleActionAtLargeType() {
        val data = sections(1)
        val section = data.single()
        val state = FolderNewspaperState()
        var scale by mutableFloatStateOf(1.6f)
        var expanded by mutableStateOf(emptySet<String>())
        val opened = mutableListOf<String>()
        val options = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Fixture(data, state, expanded,
                    onOpenFolder = { opened += it }, onFolderOptions = { options += it },
                    onToggleFolder = { id -> expanded = if (id in expanded) expanded - id else expanded + id })
            }
        }
        for (font in listOf(1.6f, 2f)) {
            compose.runOnIdle { scale = font }
            compose.waitForIdle()
            val open = compose.onNodeWithTag("folder-open:${section.name}")
            val disclosure = compose.onNodeWithTag("folder-expand:${section.name}")
            open.assertHasClickAction()
            disclosure.assertHasClickAction()
            val action = disclosure.getUnclippedBoundsInRoot()
            val label = open.getUnclippedBoundsInRoot()
            assertTrue("Disclosure retains its own 48dp accessible touch target", action.right - action.left >= 48.dp && action.bottom - action.top >= 48.dp)
            assertTrue("Opening and expanding never overlap", action.left >= label.right || action.right <= label.left || action.top >= label.bottom || action.bottom <= label.top)
            assertTrue("Disclosure has an accessible description", disclosure.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].any { it.isNotBlank() })
            disclosure.performClick()
            compose.waitForIdle()
            assertTrue("Expand does not accidentally open the folder", opened.isEmpty())
            assertTrue("Expand does not accidentally open options", options.isEmpty())
            assertTrue(section.id in expanded)
            disclosure.performClick()
            compose.waitForIdle()
            assertTrue(expanded.isEmpty())
            assertCompleteRows(state)
        }
        compose.onNodeWithTag("folder-open:${section.name}").performClick()
        compose.onNodeWithTag("folder-open:${section.name}").performTouchInput { longClick() }
        assertEquals(listOf(section.id), opened)
        assertEquals(listOf(section.id), options)
    }

    @Test fun expandedMultipageEditionHasNoLostFeedsAndReflowsAroundItsAnchor() {
        val data = sections(10)
        val state = FolderNewspaperState()
        var scale by mutableFloatStateOf(1f)
        var columns by mutableStateOf<ReadingColumnsPreference>(ReadingColumnsPreference.Auto)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, scale),
                LocalReadingColumns provides columns, LocalLayoutDirection provides direction) {
                Fixture(data, state, data.map { it.id }.toSet())
            }
        }
        compose.waitForIdle()
        val expected = data.flatMap { listOf("folder:${it.id}") + it.publications.map { feed -> "feed:${feed.id}" } }
        assertEquals(expected, state.pages.flatMap { it.keys })
        compose.onNodeWithTag("folder-next-page").performClick()
        compose.waitForIdle()
        val anchor = state.pages[state.currentPage].keys.first()
        compose.runOnIdle { columns = ReadingColumnsPreference.One; scale = 1.6f }
        compose.waitForIdle()
        assertTrue(anchor in state.pages[state.currentPage].keys)
        assertEquals(expected, state.pages.flatMap { it.keys })
        assertCompleteRows(state)
        capture("folders-large-font")
        compose.runOnIdle { columns = ReadingColumnsPreference.Two; scale = 1f; direction = LayoutDirection.Rtl }
        compose.waitForIdle()
        assertTrue(anchor in state.pages[state.currentPage].keys)
        assertCompleteRows(state)
        capture("folders-rtl")
        val seen = mutableListOf<String>()
        compose.runOnIdle { state.firstPage() }
        compose.waitForIdle()
        while (true) {
            assertCompleteRows(state)
            seen += state.pages[state.currentPage].keys
            if (state.currentPage + 1 == state.pageCount) break
            compose.onNodeWithTag("folder-viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1f) }
            compose.waitForIdle()
        }
        assertEquals(expected, seen)
    }

    @Test fun successiveReflowsAndSavingKeepTheLogicalSourceAnchorUntilAnExplicitTurn() {
        val data = sections(4)
        fun pages(vararg groups: List<Int>) = groups.map { ids ->
            FolderNewspaperPage(ids.map { index ->
                MeasuredFolderRow(FolderNewspaperRow(listOf(FolderNewspaperSlot(data[index]))), 100)
            })
        }
        var state = FolderNewspaperState()
        state.updatePages(pages(listOf(0, 1), listOf(2, 3)), "initial")
        assertTrue(state.turn(true))
        val anchor = state.pages[state.currentPage].keys.first()
        assertEquals("folder:g2", anchor)
        state.updatePages(pages(listOf(0), listOf(1, 2, 3)), "large-font-one-column")
        assertTrue("Reflow may place an earlier source before the stable anchor", anchor in state.pages[state.currentPage].keys)
        assertEquals("folder:g1", state.pages[state.currentPage].keys.first())
        val saveScope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val saved = with(FolderNewspaperState.Saver) { saveScope.save(state) }
        state = requireNotNull(FolderNewspaperState.Saver.restore(requireNotNull(saved)))
        state.updatePages(pages(listOf(0, 1), listOf(2, 3)), "normal-font-two-columns-rtl")
        assertTrue("A second reflow must not drift to the incidental first key of the intervening page", anchor in state.pages[state.currentPage].keys)
        assertTrue(state.turn(false))
        state.updatePages(pages(listOf(0), listOf(1, 2, 3)), "another-resize")
        assertEquals("An explicit turn establishes a new logical source anchor", listOf("folder:g0"), state.pages[state.currentPage].keys)
    }

    @Test fun shortAndLongFolderDigestsKeepWholeSourcesAtEveryFontSize() {
        val data = sections(15).mapIndexed { index, section ->
            val names = listOf("Brief", "A longer publication covering science and the natural world",
                "世界新闻与城市文化观察", "Field Notes", "Books, design, and independent voices")
            section.copy(publications = (0 until listOf(0, 1, 3, 7, 12)[index % 5]).map { feed ->
                FolderPublication("${section.id}f$feed", names[feed % names.size], "publication${feed + 1}.example", count = feed)
            })
        }
        val state = FolderNewspaperState()
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, scale)) {
                Fixture(data, state)
            }
        }
        val expected = data.map { "folder:${it.id}" }
        for (font in listOf(1f, 1.6f, 2f)) {
            compose.runOnIdle { scale = font }
            compose.waitForIdle()
            compose.runOnIdle { state.firstPage() }
            compose.waitForIdle()
            val count = state.pageCount
            val seen = mutableListOf<String>()
            repeat(count) { page ->
                assertCompleteRows(state)
                seen += state.pages[state.currentPage].keys
                listOf("first" to 0, "middle" to count / 2, "last" to count - 1).filter { it.second == page }.forEach { (position, _) ->
                    capture("density-folders-${(font * 100).toInt()}-$position")
                }
                if (page < count - 1) {
                    compose.onNodeWithTag("folder-next-page").performClick()
                    compose.waitForIdle()
                }
            }
            assertEquals("Actual folder sources remain whole and ordered at font=$font", expected, seen)
            assertEquals("A digest never duplicates its folder source", seen.size, seen.distinct().size)
        }
    }

    @Test fun availableSpaceShowsAdditionalRealPublicationNamesRatherThanLargerCards() {
        val data = sections(1)
        val state = FolderNewspaperState()
        compose.setContent { Fixture(data, state) }
        compose.waitForIdle()
        assertCompleteRows(state)
        val digests = compose.onAllNodes(SemanticsMatcher("real publication digest") { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("folder-digest:g0:")
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("The same seven-publication folder uses spare space for more than the old three-name featured digest", digests.size > 3)
        val names = digests.flatMap { it.config[SemanticsProperties.Text] }.map { it.text }
        assertEquals("Additional digest lines are an ordered prefix of actual subscriptions", data.single().publications.take(names.size).map { it.name }, names)
        assertEquals("Digest detail does not manufacture extra source blocks", listOf(listOf("folder:g0")), state.pages.map { it.keys })
        capture("density-long-folder")
    }

    @Test fun singleShortFolderKeepsHonestWhitespaceInsteadOfInventingPublications() {
        val data = listOf(FolderSection("small", "Reading", listOf(FolderPublication("one", "Field Notes", "notes.example", count = 1))))
        val state = FolderNewspaperState()
        compose.setContent { Fixture(data, state) }
        compose.waitForIdle()
        assertCompleteRows(state)
        assertEquals(listOf(listOf("folder:small")), state.pages.map { it.keys })
        val block = compose.onNodeWithTag("folder-block:small", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("folder-viewport").getUnclippedBoundsInRoot()
        assertTrue("Scant source content remains naturally sized, not stretched to fake density", block.bottom - block.top < (viewport.bottom - viewport.top) * 0.5f)
        compose.onAllNodesWithText("Field Notes", useUnmergedTree = true).assertCountEquals(1)
        capture("density-sparse-folder")
    }

    @Test fun swipeBoundariesAndSavedStateKeepTheSameCompletePage() {
        val data = sections()
        var current: FolderNewspaperState? = null
        var refreshes = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val state = rememberSaveable(saver = FolderNewspaperState.Saver) { FolderNewspaperState() }
            SideEffect { current = state }
            Fixture(data, state, onRefresh = { refreshes++ })
        }
        compose.onNodeWithTag("folder-viewport").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(1, current!!.currentPage)
        val anchor = current!!.pages[current!!.currentPage].keys.first()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue(anchor in current!!.pages[current!!.currentPage].keys)
        assertCompleteRows(current!!)
        compose.onNodeWithTag("folder-viewport").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, current!!.currentPage)
        compose.onNodeWithTag("folder-viewport").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, refreshes)
        compose.onNodeWithTag("folder-viewport").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, current!!.currentPage)
        assertCompleteRows(current!!)
    }

    private fun assertCompleteRows(state: FolderNewspaperState) {
        compose.waitForIdle()
        assertTrue("A realistic screen has room for complete blocks", state.presentationAvailable)
        val viewport = compose.onNodeWithTag("folder-viewport").getUnclippedBoundsInRoot()
        val rows = state.pages[state.currentPage].rows
        assertTrue(rows.isNotEmpty())
        val viewportPx = compose.onNodeWithTag("folder-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Measured content, not allocated rectangles, fits the viewport", state.pages[state.currentPage].heightPx <= viewportPx.height + 1f)
        rows.forEach { row ->
            val bounds = compose.onNodeWithTag("folder-row:${row.row.key}", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("No top clipping: $bounds in $viewport", bounds.top >= viewport.top - 1.dp)
            assertTrue("Complete final row: $bounds in $viewport", bounds.bottom <= viewport.bottom + 1.dp)
            assertTrue(bounds.left >= viewport.left - 1.dp && bounds.right <= viewport.right + 1.dp)
            val text = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
                hasAnyAncestor(hasTestTag("folder-row:${row.row.key}")), useUnmergedTree = true)
            text.fetchSemanticsNodes().indices.forEach { index ->
                val child = text[index].getUnclippedBoundsInRoot()
                assertTrue("A title, count, or real publication name must not cross its whole row: $child in $bounds",
                    child.top >= bounds.top - 1.dp && child.bottom <= bounds.bottom + 1.dp &&
                        child.left >= bounds.left - 1.dp && child.right <= bounds.right + 1.dp)
            }
        }
        val bar = compose.onNodeWithTag("folder-fixture-filter").getUnclippedBoundsInRoot()
        assertTrue("Body avoids the filter bar", viewport.bottom <= bar.top + 1.dp)
        val actualKeys = compose.onAllNodes(SemanticsMatcher("folder or publication source") { node ->
            val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
            tag.startsWith("folder-block:") || tag.startsWith("folder-publication:")
        }, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            val tag = node.config[SemanticsProperties.TestTag]
            if (tag.startsWith("folder-block:")) "folder:${tag.substringAfter(':')}"
            else "feed:${tag.substringAfter(':')}"
        }
        assertEquals("Only the measured page is placed", state.pages[state.currentPage].keys, actualKeys)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyAncestor(hasTestTag("folder-viewport")), useUnmergedTree = true).assertCountEquals(0)
        val scroll = compose.onNodeWithTag("folder-viewport").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle {
            assertEquals("Accessibility scroll remains a whole page, never a partial pixel offset", state.currentPage.toFloat(), scroll.value(), 0f)
            assertEquals((state.pageCount - 1).toFloat(), scroll.maxValue(), 0f)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Fixture(
        data: List<FolderSection>, state: FolderNewspaperState, expanded: Set<String> = emptySet(),
        onOpenFolder: (String) -> Unit = {}, onToggleFolder: (String) -> Unit = {},
        onFolderOptions: (String) -> Unit = {}, onOpenPublication: (String) -> Unit = {},
        onPublicationOptions: (String) -> Unit = {}, onRefresh: () -> Unit = {},
    ) {
        CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
            AppTheme(useDarkTheme = false) {
                RYScaffold(reserveBottomBarSpace = true,
                    topBar = { TopAppBar(title = {},
                        navigationIcon = { FeedbackIconButton(imageVector = Icons.Outlined.Settings, contentDescription = "Settings") },
                        actions = { FeedbackIconButton(imageVector = Icons.Rounded.Add, contentDescription = "Subscribe") }) },
                    bottomBar = {
                        FilterBar(Modifier.testTag("folder-fixture-filter"), Filter.Unread,
                            FeedsFilterBarStylePreference.Icon.value, true, 12.dp, 0.dp, filterOnClick = {})
                    }) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        NewspaperMasthead("My subscriptions", "Folder: ${data.size}   Feeds: ${data.sumOf { it.publications.size }}")
                        FeedsBanner(filter = Filter.Unread, desc = "${data.sumOf { it.count }} unread items", compact = true)
                        FolderNewspaperEdition(data, expanded, "Unread", Modifier.weight(1f), state,
                            onOpenFolder = onOpenFolder, onToggleFolder = onToggleFolder,
                            onFolderOptions = onFolderOptions, onOpenPublication = onOpenPublication,
                            onPublicationOptions = onPublicationOptions, onRefresh = onRefresh)
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        NativePageDensityCapture.capture(compose, name, "folder-viewport")
    }
}

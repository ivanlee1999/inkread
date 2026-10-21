package me.ash.reader.ui.page.home

import android.graphics.Bitmap
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
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.File

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
        rows.forEach { row ->
            val bounds = compose.onNodeWithTag("folder-row:${row.row.key}", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("No top clipping: $bounds in $viewport", bounds.top >= viewport.top - 1.dp)
            assertTrue("Complete final row: $bounds in $viewport", bounds.bottom <= viewport.bottom + 1.dp)
            assertTrue(bounds.left >= viewport.left - 1.dp && bounds.right <= viewport.right + 1.dp)
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
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = if (output.isNullOrBlank()) File(context.getExternalFilesDir(null), "newspaper-screenshots") else File(output)
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "newspaper-$name-${context.resources.configuration.screenWidthDp}.png")
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(file.length() > 0)
    }
}

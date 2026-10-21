package me.ash.reader.ui.page.home

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.ui.page.home.feeds.accounts.AccountsTab
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.page.home.feeds.PublicationBlock
import me.ash.reader.ui.page.home.reading.Metadata
import me.ash.reader.ui.page.settings.SelectableSettingGroupItem
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.page.settings.color.ColorAndStylePage
import me.ash.reader.ui.page.settings.color.DarkThemePage
import me.ash.reader.ui.page.settings.color.ReadingLayoutPresetSetting
import me.ash.reader.ui.page.settings.color.feeds.FeedsPageStylePage
import me.ash.reader.ui.page.settings.color.flow.FlowPageStylePage
import me.ash.reader.ui.page.settings.color.reading.*
import me.ash.reader.ui.page.settings.interaction.InteractionPage
import me.ash.reader.ui.page.settings.languages.LanguagesPage
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Date

/**
 * Native production settings routes and shared reader/dialog components. This deliberately does
 * not call a mocked settings home an app screen: SettingsPage, account and subscription sheets
 * still require their Hilt/navigation hosts and are recorded separately in the UI audit.
 */
class EditorialChromeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun productionSettingsRoutesKeepBackAndAppearanceNavigationReachable() {
        var route by mutableStateOf("appearance")
        var scale by mutableFloatStateOf(1f)
        val backs = mutableListOf<String>()
        val navigations = mutableListOf<String>()
        compose.setContent {
            Theme(scale) {
                key(route, scale) {
                    SettingsRoute(route, onBack = { backs += route }, onNavigate = { navigations += it })
                }
            }
        }
        val routes = listOf("appearance", "interaction", "feeds", "articles", "reading", "title", "text", "images", "video", "languages", "dark")
        for (name in routes) {
            compose.runOnIdle { route = name }
            compose.waitForIdle()
            pageBack().assertIsDisplayed().assertHasClickAction()
            assertTrue("$name renders actual production text", compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            if (name == "video") {
                for (setting in listOf(R.string.rounded_corners, R.string.horizontal_padding)) {
                    compose.onNodeWithText(context.getString(setting)).assertIsNotEnabled()
                }
            }
            if (name == "feeds") {
                val parent = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                val disclosure = hasTestTag("folder-expand:${context.getString(R.string.defaults)}")
                val sample = hasText(context.getString(R.string.preview_feed_name)) and hasAnyAncestor(hasTestTag("feeds-style-preview"))
                parent.performScrollToNode(disclosure)
                val initiallyExpanded = compose.onAllNodes(sample).fetchSemanticsNodes().isNotEmpty()
                compose.onNode(disclosure).performClick()
                if (initiallyExpanded) compose.onNode(sample).assertDoesNotExist() else compose.onNode(sample).assertIsDisplayed()
                compose.onNode(disclosure).performClick()
                if (initiallyExpanded) compose.onNode(sample).assertIsDisplayed() else compose.onNode(sample).assertDoesNotExist()
                for (filter in listOf(R.string.starred, R.string.unread)) {
                    val target = hasContentDescription(context.getString(filter)) and hasAnyAncestor(hasTestTag("feeds-style-preview"))
                    parent.performScrollToNode(target)
                    compose.onNode(target).assertIsDisplayed().performClick().assertIsSelected()
                }
                parent.performScrollToIndex(0)
            }
            capture("editorial-settings-$name")
            pageBack().performClick()
            assertEquals(name, backs.last())
        }
        compose.runOnIdle { route = "appearance" }
        compose.waitForIdle()
        // The removed decorative palette illustration was a no-op click target. The real palette
        // controls and all three screen-style destinations must remain in the actual page.
        compose.onAllNodesWithContentDescription(context.getString(R.string.color_and_style)).assertCountEquals(0)
        for ((id, destination) in listOf(R.string.feeds_page to "feeds", R.string.flow_page to "articles", R.string.reading_page to "reading")) {
            val target = hasText(context.getString(id))
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(target)
            compose.onNode(target).assertIsDisplayed().performClick()
            assertEquals(destination, navigations.last())
        }
        capture("editorial-settings-appearance-end")
        for (name in listOf("appearance", "interaction", "reading")) {
            compose.runOnIdle { route = name; scale = 1.6f }
            compose.waitForIdle()
            pageBack().assertIsDisplayed().performClick()
            assertEquals(name, backs.last())
            capture("editorial-settings-$name-large")
        }
    }

    @Test fun sharedSettingsRowsWrapLongLabelsAndPreserveDisabledActions() {
        val title = "Reading, typography and the appearance of long articles"
        val description = "Keep every label readable without hiding its meaning. 阅读设置与文章排版。"
        var scale by mutableFloatStateOf(1.6f)
        var dark by mutableStateOf(false)
        var settingClicks = 0
        var groupClicks = 0
        var disabledClicks = 0
        compose.setContent {
            Theme(scale, dark) {
                LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("settings-components")) {
                    item { DisplayText(text = "Reading settings · 阅读设置", desc = "A clear hierarchy for every page") }
                    item { SettingItem(Modifier.testTag("setting-row"), title = title, desc = description, icon = Icons.Outlined.Settings, onClick = { settingClicks++ }) }
                    item { SelectableSettingGroupItem(Modifier.testTag("setting-group"), title = "中文阅读与排版设置", desc = description, icon = Icons.Outlined.Settings, onClick = { groupClicks++ }) }
                    item { SettingItem(Modifier.testTag("disabled-setting"), enabled = false, title = "Unavailable setting", onClick = { disabledClicks++ }) }
                    item { SelectableSettingGroupItem(Modifier.testTag("disabled-group"), enable = false, title = "Unavailable group", onClick = { disabledClicks++ }) }
                }
            }
        }
        for (font in listOf(1.6f, 2f)) {
            compose.runOnIdle { scale = font }
            for (tag in listOf("setting-row", "setting-group")) {
                compose.onNodeWithTag("settings-components").performScrollToNode(hasTestTag(tag))
                val row = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
                assertCompleteText(tag)
                row.performClick()
            }
            for (tag in listOf("disabled-setting", "disabled-group")) {
                compose.onNodeWithTag("settings-components").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
            }
            assertEquals(0, disabledClicks)
            compose.onNodeWithTag("settings-components").performScrollToIndex(0)
            capture("editorial-settings-components-${(font * 100).toInt()}")
        }
        assertEquals(2, settingClicks)
        assertEquals(2, groupClicks)
        compose.runOnIdle { scale = 1.6f; dark = true }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-components").performScrollToNode(hasTestTag("setting-row"))
        compose.onNodeWithTag("setting-row").assertIsDisplayed()
        assertCompleteText("setting-row")
        capture("editorial-settings-components-dark")
    }

    @Test fun readerHeadlineLeadsOneDeduplicatedRealBylineAtEveryTextSize() {
        var author by mutableStateOf<String?>("Ada Chen")
        var scale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        compose.setContent {
            Theme(scale, dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("metadata-fixture")) {
                    Metadata(feedName = "The Journal", title = "A clearer view · 城市与世界", publishedDate = Date(0), author = author)
                }
            }
        }
        for (font in listOf(1f, 1.6f, 2f)) {
            compose.runOnIdle { scale = font }
            compose.waitForIdle()
            val title = compose.onNodeWithTag("reader-headline", useUnmergedTree = true)
            val byline = compose.onNodeWithTag("reader-byline", useUnmergedTree = true)
            title.assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            byline.assertIsDisplayed().assertTextContains("The Journal · Ada Chen", substring = true)
            assertTrue("Reader headline appears before attribution/date", byline.getUnclippedBoundsInRoot().top >= title.getUnclippedBoundsInRoot().bottom)
            val titleLayout = textLayout(title)
            val bylineLayout = textLayout(byline)
            assertTrue("Headline remains typographically dominant", titleLayout.layoutInput.style.fontSize.value > bylineLayout.layoutInput.style.fontSize.value)
            assertEquals(font, titleLayout.layoutInput.density.fontScale, 0f)
            assertFalse("Reader title is not clipped at accessibility scale", titleLayout.hasVisualOverflow)
            assertFalse("Reader metadata wraps rather than clipping", bylineLayout.hasVisualOverflow)
            capture("editorial-reader-metadata-${(font * 100).toInt()}")
        }
        for (value in listOf(" the journal ", " ", null)) {
            compose.runOnIdle { author = value }
            compose.waitForIdle()
            val text = textLayout(compose.onNodeWithTag("reader-byline", useUnmergedTree = true)).layoutInput.text.text
            assertEquals("Source appears exactly once, with no fabricated author", 1, Regex("The Journal", RegexOption.IGNORE_CASE).findAll(text).count())
            assertFalse(text.contains(" ·  · "))
        }
        compose.runOnIdle { author = "Ada Chen"; scale = 1.6f; dark = true }
        compose.waitForIdle()
        capture("editorial-reader-metadata-dark")
    }

    @Test fun realLayoutChooserRetainsAllChoicesAndBackNeverChangesSelection() {
        var scale by mutableFloatStateOf(1.6f)
        compose.setContent { Theme(scale) { ReadingLayoutPresetSetting() } }
        val title = context.getString(R.string.layout_preset)
        val host = compose.onNodeWithText(context.getString(R.string.reading_columns))
        for (font in listOf(1.6f, 2f)) {
            compose.runOnIdle { scale = font }
            compose.onNodeWithText(title).performClick()
            for (option in ReadingLayoutPresetPreference.values) {
                val target = hasText(option.toDesc(context)) and isSelectable()
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(target)
                compose.onNode(target).assertIsDisplayed()
            }
            val firstChoice = hasText(ReadingLayoutPresetPreference.Auto.toDesc(context)) and isSelectable()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(firstChoice)
            val first = compose.onNode(firstChoice).assertIsDisplayed()
            capture("editorial-layout-dialog-${(font * 100).toInt()}")
            pressBackFromModal(first, host)
            compose.onAllNodes(isSelectable()).assertCountEquals(0)
            host.assertIsDisplayed()
        }
    }

    @Test fun productionTextDialogCanCancelReopenAndConfirmWithoutLosingHost() {
        var visible by mutableStateOf(false)
        val confirmed = mutableListOf<String>()
        compose.setContent {
            val field = rememberTextFieldState("Morning reading")
            Theme(1.6f) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    SettingItem(Modifier.testTag("dialog-launcher"), title = "Rename folder", onClick = { visible = true })
                    TextFieldDialog(textFieldState = field, visible = visible, readOnly = true,
                        title = "Rename folder", onDismissRequest = { visible = false },
                        onConfirm = { confirmed += it; visible = false })
                }
            }
        }
        val host = compose.onNodeWithTag("dialog-launcher")
        host.performClick()
        val cancel = compose.onNodeWithText(context.getString(R.string.cancel))
        cancel.assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.confirm)).assertIsDisplayed().assertIsEnabled()
        capture("editorial-text-dialog")
        cancel.performClick()
        compose.waitForIdle()
        assertTrue(confirmed.isEmpty())
        host.assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("Morning reading"), confirmed)
        host.assertIsDisplayed().performClick()
        pressBackFromModal(compose.onNodeWithText(context.getString(R.string.cancel)), host)
        assertEquals(listOf("Morning reading"), confirmed)
    }

    @Test fun realAccountChooserKeepsSelectedSwitchAndManageActionsDistinct() {
        val accounts = (1..12).map { id -> Account(id = id,
            name = if (id % 2 == 1) "Independent daily reading $id" else "世界新闻与文化观察 $id", type = AccountType.Local) }
        var visible by mutableStateOf(false)
        var scale by mutableFloatStateOf(1.6f)
        var current by mutableIntStateOf(1)
        var settings = 0
        var manages = 0
        val switches = mutableListOf<Int?>()
        compose.setContent {
            Theme(scale) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    SettingItem(Modifier.testTag("accounts-launcher"), title = "Accounts", onClick = { visible = true })
                    AccountsTab(visible = visible, currentAccountId = current, accounts = accounts,
                        onAccountSwitch = { switches += it.id; current = requireNotNull(it.id) },
                        onClickSettings = { settings++ }, onClickManage = { manages++ },
                        onDismissRequest = { visible = false })
                }
            }
        }
        val host = compose.onNodeWithTag("accounts-launcher")
        for (font in listOf(1.6f, 2f)) {
            compose.runOnIdle { scale = font; current = 1 }
            host.performClick()
            compose.onNodeWithText(accounts.first().name).performScrollTo().assertIsSelected().assertHasClickAction().performClick()
            compose.onNodeWithText(accounts[1].name).performScrollTo().assertIsNotSelected().performClick()
            compose.onNodeWithText(accounts[1].name).assertIsSelected()
            if (font == 1.6f) capture("editorial-accounts-dialog")
            compose.onNodeWithText(accounts.last().name).performScrollTo().assertIsDisplayed().performClick()
            assertEquals(accounts.last().id, current)
            compose.onNodeWithText(context.getString(R.string.add)).performScrollTo().assertIsDisplayed().performClick()
            pressBackFromModal(compose.onNodeWithText(context.getString(R.string.add)), host)
            host.performClick()
            compose.onNodeWithText(accounts.last().name).performScrollTo().assertIsSelected()
            pressBackFromModal(compose.onNodeWithText(accounts.last().name), host)
        }
        assertEquals(2, settings)
        assertEquals(2, manages)
        assertEquals(listOf(2, 12, 2, 12), switches)
    }

    @Test fun publicationTitleDoesNotNeedAFabricatedInitialBadgeToKeepItsActions() {
        var scale by mutableFloatStateOf(1.6f)
        var measuring by mutableStateOf(false)
        var opens = 0
        var options = 0
        val source = "a-deliberately-long-publication-host.covering-science-and-culture.example"
        compose.setContent {
            Theme(scale) {
                PublicationBlock(name = "Observatory", count = 7, source = source, countLabel = "Unread", measuring = measuring,
                    modifier = Modifier.testTag("publication-clean"), onClick = { opens++ }, onLongClick = { options++ })
            }
        }
        for (font in listOf(1.6f, 2f)) {
            compose.runOnIdle { scale = font }
            compose.onAllNodesWithText("O", useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText("Observatory", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(source, useUnmergedTree = true).assertIsDisplayed()
            val count = compose.onNodeWithText("7 Unread", useUnmergedTree = true).assertIsDisplayed()
            val countLayout = textLayout(count)
            val countDiagnostics = "text=${countLayout.layoutInput.text.text}, font=$font, style=${countLayout.layoutInput.style}, constraints=${countLayout.layoutInput.constraints}, size=${countLayout.size}, " +
                "paragraph=${countLayout.multiParagraph.width}x${countLayout.multiParagraph.height}, " +
                "overflowWidth=${countLayout.didOverflowWidth}, overflowHeight=${countLayout.didOverflowHeight}, " +
                "lines=${(0 until countLayout.lineCount).map { line -> listOf(countLayout.getLineStart(line), countLayout.getLineEnd(line), countLayout.isLineEllipsized(line), countLayout.getLineTop(line), countLayout.getLineBottom(line), countLayout.getLineLeft(line), countLayout.getLineRight(line)) }}, " +
                "countBounds=${count.getUnclippedBoundsInRoot()}, blockBounds=${compose.onNodeWithTag("publication-clean").getUnclippedBoundsInRoot()}"
            if (countLayout.hasVisualOverflow) capture("diagnostic-publication-count-${(font * 100).toInt()}")
            assertFalse("A long source may not ellipsize the independent unread count: $countDiagnostics", countLayout.hasVisualOverflow)
            val block = compose.onNodeWithTag("publication-clean").getUnclippedBoundsInRoot()
            val countBounds = count.getUnclippedBoundsInRoot()
            val screen = compose.onRoot().getUnclippedBoundsInRoot()
            assertTrue(countBounds.left >= block.left && countBounds.right <= block.right && countBounds.top >= block.top && countBounds.bottom <= block.bottom)
            assertTrue(block.left >= screen.left && block.right <= screen.right && block.top >= screen.top && block.bottom <= screen.bottom)
            compose.runOnIdle { measuring = true }
            compose.waitForIdle()
            assertEquals("Measurement and actual long-source/count blocks have identical bounds", block,
                compose.onNodeWithTag("publication-clean").getUnclippedBoundsInRoot())
            compose.runOnIdle { measuring = false }
            compose.waitForIdle()
            compose.onNodeWithTag("publication-clean").assertHasClickAction().performClick()
            compose.onNodeWithTag("publication-clean").performTouchInput { longClick() }
        }
        assertEquals(2, opens)
        assertEquals(2, options)
    }

    private fun pageBack(): SemanticsNodeInteraction {
        val buttons = compose.onAllNodesWithContentDescription(context.getString(R.string.back))
        val nodes = buttons.fetchSemanticsNodes()
        val top = nodes.indices.filter { nodes[it].boundsInRoot.top >= 0f && nodes[it].boundsInRoot.height > 0f }
            .minBy { nodes[it].boundsInRoot.top }
        // Embedded style samples can have a Back icon too. The actual scaffold is topmost;
        // each caller additionally verifies that its real route onBack callback fires.
        return buttons[top]
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Composable private fun Theme(scale: Float, dark: Boolean = false, content: @Composable () -> Unit) {
        val native = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(native.density, scale),
            LocalEInkMode provides if (dark) EInkModePreference.OFF else EInkModePreference.ON) {
            AppTheme(useDarkTheme = dark, content = content)
        }
    }

    @Composable private fun SettingsRoute(route: String, onBack: () -> Unit, onNavigate: (String) -> Unit) {
        when (route) {
            "appearance" -> ColorAndStylePage(onBack, { onNavigate("dark") }, { onNavigate("feeds") }, { onNavigate("articles") }, { onNavigate("reading") })
            "interaction" -> InteractionPage(onBack)
            "feeds" -> FeedsPageStylePage(onBack)
            "articles" -> FlowPageStylePage(onBack)
            "reading" -> ReadingStylePage(onBack, { onNavigate("bold") }, { onNavigate("title") }, { onNavigate("text") }, { onNavigate("images") }, { onNavigate("video") })
            "title" -> ReadingTitlePage(onBack)
            "text" -> ReadingTextPage(onBack)
            "images" -> ReadingImagePage(onBack)
            "video" -> ReadingVideoPage(onBack)
            "languages" -> LanguagesPage(onBack)
            "dark" -> DarkThemePage(onBack)
        }
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun assertCompleteText(parent: String) {
        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag(parent)), useUnmergedTree = true)
        assertTrue(rows.fetchSemanticsNodes().isNotEmpty())
        rows.fetchSemanticsNodes().indices.forEach { index ->
            val layout = textLayout(rows[index])
            val diagnostics = "text=${layout.layoutInput.text.text}, fontScale=${layout.layoutInput.density.fontScale}, style=${layout.layoutInput.style}, " +
                "constraints=${layout.layoutInput.constraints}, size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, " +
                "lines=${(0 until layout.lineCount).map { line -> listOf(layout.getLineStart(line), layout.getLineEnd(line), layout.isLineEllipsized(line), layout.getLineTop(line), layout.getLineBottom(line), layout.getLineLeft(line), layout.getLineRight(line)) }}, " +
                "bounds=${rows[index].getUnclippedBoundsInRoot()}"
            if (layout.hasVisualOverflow) capture("diagnostic-settings-$parent-${(layout.layoutInput.density.fontScale * 100).toInt()}")
            assertFalse("Setting text must wrap completely: $diagnostics", layout.hasVisualOverflow)
            for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
        }
    }

    private fun pressBackFromModal(modal: SemanticsNodeInteraction, host: SemanticsNodeInteraction) {
        val modalRoot = modal.assertIsDisplayed().fetchSemanticsNode().root as ViewRootForTest
        val hostRoot = host.fetchSemanticsNode().root as ViewRootForTest
        assertTrue("Back targets the separate modal, not the host activity", modalRoot !== hostRoot)
        compose.waitUntil(5_000) { compose.runOnUiThread { modalRoot.view.isAttachedToWindow && modalRoot.view.hasWindowFocus() } }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.runOnUiThread { !modalRoot.view.isAttachedToWindow } }
        compose.waitUntil(5_000) { compose.runOnUiThread { hostRoot.view.isAttachedToWindow && hostRoot.view.hasWindowFocus() } }
        compose.runOnIdle { assertTrue(hostRoot.isLifecycleInResumedState) }
        host.assertIsDisplayed()
    }

    private fun capture(name: String) = NativePageDensityCapture.capture(compose, name, "", recordDensity = false)
}

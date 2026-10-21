package me.ash.reader.ui.page.home

import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.disk.DiskCache
import coil.EventListener
import coil.fetch.Fetcher
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import me.ash.reader.R
import me.ash.reader.domain.data.Diff
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.layout.resolveReadingLayout
import me.ash.reader.ui.page.home.flow.*
import me.ash.reader.ui.page.home.reading.EInkReaderVolumeReservation
import me.ash.reader.ui.page.home.reading.readerArticleSwipe
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screen-fit acceptance tests. These render the production scaffold, compact header, measured
 * viewport, article previews and page dispatcher. No LazyColumn or count-slice substitute is
 * used here. The five CI AVD sizes supply the actual 320/400/600/800/1000dp window constraints.
 * Account/repository integration is covered separately; these are deterministic native screens.
 */
class ViewportNewspaperUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyCjkPageFitsWholePreviewBlocksAndPartitionsTheEdition() {
        val fixture = Fixture(articles(photoUrls()))
        compose.setContent { Screen(fixture) }
        awaitEdition(fixture)
        val expected = fixture.source.value.filterIsInstance<ArticleFlowItem.Article>().map { it.articleWithFeed.article.id }
        val seen = mutableListOf<String>()
        val pageCount = compose.runOnIdle { fixture.state.pages.size }
        assertTrue("Realistic CJK edition needs multiple actual viewport pages", pageCount > 2)
        repeat(pageCount) { page ->
            val ids = assertWholePage(fixture)
            seen += ids
            if (page == 0) { waitForPhotograph(); capture("viewport-cjk-first") }
            if (page == 1) capture("viewport-cjk-second")
            if (page == pageCount - 1) capture("viewport-cjk-last")
            if (page < pageCount - 1) nextWithHeader()
        }
        assertEquals("Every source appears once, in source order, with no clipped extra stories", expected, seen)
        val beforeEnd = fixture.marked.toList()
        compose.onNodeWithTag("article-page-control").performClick()
        compose.onNodeWithTag("article-page-next").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("article-page-close").performClick()
        compose.onNodeWithTag("newspaper-viewport").performTouchInput { swipeUp() }
        assertEquals("End-of-edition gestures/buttons cannot mark an unturned final page", beforeEnd, fixture.marked)
        assertEquals("Forward navigation marks every actually displayed outgoing page and no final-page IDs",
            expected.dropLast(assertWholePage(fixture).size), fixture.marked)
    }

    @Test fun enlargedCjkTitlesAndImagesOnOffStillHaveNoWithinPageScroll() {
        val fixture = Fixture(articles(photoUrls(), oversized = true, count = 14))
        compose.setContent { Screen(fixture) }
        awaitEdition(fixture)
        for (scale in listOf(1f, 1.6f, 2f)) {
            for (images in listOf(true, false)) {
                compose.runOnIdle { fixture.fontScale.value = scale; fixture.images.value = images }
                compose.waitForIdle()
                val pageCount = compose.runOnIdle { fixture.state.pages.size }
                // Resize/font/image changes preserve an article anchor, not a count-based page.
                while (compose.runOnIdle { fixture.state.currentPage > 0 }) previousWithHeader()
                val seen = mutableListOf<String>()
                repeat(pageCount) { page ->
                    seen += assertWholePage(fixture)
                    if (page < pageCount - 1) nextWithHeader()
                }
                assertEquals("All oversized headlines remain reachable at font=$scale, images=$images",
                    fixture.source.value.filterIsInstance<ArticleFlowItem.Article>().map { it.articleWithFeed.article.id }, seen)
                if (!images) compose.onAllNodesWithTag("article-photo", useUnmergedTree = true).assertCountEquals(0)
                if (scale == 2f && images) capture("viewport-cjk-large-font")
            }
        }
    }

    @Test fun failedAndDelayedPhotosKeepTheSameMeasuredPagesAndWholeBlocks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Coil.imageLoader(context)
        val gate = CountDownLatch(1)
        val failureGate = CountDownLatch(1)
        val failureRequests = java.util.concurrent.atomic.AtomicInteger()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val failures = java.util.concurrent.atomic.AtomicInteger()
        // Production replaces the component registry with its safe raster decoder. Coil retains
        // the EventListener when cloning that loader, so observe/gate its real fetch pipeline here.
        val delayed = original.newBuilder()
            .diskCache(DiskCache.Builder()
                .directory(File(context.cacheDir, "viewport-delay-${System.nanoTime()}")).build())
            .memoryCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .respectCacheHeaders(false)
            .fetcherDispatcher(Dispatchers.IO)
            .eventListener(object : EventListener {
                override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
                    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                    if (request.data.toString().contains("viewport-photo-missing")) {
                        failureRequests.incrementAndGet()
                        check(failureGate.await(30, TimeUnit.SECONDS)) { "Failure gate was not released" }
                        throw java.io.IOException("Deterministic offline image failure")
                    }
                    if (request.data.toString().contains("viewport-photo")) {
                        requests.incrementAndGet()
                        check(gate.await(30, TimeUnit.SECONDS)) { "Photo gate was not released" }
                    }
                }

                override fun onError(request: ImageRequest, result: ErrorResult) {
                    if (request.data.toString().contains("viewport-photo-missing")) {
                        failures.incrementAndGet()
                    }
                }
            }).build()
        Coil.setImageLoader(delayed)
        try {
            val fixture = Fixture(articles(photoUrls(), count = 18))
            compose.setContent { Screen(fixture) }
            awaitEdition(fixture)
            compose.waitUntil(10_000) { requests.get() > 0 }
            val beforePages = pageIds(fixture)
            val beforeBounds = wholeBlockBounds(fixture)
            assertWholePage(fixture)
            gate.countDown()
            waitForPhotograph()
            compose.waitForIdle()
            assertEquals("Image completion cannot repack the printed edition", beforePages, pageIds(fixture))
            assertEquals("A photo's intrinsic ratio cannot move an already displayed block", beforeBounds, wholeBlockBounds(fixture))
            assertWholePage(fixture)
            capture("viewport-delayed-photo")
            // A new edition with an invalid image exercises the real production failure path.
            compose.runOnIdle {
                fixture.pager.value = Any()
                fixture.source.value = articles(listOf("https://offline-fixture.invalid/viewport-photo-missing-${System.nanoTime()}.jpg"), count = 8)
            }
            awaitEdition(fixture)
            compose.waitUntil(10_000) { failureRequests.get() > 0 }
            val failedPages = pageIds(fixture)
            val failedBounds = wholeBlockBounds(fixture)
            failureGate.countDown()
            compose.waitUntil(10_000) { failures.get() > 0 }
            compose.waitForIdle()
            assertEquals("Decode failure cannot alter page membership", failedPages, pageIds(fixture))
            assertEquals("Decode failure preserves the reserved image geometry", failedBounds, wholeBlockBounds(fixture))
            assertWholePage(fixture)
            capture("viewport-failed-photo")
        } finally {
            gate.countDown()
            failureGate.countDown()
            Coil.setImageLoader(original)
            delayed.shutdown()
        }
    }

    @Test fun buttonsSwipesAndVolumeUseTheSameVisibleOutgoingIds() {
        VolumeKeyEventBus.resetForTest()
        val fixture = Fixture(articles(photoUrls(), count = 24))
        try {
            compose.setContent { Screen(fixture) }
            awaitEdition(fixture)
            val first = assertWholePage(fixture)
            nextWithHeader()
            val second = assertWholePage(fixture)
            assertEquals(first, fixture.marked)
            previousWithHeader()
            assertEquals(first, assertWholePage(fixture))
            assertEquals("Backward turns never mark a page", first, fixture.marked)
            fixture.marked.clear()
            compose.onNodeWithTag("newspaper-viewport").performTouchInput { swipeUp() }
            assertEquals(second, assertWholePage(fixture))
            assertEquals("One vertical gesture marks only its displayed outgoing blocks", first, fixture.marked)
            compose.onNodeWithTag("newspaper-viewport").performTouchInput { swipeDown() }
            assertEquals(first, assertWholePage(fixture))
            fixture.marked.clear()
            compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }
            compose.waitUntil(5_000) { fixture.state.currentPage == 1 }
            assertEquals(second, assertWholePage(fixture))
            assertEquals("The real hardware-event bus uses the same concrete page dispatcher", first, fixture.marked)
            compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.PREV) }
            compose.waitUntil(5_000) { fixture.state.currentPage == 0 }
            assertEquals(first, assertWholePage(fixture))
            assertEquals(first, fixture.marked)
            fixture.marked.clear()
            compose.runOnIdle {
                VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
                VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
            }
            compose.waitUntil(5_000) { fixture.state.currentPage == 1 }
            assertEquals("A burst before the next frame cannot skip an unpresented page", second, assertWholePage(fixture))
            assertEquals(first, fixture.marked)
            compose.onNodeWithTag("newspaper-viewport").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                assertTrue("Accessibility scroll is one whole page, not a partial list offset", scroll(0f, 1f))
            }
            assertWholePage(fixture)
            assertEquals(first + second, fixture.marked)
        } finally { VolumeKeyEventBus.resetForTest() }
    }

    @Test fun tapsLongPressShortDragsAndInterruptedGesturesDoNotTurnOrReadHiddenPages() {
        VolumeKeyEventBus.resetForTest()
        val fixture = Fixture(articles(photoUrls(), count = 24))
        try {
            compose.setContent { Screen(fixture) }
            awaitEdition(fixture)
            val first = assertWholePage(fixture)
            val firstBlock = compose.onNodeWithTag("article-block-${first.first()}", useUnmergedTree = true)
            firstBlock.performTouchInput { click() }
            assertEquals(listOf(first.first()), fixture.opened)
            firstBlock.performTouchInput { longClick() }
            val readLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.mark_as_read)
            compose.onNodeWithText(readLabel).assertIsDisplayed().performClick()
            assertEquals(listOf(first.first()), fixture.toggled)
            assertEquals(first, assertWholePage(fixture))
            assertTrue("Tap and context action are not forward page turns", fixture.marked.isEmpty())
            val viewport = compose.onNodeWithTag("newspaper-viewport")
            val beforeJitter = fixture.opened.toList()
            val jitterPoint = viewport.fetchSemanticsNode().boundsInRoot.center
            val jitterArticle = first.firstOrNull { id ->
                compose.onNodeWithTag("article-block-$id", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot.contains(jitterPoint)
            }
            viewport.performTouchInput {
                down(center)
                moveTo(center + Offset(0f, -12f), delayMillis = 100)
                up()
            }
            assertEquals(first, assertWholePage(fixture))
            // Sub-touch-slop finger jitter is a conventional tap, not a cancelled gesture.
            assertTrue("Jitter can only tap the article actually under its original down position",
                fixture.opened == beforeJitter || jitterArticle != null && fixture.opened == beforeJitter + jitterArticle)
            val acceptedTaps = fixture.opened.toList()
            viewport.performTouchInput { down(center); moveTo(center + Offset(0f, -150f), delayMillis = 100); cancel() }
            assertEquals(first, assertWholePage(fixture))
            assertEquals("A cancelled vertical gesture never clicks through", acceptedTaps, fixture.opened)
            assertTrue("Short and cancelled gestures never mark stories", fixture.marked.isEmpty())
            viewport.performTouchInput { down(center); moveTo(center + Offset(0f, -150f), delayMillis = 100) }
            compose.runOnIdle { fixture.paneHeight.value = 270.dp }
            val resized = assertWholePage(fixture)
            viewport.performTouchInput { up() }
            assertEquals("Resizing cancels a held gesture even if page index stays zero", resized, assertWholePage(fixture))
            assertEquals("Resize cancellation never taps a reflowed article", acceptedTaps, fixture.opened)
            assertTrue(fixture.marked.isEmpty())
            compose.runOnIdle { fixture.paneHeight.value = Dp.Unspecified }
            assertEquals(first, assertWholePage(fixture))
            viewport.performTouchInput { down(center); moveTo(center + Offset(0f, -150f), delayMillis = 100) }
            compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }
            compose.waitUntil(5_000) { fixture.state.currentPage == 1 }
            val second = assertWholePage(fixture)
            viewport.performTouchInput { up() }
            assertEquals("A held gesture cannot turn the page installed by a hardware key", second, assertWholePage(fixture))
            assertEquals("Hardware interruption never taps the new page", acceptedTaps, fixture.opened)
            assertEquals(first, fixture.marked)
            // A dominant horizontal motion still invokes only the configured article shortcut.
            val nextBlock = compose.onNodeWithTag("article-block-${second.first()}", useUnmergedTree = true)
            nextBlock.performTouchInput { swipe(Offset(width * 0.2f, center.y), Offset(width * 0.8f, center.y), 250) }
            assertEquals(listOf(first.first(), second.first()), fixture.toggled)
            assertEquals("A horizontal article shortcut cancels its tap", acceptedTaps, fixture.opened)
            for (distance in listOf(-100f, 100f)) {
                nextBlock.performTouchInput {
                    down(center)
                    moveTo(center + Offset(distance, 0f), delayMillis = 100)
                    cancel()
                }
                assertEquals("Cancelled horizontal drag $distance never becomes a tap", acceptedTaps, fixture.opened)
                assertEquals(listOf(first.first(), second.first()), fixture.toggled)
                assertTrue(fixture.starred.isEmpty())
            }
            nextBlock.performTouchInput {
                down(0, center)
                down(1, center + Offset(0f, 30f))
                moveTo(0, center + Offset(100f, 0f), delayMillis = 100)
                moveTo(1, center + Offset(100f, 30f), delayMillis = 100)
                up(0); up(1)
            }
            assertEquals("Cancelled and multi-pointer horizontal gestures never invoke an article action",
                listOf(first.first(), second.first()), fixture.toggled)
            assertTrue("Rejected horizontal gestures never invoke the opposite shortcut", fixture.starred.isEmpty())
            assertEquals("Multi-pointer rejection never falls through to the article's clickable child", acceptedTaps, fixture.opened)
            assertEquals(second, assertWholePage(fixture))
            assertEquals(first, fixture.marked)
            nextBlock.performTouchInput { swipe(Offset(width * 0.2f, center.y), Offset(width * 0.8f, center.y), 250) }
            assertEquals("The normal horizontal shortcut still works after cancellation",
                listOf(first.first(), second.first(), second.first()), fixture.toggled)
            assertEquals(second, assertWholePage(fixture))
            assertEquals(first, fixture.marked)
        } finally { VolumeKeyEventBus.resetForTest() }
    }

    @Test fun unreadMutationReaderReturnAndPaneResizeKeepTheExactEdition() {
        val fixture = Fixture(articles(photoUrls(), count = 28))
        compose.setContent { Screen(fixture) }
        awaitEdition(fixture)
        nextWithHeader()
        nextWithHeader()
        val outgoing = assertWholePage(fixture)
        val originalPages = pageIds(fixture)
        val originalBounds = wholeBlockBounds(fixture)
        val marked = fixture.marked.toList()
        compose.onNodeWithTag("article-block-${outgoing.last()}", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(outgoing.last(), fixture.opened.last())
        compose.runOnIdle {
            fixture.readerVisible.value = true
            // The clicked article and every peer on its page disappear from a live unread query.
            fixture.source.value = fixture.source.value.filterNot {
                it is ArticleFlowItem.Article && it.articleWithFeed.article.id in outgoing
            }
        }
        compose.onNodeWithTag("test-reader").assertIsDisplayed()
        waitForHostFocus(compose.onNodeWithTag("test-reader"))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("test-reader").assertDoesNotExist()
        assertEquals("Reader return restores the entire printed page, not just a shifted index", outgoing, assertWholePage(fixture))
        assertEquals(originalPages, pageIds(fixture))
        assertEquals(originalBounds, wholeBlockBounds(fixture))
        assertEquals("Reader return and unread invalidation cannot mark unseen stories", marked, fixture.marked)
        val anchor = outgoing.first()
        compose.runOnIdle { fixture.paneHeight.value = 270.dp }
        assertTrue("Short landscape/split-pane reflow keeps the exact article anchor", anchor in assertWholePage(fixture))
        assertEquals(marked, fixture.marked)
        compose.runOnIdle { fixture.fontScale.value = 2f }
        assertWholePage(fixture)
        val resizedAnchor = fixture.state.displayedArticles.first().article.id
        compose.runOnIdle { fixture.paneHeight.value = Dp.Unspecified }
        assertTrue("Growing the pane retains its latest exact source anchor", resizedAnchor in assertWholePage(fixture))
        assertEquals(marked, fixture.marked)
        capture("viewport-resized-anchor")
    }

    @Test fun readerLoadingReservesVolumeAndDisposalRestoresListOwnership() {
        VolumeKeyEventBus.resetForTest()
        val fixture = Fixture(articles(photoUrls(), count = 20))
        try {
            compose.setContent { Screen(fixture) }
            awaitEdition(fixture)
            val first = assertWholePage(fixture)
            compose.runOnIdle { fixture.readerLoading.value = true }
            compose.runOnIdle {
                assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH))
                VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
            }
            assertEquals("Loading reader prevents hidden list turns", first, assertWholePage(fixture))
            assertTrue(fixture.marked.isEmpty())
            compose.runOnIdle { fixture.readerLoading.value = false }
            compose.runOnIdle {
                assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
                VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
            }
            compose.waitUntil(5_000) { fixture.state.currentPage == 1 }
            assertEquals(first, fixture.marked)
            assertWholePage(fixture)
        } finally { VolumeKeyEventBus.resetForTest() }
    }

    @Test fun productionHeaderActionsAndModalDismissalsPreservePageAndHostFocus() {
        val fixture = Fixture(articles(photoUrls(), count = 20))
        compose.setContent { Screen(fixture) }
        awaitEdition(fixture)
        val first = assertWholePage(fixture)
        val before = wholeBlockBounds(fixture)
        val host = compose.onNodeWithTag("newspaper-viewport")
        val more = compose.onNodeWithTag("article-list-more")
        val dialog = compose.onNodeWithTag("article-list-actions-dialog")
        for (scale in listOf(1f, 1.6f, 2f)) {
            compose.runOnIdle { fixture.fontScale.value = scale }
            for (tag in listOf("article-list-back", "article-page-control", "article-list-more")) {
                val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
                assertTrue("$tag preserves a 48dp hit target at font=$scale", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
            }
            val title = compose.onNodeWithTag("article-list-title").getUnclippedBoundsInRoot()
            val count = compose.onNodeWithTag("article-page-control").getUnclippedBoundsInRoot()
            assertTrue("Actual CJK edition title never overlaps the count", title.right <= count.left + 1.dp)
            more.performClick()
            assertStaticDialog(dialog)
            compose.onNodeWithTag("article-list-actions-close").performScrollTo().performClick()
            waitForHostFocus(host)
        }
        compose.runOnIdle { fixture.fontScale.value = 1f }
        more.performClick()
        capture("viewport-header-actions")
        pressBackFromModal(dialog, host)
        more.performClick()
        dismissOutside(dialog, host)
        assertEquals(first, assertWholePage(fixture))
        assertEquals(before, wholeBlockBounds(fixture))
        assertTrue("Opening or dismissing controls never marks a page", fixture.marked.isEmpty())
        assertTrue("Outside dismissal never clicks through into a story", fixture.opened.isEmpty())
        for (action in listOf("filter-all", "filter-unread", "filter-starred")) {
            more.performClick()
            compose.onNodeWithTag("article-list-$action").performScrollTo().performClick()
            dialog.assertDoesNotExist()
            waitForHostFocus(host)
        }
        assertEquals(listOf(Filter.All, Filter.Unread, Filter.Starred), fixture.filters)
        more.performClick()
        compose.onNodeWithTag("article-list-mark-all").assertDoesNotExist()
        compose.onNodeWithTag("article-list-actions-close").performScrollTo().performClick()
        compose.runOnIdle { fixture.filter.value = Filter.Unread }
        for (action in listOf("refresh", "mark-all", "search")) {
            more.performClick()
            compose.onNodeWithTag("article-list-$action").performScrollTo().performClick()
            dialog.assertDoesNotExist()
            waitForHostFocus(host)
        }
        assertEquals(listOf("refresh", "mark-all", "search"), fixture.actions)
        compose.onNodeWithTag("article-list-back").performClick()
        assertEquals(listOf("refresh", "mark-all", "search", "back"), fixture.actions)
        assertEquals(first, assertWholePage(fixture))
        assertTrue(fixture.marked.isEmpty())
    }

    @Test fun liveReadAndStarActionsUpdateMenusWithoutChangingFrozenPreviewGeometry() {
        val data = articles(emptyList(), count = 12).map { item ->
            if (item is ArticleFlowItem.Article) ArticleFlowItem.Article(item.articleWithFeed.copy(
                article = item.articleWithFeed.article.copy(isUnread = false).also { it.dateString = "09:35 PM" }))
            else item
        }
        val fixture = Fixture(data)
        fixture.hideSource.value = true
        fixture.liveActions.value = true
        compose.setContent { Screen(fixture) }
        awaitEdition(fixture)
        val ids = assertWholePage(fixture)
        val id = ids.first()
        val initialBounds = wholeBlockBounds(fixture)
        val initialPages = pageIds(fixture)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun action(label: Int) {
            compose.onNodeWithTag("article-block-$id", useUnmergedTree = true).performTouchInput { longClick() }
            compose.onNodeWithText(context.getString(label)).assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals("Live status changes cannot change printed block dimensions", initialBounds, wholeBlockBounds(fixture))
            assertEquals(initialPages, pageIds(fixture))
        }
        action(R.string.mark_as_unread)
        compose.runOnIdle { fixture.diffMap.value = emptyMap() }
        action(R.string.mark_as_read)
        compose.runOnIdle { fixture.diffMap.value = emptyMap() }
        action(R.string.mark_as_starred)
        action(R.string.mark_as_unstar)
        assertEquals(listOf(id, id), fixture.toggled)
        assertEquals(listOf(id, id), fixture.starred)
        assertTrue("Read/star actions do not mark any outgoing page", fixture.marked.isEmpty())
    }

    @Test fun emptySinglePageAndFilterReplacementNeverTurnOrLeakAnOldEdition() {
        val fixture = Fixture(emptyList())
        compose.setContent { Screen(fixture) }
        compose.waitForIdle()
        compose.onAllNodes(SemanticsMatcher("article blocks") {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag].startsWith("article-block-")
        }, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("article-page-control").performClick()
        compose.onNodeWithTag("article-page-previous").assertIsNotEnabled()
        compose.onNodeWithTag("article-page-next").assertIsNotEnabled()
        compose.onNodeWithTag("article-page-close").performClick()
        compose.runOnIdle { fixture.source.value = articles(emptyList(), count = 1) }
        awaitEdition(fixture)
        val single = assertWholePage(fixture)
        assertEquals(1, fixture.state.pages.size)
        compose.onNodeWithTag("newspaper-viewport").performTouchInput { swipeUp() }
        assertEquals(single, assertWholePage(fixture))
        assertTrue(fixture.marked.isEmpty())
        compose.onNodeWithTag("article-list-more").performClick()
        compose.runOnIdle { fixture.pager.value = Any(); fixture.source.value = emptyList() }
        compose.waitUntil(5_000) { fixture.state.items.isEmpty() }
        compose.onNodeWithTag("article-list-actions-dialog").assertDoesNotExist()
        single.forEach { compose.onNodeWithTag("article-block-$it", useUnmergedTree = true).assertDoesNotExist() }
        assertTrue(fixture.marked.isEmpty())
        var previousId = single.single()
        repeat(3) { round ->
            val replacementId = "replacement-$round"
            compose.runOnIdle {
                fixture.pager.value = Any()
                fixture.source.value = articles(emptyList(), count = 1).map { item ->
                    if (item is ArticleFlowItem.Article) ArticleFlowItem.Article(item.articleWithFeed.copy(
                        article = item.articleWithFeed.article.copy(id = replacementId).also { it.dateString = "09:35 PM" }))
                    else item
                }
            }
            awaitEdition(fixture)
            assertEquals("Repopulating and same-count filter replacements publish only the new edition",
                listOf(replacementId), assertWholePage(fixture))
            compose.onNodeWithTag("article-block-$previousId", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(1, fixture.state.pages.size)
            assertTrue("Replacing an edition does not mark its previous IDs", fixture.marked.isEmpty())
            previousId = replacementId
        }
    }

    @Test fun readerHorizontalSwipeHonorsAxisThresholdCancellationAndDocumentLifecycle() {
        val generation = mutableStateOf("article-a/page-1")
        val height = mutableStateOf(300.dp)
        val verticalScrollEnabled = mutableStateOf(false)
        val scrollState = ScrollState(0)
        val next = java.util.concurrent.atomic.AtomicInteger()
        val previous = java.util.concurrent.atomic.AtomicInteger()
        val taps = java.util.concurrent.atomic.AtomicInteger()
        val systemLeftEdge = java.util.concurrent.atomic.AtomicInteger()
        val systemRightEdge = java.util.concurrent.atomic.AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    val edgeDensity = LocalDensity.current
                    val direction = LocalLayoutDirection.current
                    val leftEdge = WindowInsets.systemGestures.getLeft(edgeDensity, direction)
                    val rightEdge = WindowInsets.systemGestures.getRight(edgeDensity, direction)
                    SideEffect { systemLeftEdge.set(leftEdge); systemRightEdge.set(rightEdge) }
                    Box(Modifier.fillMaxWidth().height(height.value)
                        .verticalScroll(scrollState, enabled = verticalScrollEnabled.value)
                        .testTag("reader-scroll-parent")) {
                        Box(Modifier.fillMaxWidth()
                            .height(if (verticalScrollEnabled.value) 900.dp else height.value)
                            .testTag("reader-swipe-surface")
                            .readerArticleSwipe(generation.value, true,
                                onPrevious = { previous.incrementAndGet() }, onNext = { next.incrementAndGet() },
                                consumeRejectedVertical = !verticalScrollEnabled.value)) {
                            Text("Actual reader gesture with an independently clickable child",
                                Modifier.fillMaxSize().clickable { taps.incrementAndGet() })
                        }
                    }
                }
            }
        }
        val surface = compose.onNodeWithTag("reader-swipe-surface")
        surface.performTouchInput { click() }
        assertEquals(1, taps.get())
        surface.performTouchInput { swipe(Offset(width * 0.8f, center.y), Offset(width * 0.2f, center.y), 250) }
        surface.performTouchInput { swipe(Offset(width * 0.2f, center.y), Offset(width * 0.8f, center.y), 250) }
        assertEquals(1, next.get())
        assertEquals(1, previous.get())
        assertEquals("Horizontal navigation cancels the child's tap", 1, taps.get())
        surface.performTouchInput { swipeUp() }
        assertEquals("A vertical reader drag cancels its child tap", 1, taps.get())
        surface.performTouchInput { down(center); moveTo(center + Offset(-12f, 0f), 100); up() }
        assertTrue("Sub-slop reader jitter may tap only its one hit-tested child", taps.get() == 1 || taps.get() == 2)
        val acceptedTaps = taps.get()
        surface.performTouchInput { down(center); moveTo(center + Offset(-120f, 0f), 100); cancel() }
        assertEquals("Cancelled horizontal reader drag never clicks through", acceptedTaps, taps.get())
        assertEquals(1, next.get())
        assertEquals(1, previous.get())
        surface.performTouchInput { down(center); moveTo(center + Offset(-120f, 0f), 100) }
        compose.runOnIdle { generation.value = "article-b/page-1" }
        surface.performTouchInput { up() }
        assertEquals("Held horizontal gesture cannot act on a replacement document", 1, next.get())
        assertEquals("Document replacement cannot release a child click", acceptedTaps, taps.get())
        surface.performTouchInput { down(center); moveTo(center + Offset(-120f, 0f), 100) }
        compose.runOnIdle { height.value = 240.dp }
        surface.performTouchInput { up() }
        assertEquals("Resize cancels held reader gestures", 1, next.get())
        assertEquals("Reader resize cancellation never clicks through", acceptedTaps, taps.get())
        surface.performTouchInput {
            down(0, center)
            down(1, center + Offset(0f, 40f))
            moveTo(0, center + Offset(-100f, 0f), 100)
            moveTo(1, center + Offset(-100f, 40f), 100)
            up(0); up(1)
        }
        assertEquals("A two-finger gesture cannot navigate an article", 1, next.get())
        assertEquals("A rejected two-finger gesture cannot click the reader's child", acceptedTaps, taps.get())
        // Some emulator navigation modes have zero gesture edges; only assert an edge reservation
        // when Android actually reports one, rather than inventing a fixed exclusion strip.
        if (systemLeftEdge.get() > 0) {
            surface.performTouchInput { swipe(Offset(1f, center.y), Offset(width * 0.6f, center.y), 250) }
            assertEquals("Android Back edge is not an article shortcut", 1, previous.get())
        }
        if (systemRightEdge.get() > 0) {
            surface.performTouchInput { swipe(Offset(width - 1f, center.y), Offset(width * 0.4f, center.y), 250) }
            assertEquals("Android right Back edge is not an article shortcut", 1, next.get())
        }
        surface.performTouchInput { swipe(Offset(width * 0.8f, center.y), Offset(width * 0.2f, center.y), 250) }
        assertEquals("Cancellation does not permanently disable navigation", 2, next.get())
        val beforeParentScrollTaps = taps.get()
        compose.runOnIdle { verticalScrollEnabled.value = true }
        compose.onNodeWithTag("reader-scroll-parent").performTouchInput {
            swipe(Offset(width * 0.6f, this.height * 0.8f), Offset(width * 0.6f, this.height * 0.2f), 250)
        }
        compose.waitForIdle()
        assertTrue("The shared article gesture must yield vertical movement to a real scrolling parent", scrollState.value > 0)
        assertEquals("A parent-owned scroll never clicks through into its article", beforeParentScrollTaps, taps.get())
        assertEquals("A parent-owned scroll does not navigate horizontally", 2, next.get())
        assertEquals(1, previous.get())
    }

    private class Fixture(initial: List<ArticleFlowItem>) {
        val state = NewspaperViewportState()
        val source = mutableStateOf(initial)
        val pager = mutableStateOf<Any>(Any())
        val fontScale = mutableStateOf(1f)
        val images = mutableStateOf(true)
        val paneHeight = mutableStateOf(Dp.Unspecified)
        val readerVisible = mutableStateOf(false)
        val readerLoading = mutableStateOf(false)
        val marked = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val toggled = mutableListOf<String>()
        val filter = mutableStateOf<Filter>(Filter.Unread)
        val hideSource = mutableStateOf(false)
        val diffMap = mutableStateOf<Map<String, Diff>>(emptyMap())
        val liveActions = mutableStateOf(false)
        val starred = mutableListOf<String>()
        val filters = mutableListOf<Filter>()
        val actions = mutableListOf<String>()
        fun updateArticle(id: String, transform: (Article) -> Article) {
            source.value = source.value.map { item ->
                if (item is ArticleFlowItem.Article && item.articleWithFeed.article.id == id) {
                    val original = item.articleWithFeed.article
                    ArticleFlowItem.Article(item.articleWithFeed.copy(article = transform(original).also { it.dateString = original.dateString }))
                } else item
            }
        }
        fun turn(forward: Boolean) = state.turn(forward, true) { articles -> marked.addAll(articles.map { it.article.id }) }
    }

    @Composable private fun Screen(fixture: Fixture) {
        val nativeDensity = LocalDensity.current.density
        CompositionLocalProvider(
            LocalDensity provides Density(nativeDensity, fixture.fontScale.value),
            LocalEInkMode provides EInkModePreference.ON,
            LocalFlowArticleListImage provides if (fixture.images.value) FlowArticleListImagePreference.ON else FlowArticleListImagePreference.OFF,
            LocalArticleListSwipeEndAction provides SwipeEndActionPreference.ToggleRead,
            LocalFlowArticleListFeedIcon provides FlowArticleListFeedIconPreference.OFF,
            LocalFlowArticleListFeedName provides if (fixture.hideSource.value) FlowArticleListFeedNamePreference.OFF else FlowArticleListFeedNamePreference.ON,
            LocalFlowArticleListReadIndicator provides FlowArticleReadIndicatorPreference.AllRead,
        ) {
            AppTheme(useDarkTheme = false) {
                LaunchedEffect(fixture.pager.value, fixture.source.value) {
                    fixture.state.acceptSnapshot(fixture.pager.value, fixture.source.value)
                }
                EInkReaderVolumeReservation(fixture.readerLoading.value)
                if (fixture.readerVisible.value) {
                    BackHandler { fixture.readerVisible.value = false }
                    Box(Modifier.fillMaxSize().testTag("test-reader"))
                } else {
                    EInkVolumePageKeys(enabled = true, state = fixture.state, onTurn = { fixture.turn(it) })
                    RYScaffold(modifier = Modifier.fillMaxSize().testTag("viewport-screen"), reserveBottomBarSpace = true,
                        topBar = {
                            EInkArticleListHeader(title = "公众号 · 科技、人文与世界新闻", filter = fixture.filter.value,
                                currentPage = fixture.state.currentPage + 1, totalPages = fixture.state.pages.size,
                                totalArticles = fixture.state.items.count { it is ArticleFlowItem.Article }, isSyncing = false,
                                navigationKey = fixture.pager.value,
                                onNavigateUp = { fixture.actions += "back" }, onFilterChange = { fixture.filters += it; fixture.filter.value = it },
                                onRefresh = { fixture.actions += "refresh" }, onMarkAllAsRead = { fixture.actions += "mark-all" },
                                onSearch = { fixture.actions += "search" }, onPrev = { fixture.turn(false) }, onNext = { fixture.turn(true) })
                        }) {
                        BoxWithConstraints(Modifier.fillMaxSize().testTag("safe-screen-content")) {
                            val layout = resolveReadingLayout(maxWidth.value, LocalDensity.current.fontScale)
                            val size = if (fixture.paneHeight.value == Dp.Unspecified) Modifier.fillMaxSize()
                                else Modifier.fillMaxWidth().height(fixture.paneHeight.value)
                            EInkNewspaperViewport(state = fixture.state, columns = layout.columns,
                                horizontalPadding = layout.horizontalPaddingDp.dp, columnSpacing = layout.columnSpacingDp.dp,
                                modifier = size, diffMap = fixture.diffMap.value, onClick = { article, _ -> fixture.opened += article.article.id },
                                onToggleRead = { article ->
                                    fixture.toggled += article.article.id
                                    if (fixture.liveActions.value) {
                                        val unread = !article.article.isUnread
                                        fixture.diffMap.value = mapOf(article.article.id to Diff(unread, article))
                                        fixture.updateArticle(article.article.id) { it.copy(isUnread = unread) }
                                    }
                                }, onToggleStarred = { article ->
                                    fixture.starred += article.article.id
                                    if (fixture.liveActions.value) fixture.updateArticle(article.article.id) { it.copy(isStarred = !article.article.isStarred) }
                                }, onTurn = { fixture.turn(it) })
                        }
                    }
                }
            }
        }
    }

    private fun awaitEdition(fixture: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(15_000) { fixture.state.pages.isNotEmpty() }
        compose.waitForIdle()
        assertWholePage(fixture)
    }

    private fun assertWholePage(fixture: Fixture): List<String> {
        compose.waitForIdle()
        val expected = compose.runOnIdle { fixture.state.displayedArticles.map { it.article.id } }
        assertTrue("Every nonempty screen has at least one complete preview", expected.isNotEmpty())
        val viewport = compose.onNodeWithTag("newspaper-viewport").getUnclippedBoundsInRoot()
        val safe = compose.onNodeWithTag("safe-screen-content").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("article-list-header").getUnclippedBoundsInRoot()
        assertTrue("Article viewport starts below actual compact header", viewport.top >= header.bottom - 1.dp)
        assertTrue("Actual Android safe-inset viewport contains the page", viewport.bottom <= safe.bottom + 1.dp)
        val safeNode = compose.onNodeWithTag("safe-screen-content").fetchSemanticsNode()
        val rootView = (safeNode.root as ViewRootForTest).view
        compose.runOnUiThread {
            val insets = requireNotNull(rootView.rootWindowInsets).getInsets(android.view.WindowInsets.Type.systemBars())
            val window = requireNotNull(rootView.context.getSystemService(android.view.WindowManager::class.java)).currentWindowMetrics
            val origin = IntArray(2)
            rootView.getLocationOnScreen(origin)
            assertTrue("Whole content ends above the actual Android navigation inset",
                origin[1] + safeNode.boundsInRoot.bottom <= window.bounds.bottom - insets.bottom + 1f)
        }
        val cards = compose.onAllNodes(SemanticsMatcher("entire article preview") {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag].startsWith("article-block-")
        }, useUnmergedTree = true).fetchSemanticsNodes()
        val actual = cards.map { it.config[SemanticsProperties.TestTag].removePrefix("article-block-") }
        assertEquals("Only the actual page's article IDs are composed, no measured/next-page bleed", expected, actual)
        for (id in expected) {
            val card = compose.onNodeWithTag("article-block-$id", useUnmergedTree = true).assertIsDisplayed()
            val bounds = card.getUnclippedBoundsInRoot()
            assertTrue("Whole preview $id must fit, including final metadata: $bounds in $viewport",
                bounds.top >= viewport.top - 1.dp && bounds.bottom <= viewport.bottom + 1.dp &&
                    bounds.left >= viewport.left - 1.dp && bounds.right <= viewport.right + 1.dp)
            assertTrue("A preview cannot disappear into a zero-height measurement", bounds.bottom - bounds.top > 0.dp)
            val headline = hasTestTag("article-headline") and hasAnyAncestor(hasTestTag("article-block-$id"))
            compose.onNode(headline, useUnmergedTree = true).assertIsDisplayed()
            for (tag in listOf("article-headline", "article-description", "article-photo", "article-time")) {
                val children = compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("article-block-$id")), useUnmergedTree = true)
                children.fetchSemanticsNodes().indices.forEach { index ->
                    val child = children[index].getUnclippedBoundsInRoot()
                    assertTrue("$id/$tag may not be clipped below its complete preview: $child in $bounds",
                        child.top >= bounds.top - 1.dp && child.bottom <= bounds.bottom + 1.dp)
                }
            }
        }
        val scrollRanges = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            (hasTestTag("newspaper-viewport") or hasAnyAncestor(hasTestTag("newspaper-viewport"))), useUnmergedTree = true)
        // Accessibility exposes a discrete page number, never a within-page pixel offset.
        scrollRanges.assertCountEquals(1)
        val range = scrollRanges.onFirst().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle {
            assertEquals(fixture.state.currentPage.toFloat(), range.value(), 0f)
            assertEquals((fixture.state.pages.size - 1).toFloat(), range.maxValue(), 0f)
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyAncestor(hasTestTag("newspaper-viewport")), useUnmergedTree = true).assertCountEquals(0)
        return expected
    }

    private fun pageIds(fixture: Fixture) = compose.runOnIdle {
        fixture.state.pages.map { page -> page.indices.mapNotNull { index ->
            (fixture.state.items[index] as? ArticleFlowItem.Article)?.articleWithFeed?.article?.id
        } }
    }

    private fun wholeBlockBounds(fixture: Fixture) = assertWholePage(fixture).associateWith {
        compose.onNodeWithTag("article-block-$it", useUnmergedTree = true).getUnclippedBoundsInRoot()
    }

    private fun nextWithHeader() {
        compose.onNodeWithTag("article-page-control").performClick()
        compose.onNodeWithTag("article-page-next").assertIsEnabled().performClick()
        compose.onNodeWithTag("article-page-dialog").assertDoesNotExist()
    }

    private fun previousWithHeader() {
        compose.onNodeWithTag("article-page-control").performClick()
        compose.onNodeWithTag("article-page-previous").assertIsEnabled().performClick()
    }

    private fun articles(photos: List<String>, oversized: Boolean = false, count: Int = 24): List<ArticleFlowItem> {
        val feed = Feed(id = "viewport-feed", name = "程序员的那些事与每日人文观察", url = "https://example.com",
            groupId = "g", accountId = 1)
        val titles = listOf("TikTok 周受资：写代码只占工程师约三成时间，理解用户和设计仍然不可替代",
            "淡人独居提升幸福感的小细节：在城市中寻找安静、自然与日常生活的秩序",
            "iPhone Duo 开启预购，发货排到明年：华为阔直板出货量超过七十一万台",
            "路边的野果颜色漂亮到我们当场停住，重新认识城市公园里的植物与季节",
            "福柯诞辰百年：谈福柯、西方文化的考古学家，以及今天重新阅读他的意义")
        return listOf(ArticleFlowItem.Date("2026年10月21日 星期三", false)) + (0 until count).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "viewport-$index", date = Date(0),
                title = "${index + 1}. ${titles[index % titles.size]}" + if (oversized) "\n超长标题仍须保留完整预览边界，不得把下一篇文章切成两半。".repeat(10) else "",
                rawDescription = "", shortDescription = "2026-10-21 浙江 近日，在公开场合谈到人工智能、城市生活和文化观察。我们希望读者能从容看完每一张真正适合屏幕的报纸页面，不必在页内继续滚动寻找被裁切的文章。".repeat(if (oversized) 8 else 2),
                img = photos.takeIf { it.isNotEmpty() && index % 4 != 3 }?.let { it[index % it.size] },
                link = "about:blank", feedId = feed.id, accountId = 1, isUnread = true).apply { dateString = "${9 + index % 12}:35 PM" }, feed))
        }
    }

    private fun photoUrls(): List<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = Coil.imageLoader(context)
        val cache = requireNotNull(loader.diskCache)
        val source = InstrumentationRegistry.getInstrumentation().context.assets.open("nasa-earthrise.jpg").use {
            android.graphics.BitmapFactory.decodeStream(it)
        }
        return listOf(640 to 360, 320 to 640, 480 to 480).mapIndexed { index, size ->
            val url = "https://offline-fixture.invalid/viewport-photo-$index-${System.nanoTime()}.jpg"
            val editor = requireNotNull(cache.openEditor(url))
            val bitmap = Bitmap.createScaledBitmap(source, size.first, size.second, true)
            editor.data.toFile().outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            editor.metadata.toFile().writeBytes(byteArrayOf())
            editor.commit()
            url
        }
    }

    private fun waitForPhotograph() {
        compose.waitUntil(10_000) {
            val photos = compose.onAllNodes(hasTestTag("article-photo") and hasAnyAncestor(
                SemanticsMatcher("displayed article block") {
                    it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag].startsWith("article-block-")
                }), useUnmergedTree = true)
            if (photos.fetchSemanticsNodes().isEmpty()) return@waitUntil false
            val bitmap = photos.onFirst().captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.count { Color.blue(it) > Color.red(it) + 8 && Color.blue(it) > Color.green(it) + 2 } > 20
        }
    }

    private fun waitForHostFocus(host: SemanticsNodeInteraction) {
        val root = host.fetchSemanticsNode().root as ViewRootForTest
        compose.waitUntil(5_000) { compose.runOnUiThread { root.view.isAttachedToWindow && root.view.hasWindowFocus() } }
        compose.runOnIdle { assertTrue("Dismissal leaves host resumed", root.isLifecycleInResumedState) }
    }

    private fun modalRoot(dialog: SemanticsNodeInteraction): ViewRootForTest {
        val root = dialog.assertIsDisplayed().fetchSemanticsNode().root as ViewRootForTest
        compose.waitUntil(5_000) { compose.runOnUiThread { root.view.isAttachedToWindow && root.view.hasWindowFocus() } }
        return root
    }

    private fun assertStaticDialog(dialog: SemanticsNodeInteraction) {
        val root = modalRoot(dialog)
        compose.runOnUiThread {
            var parent = root.view.parent
            while (parent != null && parent !is DialogWindowProvider) parent = parent.parent
            assertEquals(0, requireNotNull(parent as? DialogWindowProvider).window.attributes.windowAnimations)
        }
        val before = dialog.getUnclippedBoundsInRoot()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("E-ink menu stays still after opening", before, dialog.getUnclippedBoundsInRoot())
    }

    private fun pressBackFromModal(dialog: SemanticsNodeInteraction, host: SemanticsNodeInteraction) {
        val root = modalRoot(dialog)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.runOnUiThread { !root.view.isAttachedToWindow } }
        dialog.assertDoesNotExist()
        waitForHostFocus(host)
    }

    private fun dismissOutside(dialog: SemanticsNodeInteraction, host: SemanticsNodeInteraction) {
        val root = modalRoot(dialog)
        val hostRoot = host.fetchSemanticsNode().root as ViewRootForTest
        val modalOrigin = IntArray(2)
        val hostOrigin = IntArray(2)
        compose.runOnUiThread { root.view.getLocationOnScreen(modalOrigin); hostRoot.view.getLocationOnScreen(hostOrigin) }
        val bounds = dialog.fetchSemanticsNode().boundsInRoot
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val x = hostOrigin[0] + hostBounds.left + 2f
        val y = hostOrigin[1] + hostBounds.top + 2f
        assertTrue(x < modalOrigin[0] + bounds.left || x > modalOrigin[0] + bounds.right ||
            y < modalOrigin[1] + bounds.top || y > modalOrigin[1] + bounds.bottom)
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 16 else 0, action, x, y, 0)
            try { InstrumentationRegistry.getInstrumentation().sendPointerSync(event) } finally { event.recycle() }
        }
        compose.waitUntil(5_000) { compose.runOnUiThread { !root.view.isAttachedToWindow } }
        waitForHostFocus(host)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val additionalOutput = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = if (additionalOutput.isNullOrBlank()) File(context.getExternalFilesDir(null), "newspaper-screenshots") else File(additionalOutput)
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, "newspaper-$name-${context.resources.configuration.screenWidthDp}.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(output.length() > 0)
    }
}

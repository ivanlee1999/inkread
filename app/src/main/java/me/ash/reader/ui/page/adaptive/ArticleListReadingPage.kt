package me.ash.reader.ui.page.adaptive

import android.os.Parcelable
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.text.LocalBackgroundTextMeasurementExecutor
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.ash.reader.ui.component.reader.LocalTextContentWidth
import me.ash.reader.ui.component.reader.MediumContentWidth
import me.ash.reader.ui.page.home.flow.FlowPage
import me.ash.reader.ui.page.home.reading.ReadingPage

@Parcelize data class ArticleData(val articleId: String, val listIndex: Int? = null) : Parcelable

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ArticleListReaderPage(
    modifier: Modifier = Modifier,
    navigator: ThreePaneScaffoldNavigator<ArticleData>,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    viewModel: ArticleListReaderViewModel,
    onBack: () -> Unit,
    onNavigateToStylePage: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val textMeasurementExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(textMeasurementExecutor) {
        onDispose { textMeasurementExecutor.shutdown() }
    }
    val openArticle: (String, Int) -> Unit = { id, index ->
        // Claim reader ownership in the click itself, before queued list-volume events or the next
        // composition can run. The destination effect below does not restart the same article.
        if (viewModel.readerStateStateFlow.value.articleId != id) viewModel.initData(id, index)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            navigator.navigateTo(
                pane = ListDetailPaneScaffoldRole.Detail,
                contentKey = ArticleData(articleId = id, listIndex = index),
            )
        }
    }

    ArticleListReaderScaffold(
        modifier = modifier,
        navigator = navigator,
        onDestinationChanged = { destination ->
            if (destination == null) viewModel.clearReadingData()
            else if (viewModel.readerStateStateFlow.value.articleId != destination.articleId) {
                viewModel.initData(destination.articleId, destination.listIndex)
            }
        },
        listPane = {
            CompositionLocalProvider(LocalBackgroundTextMeasurementExecutor provides textMeasurementExecutor) {
                FlowPage(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    viewModel = viewModel,
                    onNavigateUp = onBack,
                    isTwoPane = false,
                    navigateToArticle = openArticle,
                )
            }
        },
        detailPane = { closeReader ->
            CompositionLocalProvider(LocalTextContentWidth provides MediumContentWidth) {
                ReadingPage(
                    viewModel = viewModel,
                    navigationAction = NavigationAction.Close,
                    onLoadArticle = openArticle,
                    onNavAction = { closeReader() },
                    onNavigateToStylePage = onNavigateToStylePage,
                )
            }
        },
    )
}

/** The same route scaffold on phones, tablets and OS split-screen: never reserve a second pane. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun ArticleListReaderScaffold(
    navigator: ThreePaneScaffoldNavigator<ArticleData>,
    onDestinationChanged: (ArticleData?) -> Unit,
    modifier: Modifier = Modifier,
    listPane: @Composable () -> Unit,
    detailPane: @Composable (onBack: () -> Unit) -> Unit,
) {
    val closeReader = rememberReaderBackAction(navigator, onDestinationChanged)
    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        scaffoldState = navigator.scaffoldState,
        modifier = modifier,
        listPane = {
            AnimatedPane(
                enterTransition = EnterTransition.None,
                exitTransition = ExitTransition.None,
            ) { listPane() }
        },
        detailPane = {
            AnimatedPane(
                enterTransition = EnterTransition.None,
                exitTransition = ExitTransition.None,
            ) { detailPane(closeReader) }
        },
    )
}

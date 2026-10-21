package me.ash.reader.ui.page.adaptive

import androidx.activity.compose.BackHandler
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Each reading route owns its history, including a list underneath an external article link. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun rememberArticleListNavigator(
    scaffoldDirective: PaneScaffoldDirective,
    initialArticleId: String? = null,
): ThreePaneScaffoldNavigator<ArticleData> =
    rememberListDetailPaneScaffoldNavigator<ArticleData>(
        // Keep the actual window bounds (including OS split-screen), but never allocate an
        // internal side-by-side reader. The article newspaper uses the entire available width.
        scaffoldDirective = scaffoldDirective.copy(maxHorizontalPartitions = 1, maxVerticalPartitions = 1),
        isDestinationHistoryAware = false,
        initialDestinationHistory = buildList {
            add(ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List))
            initialArticleId?.let {
                add(ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail, ArticleData(it)))
            }
        },
    )

/**
 * Lives outside both panes: a hidden/disposed detail cannot cancel reader cleanup. Register before
 * pane contents so their search, selection and dialog Back handlers retain priority. A committed
 * system/predictive Back and the visible reader Back use the same action, without a gesture animation.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun rememberReaderBackAction(
    navigator: ThreePaneScaffoldNavigator<ArticleData>,
    onDestinationChanged: (ArticleData?) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val renderedDestination = navigator.currentDestination
    val destination = renderedDestination?.contentKey
    val currentOnDestinationChanged by rememberUpdatedState(onDestinationChanged)
    LaunchedEffect(destination) { currentOnDestinationChanged(destination) }

    val closeReader: () -> Unit = {
        // Read the live destination, not the last rendered one. A repeated/stale Close must
        // never pop the enclosing route or another opening of even the same article.
        if (destination != null && navigator.currentDestination === renderedDestination &&
            navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // This behavior compares pane roles, not article keys: A -> B -> C closes to List.
                val behavior = BackNavigationBehavior.PopUntilCurrentDestinationChange
                if (navigator.canNavigateBack(behavior)) navigator.navigateBack(behavior)
                else navigator.navigateTo(ListDetailPaneScaffoldRole.List)
            }
        }
    }

    BackHandler(enabled = destination != null, onBack = closeReader)
    return closeReader
}

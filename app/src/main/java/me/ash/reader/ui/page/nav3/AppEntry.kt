package me.ash.reader.ui.page.nav3

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.ui.page.annotation.AnnotationNotebookPage
import me.ash.reader.ui.page.annotation.AnnotationNotebookReadingPage
import me.ash.reader.ui.page.annotation.AnnotationNotebookSavedReadingPage
import me.ash.reader.ui.page.adaptive.rememberArticleListNavigator
import me.ash.reader.ui.page.adaptive.ArticleListReaderPage
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.home.feeds.FeedsPage
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeViewModel
import me.ash.reader.ui.page.library.SavedLibraryPage
import me.ash.reader.ui.page.library.SavedLibraryReadingPage
import me.ash.reader.ui.page.library.SavedCaptureViewModel
import me.ash.reader.ui.page.library.LinkCaptureMode
import me.ash.reader.ui.page.nav3.key.Route
import me.ash.reader.ui.page.settings.readwise.ReadwiseSettingsPage
import me.ash.reader.ui.page.settings.SettingsPage
import me.ash.reader.ui.page.settings.accounts.AccountDetailsPage
import me.ash.reader.ui.page.settings.accounts.AccountViewModel
import me.ash.reader.ui.page.settings.accounts.AccountsPage
import me.ash.reader.ui.page.settings.accounts.AddAccountsPage
import me.ash.reader.ui.page.settings.color.ColorAndStylePage
import me.ash.reader.ui.page.settings.color.DarkThemePage
import me.ash.reader.ui.page.settings.color.feeds.FeedsPageStylePage
import me.ash.reader.ui.page.settings.color.flow.FlowPageStylePage
import me.ash.reader.ui.page.settings.color.reading.BoldCharactersPage
import me.ash.reader.ui.page.settings.color.reading.ReadingImagePage
import me.ash.reader.ui.page.settings.color.reading.ReadingStylePage
import me.ash.reader.ui.page.settings.color.reading.ReadingTextPage
import me.ash.reader.ui.page.settings.color.reading.ReadingTitlePage
import me.ash.reader.ui.page.settings.color.reading.ReadingVideoPage
import me.ash.reader.ui.page.settings.interaction.InteractionPage
import me.ash.reader.ui.page.settings.languages.LanguagesPage
import me.ash.reader.ui.page.settings.tips.LicenseListPage
import me.ash.reader.ui.page.settings.tips.TipsAndSupportPage
import me.ash.reader.ui.page.settings.troubleshooting.TroubleshootingPage
import me.ash.reader.ui.page.startup.StartupPage


@OptIn(
    ExperimentalSharedTransitionApi::class,
    ExperimentalMaterial3AdaptiveApi::class,
    ExperimentalMaterial3AdaptiveApi::class,
)
@Composable
fun AppEntry(
    backStack: NavBackStack<NavKey>,
    subscribeViewModel: SubscribeViewModel = hiltViewModel(),
    captureViewModel: SavedCaptureViewModel = hiltViewModel(),
) {

    val currentRoute = backStack.lastOrNull()
    val onBack: () -> Unit = { navigateAppBack(backStack, currentRoute, Route.Feeds) }

    // NavDisplay only handles Back when there is a previous entry. A standalone external article
    // link still has an in-app home; its reader handler takes priority while the article is open.
    BackHandler(backStack.size == 1 && currentRoute != Route.Feeds && currentRoute != Route.Startup) {
        onBack()
    }

    val scaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())


    SharedTransitionLayout {
        val noAnimTransition = { EnterTransition.None togetherWith ExitTransition.None }

        NavDisplay(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            backStack = backStack,
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            // Navigation is a static page replacement, including Saved on LCD devices.
            transitionSpec = { noAnimTransition() },
            popTransitionSpec = { noAnimTransition() },
            predictivePopTransitionSpec = { noAnimTransition() },
            onBack = onBack,
            entryProvider = { key ->
                // Outgoing entries can remain composed during a transition. Their toolbar must
                // only pop their own entry, never whatever newer route is now on top.
                val onBack: () -> Unit = { navigateAppBack(backStack, key, Route.Feeds) }
                when (key) {
                    Route.Feeds -> {
                        NavEntry(key) {
                            FeedsPage(
                                subscribeViewModel = subscribeViewModel,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                                navigateToSettings = { backStack.add(Route.Settings) },
                                navigateToSavedLibrary = { backStack.add(Route.SavedLibrary) },
                                navigationToFlow = { backStack.add(Route.Reading(null)) },
                                navigateToAccountList = { backStack.add(Route.Accounts) },
                                navigateToAccountDetail = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                            )
                        }
                    }
                    Route.SavedLibrary -> NavEntry(key) {
                        SavedLibraryPage(
                            onBack = onBack,
                            onOpen = { backStack.add(Route.SavedReading(it)) },
                            onCapture = { captureViewModel.enqueue(it, mode = LinkCaptureMode.SAVE) },
                            onHighlights = { showRetainedDestination(backStack, Route.Highlights) },
                        )
                    }
                    Route.Highlights -> NavEntry(key) {
                        AnnotationNotebookPage(onBack = onBack, onOpen = { annotation ->
                            // Ignore any late lookup from a notebook that is no longer on top.
                            if (backStack.lastOrNull() == key) when (annotation.source.kind) {
                                AnnotationSourceKind.SAVED -> backStack.add(Route.SavedAnnotationReading(annotation.id, annotation.source.sourceId))
                                AnnotationSourceKind.RSS -> annotation.source.accountId?.let { accountId ->
                                    backStack.add(Route.AnnotationReading(annotation.id, annotation.source.sourceId, accountId))
                                }
                            }
                        })
                    }
                    is Route.AnnotationReading -> NavEntry(key) {
                        AnnotationNotebookReadingPage(annotationId = key.annotationId, sourceId = key.sourceId,
                            accountId = key.accountId, onBack = onBack,
                            onNavigateToStylePage = { backStack.add(Route.ReadingPageStyle) })
                    }
                    is Route.SavedAnnotationReading -> NavEntry(key) {
                        AnnotationNotebookSavedReadingPage(annotationId = key.annotationId, itemId = key.itemId, onBack = onBack)
                    }
                    is Route.SavedReading -> NavEntry(key) {
                        SavedLibraryReadingPage(itemId = key.itemId, onBack = onBack)
                    }
                    is Route.Reading -> {
                        NavEntry(key) {
                            val navigator = rememberArticleListNavigator(scaffoldDirective, key.articleId)

                            val viewModel = hiltViewModel<ArticleListReaderViewModel>()

                            ArticleListReaderPage(
                                navigator = navigator,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                                viewModel = viewModel,
                                onBack = onBack,
                                onNavigateToStylePage = { backStack.add(Route.ReadingPageStyle) },
                            )
                        }
                    }
                    //                    is Route.Reading -> {
                    //                        NavEntry(key) {
                    //                            val articleId = key.articleId
                    //
                    //                            val readingViewModel: ReadingViewModel =
                    //                                hiltViewModel<
                    //                                    ReadingViewModel,
                    //                                    ReadingViewModel.ReadingViewModelFactory,
                    //                                > { factory ->
                    //                                    factory.create(articleId.toString(), null)
                    //                                }
                    //
                    //                            ReadingPage(
                    //                                readingViewModel = readingViewModel,
                    //                                onBack = onBack,
                    //                                onNavigateToStylePage = {
                    // backStack.add(Route.ReadingPageStyle) },
                    //                            )
                    //                        }
                    //                    }
                    Route.Startup -> {
                        NavEntry(key) {
                            StartupPage(onNavigateToFeeds = {
                                if (backStack.lastOrNull() == Route.Startup) {
                                    backStack.removeLastOrNull()
                                    backStack.add(Route.Feeds)
                                }
                            })
                        }
                    }
                    Route.Settings ->
                        NavEntry(key) {
                            SettingsPage(
                                onBack = onBack,
                                navigateToAccounts = { backStack.add(Route.Accounts) },
                                navigateToReadwise = { backStack.add(Route.ReadwiseSettings) },
                                navigateToColorAndStyle = { backStack.add(Route.ColorAndStyle) },
                                navigateToInteraction = { backStack.add(Route.Interaction) },
                                navigateToLanguages = { backStack.add(Route.Languages) },
                                navigateToTroubleshooting = {
                                    backStack.add(Route.Troubleshooting)
                                },
                                navigateToTipsAndSupport = { backStack.add(Route.TipsAndSupport) },
                            )
                        }
                    Route.ReadwiseSettings -> NavEntry(key) { ReadwiseSettingsPage(onBack = onBack) }
                    Route.Accounts ->
                        NavEntry(key) {
                            AccountsPage(
                                onBack = onBack,
                                navigateToAddAccount = { backStack.add(Route.AddAccounts) },
                                navigateToAccountDetails = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                            )
                        }
                    is Route.AccountDetails ->
                        NavEntry(key) {
                            AccountDetailsPage(
                                viewModel =
                                    hiltViewModel<AccountViewModel>().also {
                                        it.initData(key.accountId)
                                    },
                                onBack = onBack,
                                navigateToFeeds = { backStack.add(Route.Feeds) },
                            )
                        }
                    Route.AddAccounts ->
                        NavEntry(key) {
                            AddAccountsPage(
                                onBack = onBack,
                                navigateToAccountDetails = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                            )
                        }
                    Route.ColorAndStyle ->
                        NavEntry(key) {
                            ColorAndStylePage(
                                onBack = onBack,
                                navigateToDarkTheme = { backStack.add(Route.DarkTheme) },
                                navigateToFeedsPageStyle = { backStack.add(Route.FeedsPageStyle) },
                                navigateToFlowPageStyle = { backStack.add(Route.FlowPageStyle) },
                                navigateToReadingPageStyle = {
                                    backStack.add(Route.ReadingPageStyle)
                                },
                            )
                        }
                    Route.DarkTheme -> NavEntry(key) { DarkThemePage(onBack = onBack) }
                    Route.FeedsPageStyle -> NavEntry(key) { FeedsPageStylePage(onBack = onBack) }
                    Route.FlowPageStyle -> NavEntry(key) { FlowPageStylePage(onBack = onBack) }
                    Route.ReadingPageStyle ->
                        NavEntry(key) {
                            ReadingStylePage(
                                onBack = onBack,
                                navigateToReadingBoldCharacters = {
                                    backStack.add(Route.ReadingBoldCharacters)
                                },
                                navigateToReadingPageTitle = {
                                    backStack.add(Route.ReadingPageTitle)
                                },
                                navigateToReadingPageText = {
                                    backStack.add(Route.ReadingPageText)
                                },
                                navigateToReadingPageImage = {
                                    backStack.add(Route.ReadingPageImage)
                                },
                                navigateToReadingPageVideo = {
                                    backStack.add(Route.ReadingPageVideo)
                                },
                            )
                        }
                    Route.ReadingBoldCharacters ->
                        NavEntry(key) { BoldCharactersPage(onBack = onBack) }
                    Route.ReadingPageTitle -> NavEntry(key) { ReadingTitlePage(onBack = onBack) }
                    Route.ReadingPageText -> NavEntry(key) { ReadingTextPage(onBack = onBack) }
                    Route.ReadingPageImage -> NavEntry(key) { ReadingImagePage(onBack = onBack) }
                    Route.ReadingPageVideo -> NavEntry(key) { ReadingVideoPage(onBack = onBack) }
                    Route.Interaction -> NavEntry(key) { InteractionPage(onBack = onBack) }
                    Route.Languages -> NavEntry(key) { LanguagesPage(onBack = onBack) }
                    Route.Troubleshooting -> NavEntry(key) { TroubleshootingPage(onBack = onBack) }
                    Route.TipsAndSupport ->
                        NavEntry(key) {
                            TipsAndSupportPage(
                                onBack = onBack,
                                navigateToLicenseList = { backStack.add(Route.LicenseList) },
                            )
                        }
                    Route.LicenseList -> NavEntry(key) { LicenseListPage(onBack = onBack) }
                    else -> NavEntry(key) { throw Exception("Unknown destination") }
                }
            },
        )
    }
}

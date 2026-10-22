package me.ash.reader.infrastructure.android

import android.Manifest
import android.content.Intent
import android.database.CursorWindow
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.Consumer
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.profileinstaller.ProfileInstallerInitializer
import androidx.work.WorkManager
import coil.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import java.lang.reflect.Field
import javax.inject.Inject
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.WidgetUpdateWorker
import me.ash.reader.infrastructure.compose.ProvideCompositionLocals
import me.ash.reader.infrastructure.preference.AccountSettingsProvider
import me.ash.reader.infrastructure.preference.InitialPagePreference
import me.ash.reader.infrastructure.preference.LanguagesPreference
import me.ash.reader.infrastructure.preference.LocalDarkTheme
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.ext.initialPage
import me.ash.reader.ui.ext.isFirstLaunch
import me.ash.reader.ui.ext.languages
import me.ash.reader.ui.page.common.ExtraName
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeState
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeViewModel
import me.ash.reader.ui.page.library.SavedCaptureHost
import me.ash.reader.ui.page.library.SavedCaptureViewModel
import me.ash.reader.ui.page.library.LinkCaptureMode
import me.ash.reader.ui.page.library.externalLinkCaptureMode
import me.ash.reader.ui.page.nav3.showRetainedDestination
import me.ash.reader.ui.page.nav3.AppEntry
import me.ash.reader.ui.page.nav3.key.Route
import me.ash.reader.ui.page.nav3.key.Route.*
import me.ash.reader.ui.theme.AppTheme

/** The Single-Activity Architecture. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var deferredRssLaunch: LaunchAction? = null

    @Inject lateinit var imageLoader: ImageLoader

    @Inject lateinit var settingsProvider: SettingsProvider

    @Inject lateinit var accountService: AccountService

    @Inject lateinit var workManager: WorkManager

    @Inject lateinit var filterUseCase: FilterStateUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        // Intent extras can become SavedStateHandle defaults. A third-party share must not
        // impersonate a restored, user-approved capture transaction or success result.
        intent.extras?.keySet()?.filter { it.startsWith("saved_capture_") }?.forEach(intent::removeExtra)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val restoredLaunch = savedInstanceState?.getSerializable("deferred_rss_launch") as? LaunchAction
        deferredRssLaunch = restoredLaunch
        Log.i("RLog", "onCreate: ${ProfileInstallerInitializer().create(this)}")

        enableEdgeToEdge()

        // Set the language
        if (Build.VERSION.SDK_INT < 33) {
            LanguagesPreference.fromValue(languages).let { LanguagesPreference.setLocale(it) }
        }

        // Workaround for https://github.com/ReadYouApp/ReadYou/issues/312: increase cursor window
        // size
        try {
            val field: Field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024) // 100MB is the new cursor window size
        } catch (e: Exception) {
            Log.e("RLog", "Unable to increase cursor window size: ${e.printStackTrace()}")
        }

        val requestPermissionLauncher =
            this.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted
                ->
                if (isGranted) {} else { // Permission denied }
                }
            }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // A restored coordinator owns its pending request. Never replay the Activity's original
        // share after rotation/process restoration, cancellation or a completed save.
        val initialLaunchAction = if (savedInstanceState == null) intent.consumeLaunchAction() else null

        setContent {
            AccountSettingsProvider(accountService = accountService) {
                settingsProvider.ProvidesSettings {
                    val subscribeViewModel: SubscribeViewModel = hiltViewModel()
                    val captureViewModel: SavedCaptureViewModel = hiltViewModel()
                    val subscribeState by subscribeViewModel.subscribeState.collectAsState()

                    ProvideCompositionLocals {
                        AppTheme(useDarkTheme = LocalDarkTheme.current.isDarkTheme()) {
                            val isFirstLaunch = remember { isFirstLaunch }
                            val initialPage = remember { initialPage }
                            val startDestination = remember {
                                val ordinary = if (initialPage == InitialPagePreference.FlowPage.value)
                                    listOf(Route.Feeds, Route.Reading(null)) else listOf(Route.Feeds)
                                if (isFirstLaunch) listOf(Route.Startup)
                                else when (val launch = initialLaunchAction) {
                                    is LaunchAction.OpenArticle -> {
                                        filterUseCase.init(launch.feedId, launch.groupId)
                                        listOf(Route.Feeds, Route.Reading(launch.articleId))
                                    }
                                    is LaunchAction.OpenArticleList -> {
                                        if (launch.accountId == null || launch.accountId == accountService.getCurrentAccountId()) {
                                            filterUseCase.init(launch.feedId, launch.groupId)
                                            listOf(Route.Feeds, Route.Reading(null))
                                        } else ordinary
                                    }
                                    is LaunchAction.Capture -> if (launch.mode == LinkCaptureMode.SUBSCRIBE)
                                        listOf(Route.Feeds) else ordinary
                                    null -> ordinary
                                }
                            }

                            val backStack = rememberNavBackStack(*startDestination.toTypedArray())

                            // The initial non-onboarding RSS route was already selected synchronously.
                            // Only captures and onboarding-deferred RSS actions enter the listener path.
                            val deferredInitialAction = initialLaunchAction.takeIf {
                                isFirstLaunch || it is LaunchAction.Capture
                            }
                            NewIntentHandlerEffect(backStack, captureViewModel, deferredInitialAction)
                            AppEntry(backStack, subscribeViewModel, captureViewModel)
                            SavedCaptureHost(
                                viewModel = captureViewModel,
                                ready = backStack.lastOrNull() != Route.Startup && subscribeState is SubscribeState.Hidden,
                                onSubscribe = { url ->
                                    showRetainedDestination(backStack, Route.Feeds)
                                    subscribeViewModel.handleSharedUrlFromIntent(url)
                                },
                                onSaved = { showRetainedDestination(backStack, Route.SavedLibrary) },
                                onOpenSaved = { backStack.add(Route.SavedReading(it)) },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NewIntentHandlerEffect(
        backStack: NavBackStack<NavKey>,
        captureViewModel: SavedCaptureViewModel,
        initialLaunchAction: LaunchAction?,
    ) {
        LaunchedEffect(backStack.lastOrNull()) {
            if (backStack.lastOrNull() != Route.Startup) deferredRssLaunch?.let {
                deferredRssLaunch = null
                handleRssLaunch(backStack, it)
            }
        }
        DisposableEffect(backStack, captureViewModel) {
            fun handle(action: LaunchAction?) {
                when (action) {
                    is LaunchAction.OpenArticle, is LaunchAction.OpenArticleList -> handleRssLaunch(backStack, action)
                    is LaunchAction.Capture -> captureViewModel.enqueue(action.text, action.titleHint, action.mode)
                    null -> Unit
                }
            }
            val listener = Consumer<Intent> { handle(it.consumeLaunchAction()) }
            handle(initialLaunchAction)
            addOnNewIntentListener(listener)
            onDispose { removeOnNewIntentListener(listener) }
        }
    }

    private fun handleRssLaunch(backStack: NavBackStack<NavKey>, action: LaunchAction) {
        if (backStack.lastOrNull() == Route.Startup) {
            deferredRssLaunch = action
            return
        }
        when (action) {
            is LaunchAction.OpenArticle -> {
                filterUseCase.init(action.feedId, action.groupId)
                val readingIndex = backStack.indexOfFirst { it is Route.Reading }
                if (readingIndex != -1) repeat(backStack.size - readingIndex) { backStack.removeLastOrNull() }
                backStack.add(Reading(action.articleId))
            }
            is LaunchAction.OpenArticleList -> {
                if (action.accountId != null && action.accountId != accountService.getCurrentAccountId()) return
                filterUseCase.init(action.feedId, action.groupId)
                val readingIndex = backStack.indexOfFirst { it is Route.Reading }
                if (readingIndex != -1) repeat(backStack.size - readingIndex) { backStack.removeLastOrNull() }
                backStack.add(Reading(null))
            }
            is LaunchAction.Capture -> Unit
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putSerializable("deferred_rss_launch", deferredRssLaunch)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (dispatchEInkVolumeKey(
                settingsProvider.settings.einkMode.isEInkMode(),
                event.keyCode,
                event.action,
                event.repeatCount,
            )) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Volume keys are already handled in dispatchKeyEvent; just consume them
        // here to prevent any fallthrough (e.g. from WebView focus stealing).
        if (settingsProvider.settings.einkMode.isEInkMode() && VolumeKeyEventBus.hasActiveConsumer()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (settingsProvider.settings.einkMode.isEInkMode() && VolumeKeyEventBus.hasActiveConsumer()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        WidgetUpdateWorker.enqueueOneTimeWork(workManager)
        super.onResume()
    }
}

internal sealed interface LaunchAction : java.io.Serializable {
    data class Capture(val text: String, val titleHint: String?, val mode: LinkCaptureMode) : LaunchAction

    data class OpenArticle(val articleId: String, val feedId: String?, val groupId: String?) :
        LaunchAction

    data class OpenArticleList(val accountId: Int?, val feedId: String?, val groupId: String?) :
        LaunchAction
}

internal fun Intent.consumeLaunchAction(): LaunchAction? {
    return when (action) {
        Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
            val mode = externalLinkCaptureMode(action, type) ?: return null
            val text = if (action == Intent.ACTION_SEND) getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: clipData?.getItemAt(0)?.text?.toString() ?: dataString else dataString
            val title = getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString() ?: getStringExtra(Intent.EXTRA_SUBJECT)
            removeExtra(Intent.EXTRA_TEXT)
            removeExtra(Intent.EXTRA_TITLE)
            removeExtra(Intent.EXTRA_SUBJECT)
            clipData = null
            // Consume the URL once without changing the delivery's MIME identity.
            // Intent.setData(null) also clears type, breaking lifecycle matching for SEND.
            setDataAndType(null, type)
            text?.let { LaunchAction.Capture(it, title, mode) }
        }

        else -> {
            val articleId =
                getStringExtra(ExtraName.ARTICLE_ID)?.also { removeExtra(ExtraName.ARTICLE_ID) }
            val feedId = getStringExtra(ExtraName.FEED_ID)?.also { removeExtra(ExtraName.FEED_ID) }
            val groupId =
                getStringExtra(ExtraName.GROUP_ID)?.also { removeExtra(ExtraName.GROUP_ID) }
            val accountId = getIntExtra(ExtraName.ACCOUNT_ID, -1)

            if (accountId != -1) {
                removeExtra(ExtraName.ACCOUNT_ID)
            }

            if (articleId != null) {
                LaunchAction.OpenArticle(articleId, feedId, groupId)
            } else if (feedId != null || groupId != null || accountId != -1) {
                LaunchAction.OpenArticleList(
                    accountId = if (accountId != -1) accountId else null,
                    feedId = feedId,
                    groupId = groupId,
                )
            } else {
                null
            }
        }
    }
}

package me.ash.reader.ui.page.settings.readwise

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import me.ash.reader.infrastructure.preference.AnnotationPreferences
import me.ash.reader.ui.ext.dataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ash.reader.domain.annotation.AnnotationExportState
import me.ash.reader.domain.annotation.AnnotationRepository
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.readwise.ReadwiseConnectionManager

data class ReadwiseQueueSummary(
    val queued: Int = 0,
    val sending: Int = 0,
    val exported: Int = 0,
    val failed: Int = 0,
    val reviewRequired: Int = 0,
    val paused: Int = 0,
    val localOnly: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class ReadwiseSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: ReadwiseConnectionManager,
    repository: AnnotationRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    val autoHighlight = AnnotationPreferences.observeAutoHighlight(context.dataStore)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setAutoHighlight(enabled: Boolean) = action {
        AnnotationPreferences.setAutoHighlight(context.dataStore, enabled)
        mutableMessage.value = if (enabled) "Automatic highlights are on. Selecting text saves locally; Undo is available in the selection menu. Readwise export remains a separate choice."
            else "Automatic highlights are off. Select text, then choose Highlight or Add note."
    }
    val connection = manager.state
    val queue = repository.observeAll().map { annotations ->
        val counts = annotations.groupingBy { it.exportState }.eachCount()
        ReadwiseQueueSummary(
            queued = counts[AnnotationExportState.QUEUED] ?: 0,
            sending = counts[AnnotationExportState.SENDING] ?: 0,
            exported = counts[AnnotationExportState.EXPORTED] ?: 0,
            failed = counts[AnnotationExportState.FAILED] ?: 0,
            reviewRequired = counts[AnnotationExportState.REVIEW_REQUIRED] ?: 0,
            paused = counts[AnnotationExportState.PAUSED] ?: 0,
            localOnly = counts[AnnotationExportState.LOCAL_ONLY] ?: 0,
        )
    }.catch { emit(ReadwiseQueueSummary(error = "The local annotation queue could not be loaded.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadwiseQueueSummary())
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableWorking = MutableStateFlow(false)
    val working = mutableWorking.asStateFlow()

    // The token is only a function argument during this action, never UI state,
    // SavedStateHandle, logs, analytics, navigation arguments, or an error message.
    fun connect(token: String, autoExport: Boolean) = action {
        val result = manager.connect(token, autoExport)
        mutableMessage.value = if (result.success) {
            if (autoExport) "Connected. New annotations will export automatically. Existing local annotations stay local."
            else "Connected with auto-export off. Annotations stay on this device."
        } else result.error ?: "Readwise could not connect. Local annotations are unchanged."
    }

    fun setExportEnabled(enabled: Boolean) = action {
        manager.setExportEnabled(enabled)
        mutableMessage.value = if (enabled) "Auto-export is on for this connection. Previously local-only annotations are not uploaded."
            else "Auto-export is paused. A request already sent to Readwise cannot be recalled."
    }

    fun disconnect() = action {
        manager.disconnect()
        mutableMessage.value = "Disconnected. Local annotations and existing Readwise copies are kept."
    }

    private fun action(block: suspend () -> Unit) {
        if (mutableWorking.value) return
        mutableWorking.value = true
        mutableMessage.value = null
        // Finishing an explicitly submitted connection change must not leave the
        // shared manager stuck busy when this settings route is closed mid-request.
        applicationScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableMessage.value = "Could not change the Readwise connection. Your local annotations remain safe." }
            finally { mutableWorking.value = false }
        }
    }
}

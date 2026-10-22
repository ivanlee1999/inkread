package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import me.ash.reader.ui.ext.dataStore

/** Null means no real preference snapshot has arrived; never substitute defaults for a draft. */
val LocalReaderFormatting = compositionLocalOf<ReaderFormatting?> { null }
val LocalReaderFormattingLoadError = compositionLocalOf<Throwable?> { null }
val LocalRetryReaderFormatting = compositionLocalOf<() -> Unit> { {} }

@Composable
fun rememberPersistedReaderFormatting(): State<ReaderFormatting?> =
    rememberUpdatedState(LocalReaderFormatting.current)

suspend fun saveReaderFormatting(context: Context, value: ReaderFormatting): ReaderFormatting =
    context.dataStore.saveReaderFormatting(value)

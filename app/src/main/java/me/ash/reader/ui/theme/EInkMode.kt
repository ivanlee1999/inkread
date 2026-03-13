package me.ash.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import me.ash.reader.infrastructure.preference.LocalEInkMode

val isEInkMode: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalEInkMode.current.isEInkMode()

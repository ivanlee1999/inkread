package me.ash.reader.ui.component.base

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.ash.reader.R
import me.ash.reader.ui.theme.isEInkMode

@Composable
fun RYLoadingIndicator(modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    if (isEInkMode) Icon(Icons.Rounded.HourglassEmpty, stringResource(R.string.loading), modifier, tint = color)
    else CircularProgressIndicator(modifier = modifier, color = color)
}

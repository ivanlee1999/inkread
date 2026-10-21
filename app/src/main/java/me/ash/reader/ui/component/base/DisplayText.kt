package me.ash.reader.ui.component.base

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ash.reader.ui.interaction.alphaIndicationClickable
import me.ash.reader.infrastructure.preference.BasicFontsPreference
import me.ash.reader.infrastructure.preference.LocalBasicFonts

@Composable
fun DisplayText(
    modifier: Modifier = Modifier,
    text: String,
    desc: String,
    onTextClick: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .alphaIndicationClickable(enabled = onTextClick != null) { onTextClick?.invoke() }
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 20.dp)
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = text,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = if (LocalBasicFonts.current == BasicFontsPreference.External)
                    MaterialTheme.typography.headlineLarge.fontFamily else FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        RYExtensibleVisibility(visible = desc.isNotEmpty(), animated = false) {
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

package me.ash.reader.ui.page.home.feeds

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ash.reader.domain.model.general.Filter

/** The edition masthead opens the current aggregate filter without moving decoration. */
@Composable
fun FeedsBanner(
    modifier: Modifier = Modifier,
    filter: Filter,
    desc: String? = null,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier.padding(horizontal = 20.dp)
            .fillMaxWidth().border(2.dp, MaterialTheme.colorScheme.onSurface)
            .clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null, role = Role.Button, onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(filter.toName(), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Icon(imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
    }
}

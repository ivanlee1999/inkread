package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ash.reader.infrastructure.preference.LocalReadingFonts
import me.ash.reader.infrastructure.preference.LocalReadingTitleAlign
import me.ash.reader.infrastructure.preference.LocalReadingTitleBold
import me.ash.reader.infrastructure.preference.LocalReadingTitleUpperCase
import me.ash.reader.ui.component.articleByline
import me.ash.reader.ui.ext.formatAsString
import me.ash.reader.ui.ext.requiresBidi
import me.ash.reader.ui.theme.applyTextDirection
import java.util.Date

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Metadata(
    feedName: String,
    title: String,
    publishedDate: Date,
    modifier: Modifier = Modifier,
    author: String? = null,
) {
    val context = LocalContext.current
    val titleBold = LocalReadingTitleBold.current
    val titleUpperCase = LocalReadingTitleUpperCase.current
    val titleAlign = LocalReadingTitleAlign.current.toTextAlign()
    val dateString =
        remember(publishedDate) { publishedDate.formatAsString(context, atHourMinute = true) }
    val fontFamily = LocalReadingFonts.current.asFontFamily(context)

    val displayTitle = if (titleUpperCase.value) title.uppercase() else title
    val metadata = listOf(articleByline(feedName, author), dateString)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(12.dp)
    ) {
        Text(
            modifier = Modifier.fillMaxWidth().semantics { heading() }.testTag("reader-headline"),
            text = displayTitle,
            color = MaterialTheme.colorScheme.onSurface,
            style =
                MaterialTheme.typography.headlineLarge
                    .merge(
                        fontFamily = fontFamily,
                        fontWeight = if (titleBold.value) FontWeight.Bold else FontWeight.Medium,
                    )
                    .applyTextDirection(requiresBidi = title.requiresBidi()),
            textAlign = titleAlign,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            modifier = Modifier.fillMaxWidth().testTag("reader-byline"),
            text = metadata,
            color = labelColor,
            style = MaterialTheme.typography.bodySmall.merge(fontFamily = fontFamily),
            textAlign = titleAlign,
        )
    }
}

package me.ash.reader.ui.page.home.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import me.ash.reader.R
import me.ash.reader.ui.component.base.EInkListVolumePageKeys
import me.ash.reader.ui.component.base.NewspaperMasthead

/** The existing masthead owns page navigation; the publication viewport has no pager footer. */
@Composable
fun FolderEditionHeader(
    title: String,
    folderCount: Int,
    publicationCount: Int,
    state: FolderNewspaperState,
    modifier: Modifier = Modifier,
    paged: Boolean = true,
    navigationEnabled: Boolean = true,
    onOpenAccounts: (() -> Unit)? = null,
) {
    var expanded by remember(state) { mutableStateOf(false) }
    val pageCount = state.pageCount
    val page = if (pageCount == 0) 0 else state.currentPage + 1
    val controlsLabel = stringResource(R.string.article_page_controls)
    val pageDescription = stringResource(R.string.article_page_position, page, pageCount)
    val totals = "${stringResource(R.string.folder)}: $folderCount · ${stringResource(R.string.feeds)}: $publicationCount"
    val accountAction = if (onOpenAccounts == null) Modifier else Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null, role = Role.Button, onClick = onOpenAccounts,
    )
    val details: (@Composable () -> Unit)? = if (paged) {
        {
            Box(
                Modifier.testTag("folder-page-count").sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, role = Role.Button, onClickLabel = controlsLabel,
                        onClick = { expanded = true },
                    ).semantics(mergeDescendants = true) { contentDescription = pageDescription }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    stringResource(R.string.article_page_position_short, page, pageCount),
                    modifier = Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else null
    NewspaperMasthead(
        title = title,
        details = if (paged) null else totals,
        detailsContent = details,
        modifier = modifier.testTag("folder-edition-header").then(accountAction),
    )
    EInkListVolumePageKeys(
        enabled = paged && navigationEnabled && !expanded && state.presentationAvailable && pageCount > 0,
        ownerKey = state,
        onTurn = { state.turn(it) },
    )
    if (paged && expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setWindowAnimations(0) }
            var target by remember { mutableStateOf(page.toString()) }
            val targetPage = target.toIntOrNull()
            val validTarget = state.presentationAvailable && targetPage != null && targetPage in 1..pageCount
            val goToLabel = stringResource(R.string.go_to)
            val jump = {
                if (validTarget) {
                    state.goToPage(targetPage!! - 1)
                    expanded = false
                }
            }
            Column(
                Modifier.testTag("folder-page-dialog").widthIn(max = 360.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(controlsLabel, Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(pageDescription, Modifier.testTag("folder-page-status"),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(totals, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderPageAction(stringResource(R.string.previous_page),
                        Modifier.weight(1f).testTag("folder-previous-page"),
                        enabled = state.presentationAvailable && state.currentPage > 0) {
                        state.turn(false)
                        expanded = false
                    }
                    FolderPageAction(stringResource(R.string.next_page),
                        Modifier.weight(1f).testTag("folder-next-page"),
                        enabled = state.presentationAvailable && state.currentPage + 1 < pageCount) {
                        state.turn(true)
                        expanded = false
                    }
                }
                Text(goToLabel, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                    BasicTextField(
                        value = target,
                        onValueChange = { target = it.filter(Char::isDigit).take(9) },
                        modifier = Modifier.testTag("folder-page-jump-input").fillMaxWidth().heightIn(min = 48.dp)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface)
                            .semantics { contentDescription = goToLabel }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { jump() }),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderPageAction(goToLabel, Modifier.weight(1f).testTag("folder-page-jump"), validTarget, jump)
                    FolderPageAction(stringResource(R.string.close),
                        Modifier.weight(1f).testTag("folder-page-close")) { expanded = false }
                }
            }
        }
    }
}

@Composable
private fun FolderPageAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button,
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

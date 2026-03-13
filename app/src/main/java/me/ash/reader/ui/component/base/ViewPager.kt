package me.ash.reader.ui.component.base

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.ash.reader.ui.theme.isEInkMode

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewPager(
    modifier: Modifier = Modifier,
    composableList: List<@Composable () -> Unit>,
    userScrollEnabled: Boolean = true,
) {
    val einkMode = isEInkMode
    HorizontalPager(
        state = rememberPagerState { composableList.size },
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .then(if (einkMode) Modifier else Modifier.animateContentSize())
            .wrapContentHeight()
            .fillMaxWidth(),
        userScrollEnabled = userScrollEnabled,
    ) { page ->
        composableList[page]()
    }
}

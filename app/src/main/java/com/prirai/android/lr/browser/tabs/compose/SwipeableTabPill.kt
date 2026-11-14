package com.prirai.android.lr.browser.tabs.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.prirai.android.lr.R
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwipeableTabPill(
    tab: TabSessionState,
    isSelected: Boolean,
    groupColor: Int? = null,
    onTabClick: () -> Unit,
    onTabClose: () -> Unit,
    onShowMenu: () -> Unit,
    modifier: Modifier = Modifier,
    swipeThreshold: Float = 40f
) {
    val maxOffset = 80f
    val scope = rememberCoroutineScope()
    val releaseSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

    val offsetY = remember(tab.id) { Animatable(0f) }

    Box(
        modifier = modifier
            .height(36.dp)
            .width(124.dp)
    ) {
        if (abs(offsetY.value) > 5f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        when {
                            offsetY.value < -5f -> MaterialTheme.colorScheme.errorContainer // Swipe up - delete
                            offsetY.value > 5f -> MaterialTheme.colorScheme.primaryContainer // Swipe down - menu
                            else -> Color.Transparent
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (offsetY.value < 0) Icons.Default.Delete else Icons.Default.MoreVert,
                    contentDescription = stringResource(if (offsetY.value < 0) R.string.delete else R.string.more_options),
                    tint = if (offsetY.value < 0)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .pointerInput(tab.id) {
                    var totalHorizontalDrag = 0f
                    var totalVerticalDrag = 0f

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        totalHorizontalDrag = 0f
                        totalVerticalDrag = 0f

                        do {
                            val event = awaitPointerEvent()
                            val drag = event.changes.firstOrNull()

                            if (drag != null && drag.pressed) {
                                val deltaX = drag.position.x - drag.previousPosition.x
                                val deltaY = drag.position.y - drag.previousPosition.y

                                totalHorizontalDrag += abs(deltaX)
                                totalVerticalDrag += abs(deltaY)

                                if (totalVerticalDrag > totalHorizontalDrag && totalVerticalDrag > 10f) {
                                    drag.consume()
                                    scope.launch {
                                        offsetY.snapTo((offsetY.value + deltaY).coerceIn(-maxOffset, maxOffset))
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (totalVerticalDrag > totalHorizontalDrag && totalVerticalDrag > 10f) {
                            when {
                                offsetY.value < -swipeThreshold -> {
                                    onTabClose()
                                }

                                offsetY.value > swipeThreshold -> {
                                    onShowMenu()
                                }
                            }
                        }

                        scope.launch { offsetY.animateTo(0f, animationSpec = releaseSpec) }
                    }
                },
            shape = RoundedCornerShape(18.dp),
            color = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
            border = if (isSelected && groupColor != null) {
                BorderStroke(2.dp, Color(groupColor).copy(alpha = 0.9f))
            } else {
                null
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onTabClick)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FaviconImage(
                    tab = tab,
                    size = 16.dp
                )

                Text(
                    text = getTabDisplayTitle(tab, stringResource(R.string.tab_history_new_tab)),
                    style =
                        if (isSelected) {
                            MaterialTheme.typography.labelSmallEmphasized
                        } else {
                            MaterialTheme.typography.labelSmall
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

fun getTabDisplayTitle(tab: TabSessionState, newTabTitle: String): String {
    return when {
        tab.content.url == "about:homepage" && tab.content.title.isEmpty() -> newTabTitle
        tab.content.title.isNotEmpty() -> tab.content.title
        tab.content.url.isNotEmpty() && tab.content.url != "about:homepage" -> tab.content.url
        else -> newTabTitle
    }
}

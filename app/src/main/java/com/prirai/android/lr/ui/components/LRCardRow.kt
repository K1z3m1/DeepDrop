package com.prirai.android.lr.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun LRCardRow(
    title: String,
    isTopRow: Boolean,
    isBottomRow: Boolean,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = 24.dp,
    dividerIndent: Dp = 48.dp,
    minRowHeight: Dp = 48.dp,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(
        topStart = if (isTopRow) cornerRadius else 0.dp,
        topEnd = if (isTopRow) cornerRadius else 0.dp,
        bottomStart = if (isBottomRow) cornerRadius else 0.dp,
        bottomEnd = if (isBottomRow) cornerRadius else 0.dp,
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minRowHeight)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )

                trailing?.invoke()
            }

            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = dividerIndent),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

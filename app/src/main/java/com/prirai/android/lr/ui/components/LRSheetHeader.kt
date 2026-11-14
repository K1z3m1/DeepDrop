package com.prirai.android.lr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prirai.android.lr.R

object LRSheetHeaderDefaults {
    val ContentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp)
    val CloseButtonSize: Dp = 40.dp
    val DividerAlpha: Float = 0.2f

    val titleStyle: TextStyle
        @Composable get() = MaterialTheme.typography.titleLargeEmphasized

    val subtitleStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LRSheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    titleStyle: TextStyle = LRSheetHeaderDefaults.titleStyle,
    contentPadding: PaddingValues = LRSheetHeaderDefaults.ContentPadding,
    closeButtonSize: Dp? = LRSheetHeaderDefaults.CloseButtonSize,
    dividerAlpha: Float? = LRSheetHeaderDefaults.DividerAlpha,
    actions: @Composable RowScope.() -> Unit = {},
    showDragHandle: Boolean = true,
    titleMaxLines: Int = Int.MAX_VALUE,
) {
    Column(modifier = modifier) {
        if (showDragHandle) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BottomSheetDefaults.DragHandle()
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (leadingIcon != null) Arrangement.spacedBy(12.dp) else Arrangement.Start,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = LRSheetHeaderDefaults.subtitleStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            actions()
            IconButton(
                onClick = onClose,
                modifier = if (closeButtonSize != null) Modifier.size(closeButtonSize) else Modifier,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close),
                )
            }
        }

        if (dividerAlpha != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha))
        }
    }
}

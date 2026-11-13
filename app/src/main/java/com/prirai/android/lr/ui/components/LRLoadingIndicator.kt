package com.prirai.android.lr.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Full-surface loading state. Only ever composed while a surface has nothing else to show, so the
 * indicator morph runs while it is on screen and leaves the tree with it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LRLoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator()
    }
}

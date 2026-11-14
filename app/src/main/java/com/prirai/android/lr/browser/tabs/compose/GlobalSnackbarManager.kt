package com.prirai.android.lr.browser.tabs.compose

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.CancellationException

class GlobalSnackbarManager private constructor() {
    val snackbarHostState = SnackbarHostState()

    var coroutineScope: CoroutineScope? = null
        set(value) {
            field = value
            if (value == null) {
                currentSnackbarJob?.cancel()
                currentSnackbarJob = null
            }
        }

    private var currentSnackbarJob: Job? = null
    private var currentOnDismiss: (() -> Unit)? = null

    private fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Long,
        onAction: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ) {
        val scope = coroutineScope ?: run {
            Log.w(TAG, "Cannot show snackbar: coroutineScope not set")
            return
        }

        currentSnackbarJob?.cancel()
        currentOnDismiss = onDismiss

        currentSnackbarJob = scope.launch {
            try {
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = actionLabel,
                    duration = duration,
                    withDismissAction = true, // Show X button
                )

                when (result) {
                    SnackbarResult.ActionPerformed -> {
                        currentOnDismiss = null
                        onAction?.invoke()
                    }
                    SnackbarResult.Dismissed -> {
                        val callback = currentOnDismiss
                        currentOnDismiss = null
                        callback?.invoke()
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Snackbar cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error showing snackbar", e)
                val callback = currentOnDismiss
                currentOnDismiss = null
                callback?.invoke()
            } finally {
                currentSnackbarJob = null
            }
        }
    }

    fun showUndoSnackbar(
        message: String,
        onUndo: () -> Unit,
        onConfirm: () -> Unit,
        duration: SnackbarDuration = SnackbarDuration.Long,
    ) {
        showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = duration,
            onAction = onUndo,
            onDismiss = onConfirm,
        )
    }

    companion object {
        private const val TAG = "GlobalSnackbarManager"

        @Volatile
        private var instance: GlobalSnackbarManager? = null

        fun getInstance(): GlobalSnackbarManager {
            return instance ?: synchronized(this) {
                instance ?: GlobalSnackbarManager().also { instance = it }
            }
        }
    }
}

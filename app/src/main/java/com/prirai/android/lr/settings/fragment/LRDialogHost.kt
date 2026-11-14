package com.prirai.android.lr.settings.fragment

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.prirai.android.lr.R
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.theme.ThemeManager
import com.prirai.android.lr.ui.components.LRAlertDialog
import com.prirai.android.lr.ui.theme.LRTheme
import androidx.compose.material3.MaterialTheme

fun Fragment.showLRDialog(content: @Composable (onDismiss: () -> Unit) -> Unit) {
    val activity = requireActivity()

    val host = ComponentDialog(activity)
    host.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val composeView = ComposeView(activity)
    composeView.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
    )
    composeView.setContent {
        val prefs = UserPreferences(activity)
        LRTheme(
            darkTheme = ThemeManager.isDarkMode(activity),
            amoledMode = prefs.amoledMode,
            dynamicColor = prefs.dynamicColors,
        ) {
            content { host.dismiss() }
        }
    }

    host.setContentView(composeView)
    host.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
    host.show()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun showLRInfoDialog(context: Context, title: String, textRes: Int) {
    if (textRes == 0) return
    var current: Context? = context
    val activity = run {
        while (current is ContextWrapper) {
            if (current is FragmentActivity) return@run current
            current = current.baseContext
        }
        current as? FragmentActivity
    } ?: return

    val host = ComponentDialog(activity)
    host.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val composeView = ComposeView(activity)
    composeView.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
    )
    composeView.setContent {
        val prefs = UserPreferences(activity)
        LRTheme(
            darkTheme = ThemeManager.isDarkMode(activity),
            amoledMode = prefs.amoledMode,
            dynamicColor = prefs.dynamicColors,
        ) {
            LRAlertDialog(
                onDismissRequest = { host.dismiss() },
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                },
                text = {
                    Text(
                        text = stringResource(textRes),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { host.dismiss() },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.mozac_feature_prompts_ok))
                    }
                },
            )
        }
    }

    host.setContentView(composeView)
    host.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    host.show()
}

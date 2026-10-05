package com.firstt175.novaframe.ui

import com.firstt175.novaframe.ui.screens.GameLauncherScreen

import android.content.Context
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import com.firstt175.novaframe.prefs.AppThemeMode
import com.firstt175.novaframe.ui.theme.LiquidGlassBackdrop
import com.firstt175.novaframe.ui.theme.LocalAppAppearance
import com.firstt175.novaframe.ui.theme.isGlass
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.rememberNavController
import com.firstt175.novaframe.prefs.AppLanguagePrefs
import com.firstt175.novaframe.ui.theme.NovaTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguagePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // True immersive launcher: hide the Android status/navigation bars so
        // the launcher content can use the complete physical display.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContent {
            NovaTheme {
                val appearance = LocalAppAppearance.current
                Box(Modifier.fillMaxSize()) {
                    if (appearance.isGlass) {
                        LiquidGlassBackdrop(dark = appearance.theme == AppThemeMode.DARK)
                    }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (appearance.isGlass) Color.Transparent
                        else MaterialTheme.colorScheme.background,
                    ) {
                        val navController = rememberNavController()
                        NovaNavHost(navController)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Switches changed from the in-game drawer (HUD, frame graph, ...) write straight to
        // prefs; reload the shared config so every screen shows the real saved values.
        com.firstt175.novaframe.ui.refreshConfigState(
            com.firstt175.novaframe.prefs.NovaPreferences(this)
        )
    }

    /**
     * App-wide controller mapping so every screen gets Back/Select "for free"
     * without each Composable wiring its own key handler:
     *  - B (KEYCODE_BUTTON_B) → same as the system Back action.
     *  - A (KEYCODE_BUTTON_A) → forwarded as DPAD_CENTER, which is what
     *    Compose's focusable/clickable components already treat as "press",
     *    so a focused item can be activated with the controller.
     * Anything else (D-pad, L1/R1, ...) falls through untouched so screen-
     * level handlers (e.g. GameLauncherScreen's tab switch) still see it.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromGamepad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (fromGamepad) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        onBackPressedDispatcher.onBackPressed()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    return super.dispatchKeyEvent(
                        KeyEvent(event.action, KeyEvent.KEYCODE_DPAD_CENTER),
                    )
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

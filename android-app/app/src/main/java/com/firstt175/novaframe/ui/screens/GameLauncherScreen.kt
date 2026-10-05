@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.firstt175.novaframe.ui.screens

import com.firstt175.novaframe.ui.components.NovaFilterChip
import com.firstt175.novaframe.ui.Routes
import com.firstt175.novaframe.ui.produceConfigState

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Delete
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.novaframe.R
import com.firstt175.novaframe.prefs.NovaPreferences
import com.firstt175.novaframe.prefs.AppLanguage
import com.firstt175.novaframe.prefs.AppLanguagePrefs
import com.firstt175.novaframe.session.diagnostics.AdbDisplayController
import com.firstt175.novaframe.session.diagnostics.AppDisplayProfile
import com.firstt175.novaframe.session.diagnostics.AppDisplayProfileStore
import com.firstt175.novaframe.session.diagnostics.DisplayOverrideState
import com.firstt175.novaframe.session.service.NovaForegroundService
import com.firstt175.novaframe.session.NovaLog
import com.firstt175.novaframe.session.diagnostics.PhysicalDisplayInfo
import com.firstt175.novaframe.session.diagnostics.ShizukuDisplayPermission
import com.firstt175.novaframe.ui.components.NovaCard
import com.firstt175.novaframe.ui.components.NovaLogoMark
import com.firstt175.novaframe.ui.components.NovaSwitch
import com.firstt175.novaframe.ui.theme.LocalAppAppearance
import com.firstt175.novaframe.ui.theme.LocalGlassDark
import com.firstt175.novaframe.ui.theme.LocalGlassIntensity
import com.firstt175.novaframe.ui.theme.glassEdgeScrim
import com.firstt175.novaframe.ui.theme.glassSurface
import com.firstt175.novaframe.ui.theme.isGlass
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.firstt175.novaframe.ui.components.SectionHeader
import com.firstt175.novaframe.ui.components.StatusPill
import com.firstt175.novaframe.ui.components.StatusTone
import com.firstt175.novaframe.ui.components.ToggleRow
import com.firstt175.novaframe.ui.components.waterClickable
import com.firstt175.novaframe.ui.components.ValueSlider
import com.firstt175.novaframe.ui.rememberAppIconPainter
import com.firstt175.novaframe.ui.theme.NovaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LaunchableApp(
    val label: String,
    val packageName: String,
    val isGame: Boolean,
)

// android:appCategory="game" in the manifest is what ApplicationInfo.category
// reports, but most sideloaded/indie game APKs (like the one visible in the
// screenshot) never set it — that's why "Games" showed 0 even with an
// obvious game installed. FLAG_IS_GAME is the older pre-category signal some
// devices/APKs still carry, and a ".game." segment in the package name is a
// solid fallback for the many APKs that set neither.
@Suppress("DEPRECATION")
private fun looksLikeGame(ai: ApplicationInfo): Boolean {
    if (ai.category == ApplicationInfo.CATEGORY_GAME) return true
    if ((ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
    val segments = ai.packageName.lowercase().split(".")
    return segments.contains("game") || segments.contains("games")
}

private const val PREFS_LAUNCHER = "game_launcher"
private const val KEY_MANUAL_GAMES = "manual_games"
private const val KEY_HIDDEN_GAMES = "hidden_games"
private const val KEY_OVERLAY_ON_LAUNCH = "overlay_on_launch"

/**
 * Whether launching a game from [GameLauncherScreen] should also arm the
 * lightweight overlay host (the settings-drawer icon). Defaults to on to
 * preserve existing behavior. When off, [GameLauncherScreen.launchApp]'s
 * caller skips [NovaForegroundService.buildShowDrawerIntent] entirely, so the
 * target app runs with no overlay/drawer at all until the user flips this
 * back on and relaunches.
 */
private fun isOverlayEnabledOnLaunch(context: Context): Boolean =
    context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
        .getBoolean(KEY_OVERLAY_ON_LAUNCH, true)

private fun setOverlayEnabledOnLaunch(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_OVERLAY_ON_LAUNCH, enabled)
        .apply()
}

private const val KEY_LAST_PLAYED = "last_played_package"

private fun getLastPlayedPackage(context: Context): String? =
    context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
        .getString(KEY_LAST_PLAYED, null)

private fun setLastPlayedPackage(context: Context, packageName: String) {
    context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_LAST_PLAYED, packageName)
        .apply()
}

private fun getManualGamePackages(context: Context): Set<String> =
    context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
        .getStringSet(KEY_MANUAL_GAMES, emptySet())
        ?.toSet()
        .orEmpty()

private fun addManualGame(context: Context, packageName: String) {
    val prefs = context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
    val games = prefs.getStringSet(KEY_MANUAL_GAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
    games += packageName
    prefs.edit().putStringSet(KEY_MANUAL_GAMES, games).apply()
}

private fun removeGameFromLauncher(context: Context, packageName: String) {
    val prefs = context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
    val manual = prefs.getStringSet(KEY_MANUAL_GAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
    val hidden = prefs.getStringSet(KEY_HIDDEN_GAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
    manual.remove(packageName)
    hidden += packageName
    prefs.edit()
        .putStringSet(KEY_MANUAL_GAMES, manual)
        .putStringSet(KEY_HIDDEN_GAMES, hidden)
        .apply()
}

private fun unhideGame(context: Context, packageName: String) {
    val prefs = context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
    val hidden = prefs.getStringSet(KEY_HIDDEN_GAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
    hidden.remove(packageName)
    prefs.edit().putStringSet(KEY_HIDDEN_GAMES, hidden).apply()
}

private fun getHiddenGamePackages(context: Context): Set<String> =
    context.getSharedPreferences(PREFS_LAUNCHER, Context.MODE_PRIVATE)
        .getStringSet(KEY_HIDDEN_GAMES, emptySet())
        ?.toSet()
        .orEmpty()

private fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val manualGames = getManualGamePackages(context)
    val hiddenGames = getHiddenGamePackages(context)
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .asSequence()
        .mapNotNull { info ->
            val ai = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            if (ai.packageName == context.packageName || ai.packageName in hiddenGames) return@mapNotNull null
            LaunchableApp(
                label = ai.loadLabel(pm).toString().ifBlank { ai.packageName },
                packageName = ai.packageName,
                isGame = looksLikeGame(ai) || ai.packageName in manualGames,
            )
        }
        .distinctBy { it.packageName }
        .sortedWith(compareByDescending<LaunchableApp> { it.isGame }.thenBy { it.label.lowercase() })
        .toList()
}

private fun openAppInfo(context: Context, packageName: String) {
    val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    context.startActivity(intent)
}

/**
 * Refresh rates the device's default display can actually run at, read from
 * its [Display.Mode] list (all modes share resolution class but differ in
 * Hz on most phones). Falls back to just the display's current refresh
 * rate if the mode list can't be read for some reason.
 */
private fun getSupportedRefreshRates(context: Context): List<Int> {
    val display = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay
    }.getOrNull() ?: return emptyList()

    val fromModes = runCatching {
        display.supportedModes
            ?.map { it.refreshRate }
            .orEmpty()
    }.getOrElse { emptyList() }

    val rates = fromModes.ifEmpty {
        runCatching { listOf(display.refreshRate) }.getOrElse { emptyList() }
    }

    return rates
        .map { Math.round(it) }
        .filter { it > 0 }
        .distinct()
        .sorted()
}

private const val TAG_PRE_LAUNCH_DISPLAY = "NovaPreLaunchDisplay"

// How long to wait after starting the target app's Activity before forcing
// the resolution/DPI override. This delay is required, not just cosmetic:
// applying the resize while OUR OWN activity is still in the foreground
// triggers a configuration change that recreates it (see the big comment
// on the launchingApp LaunchedEffect below for the full story of the bug
// this caused). Firing the launch intent first hands the foreground to the
// target app, and this delay gives it a moment to actually get there before
// the resize command runs — there's no cross-process "target process is
// now up" hook available here without Shizuku/root, so it's a short fixed
// delay rather than an exact signal.
private const val POST_LAUNCH_DISPLAY_DELAY_MS = 600L

/**
 * Applies (or clears) the per-app forced size/density override AFTER the
 * target app's launch intent has fired and it's had a moment to reach the
 * foreground. This ordering is required — see [POST_LAUNCH_DISPLAY_DELAY_MS]
 * and the comment on the launchingApp LaunchedEffect in [GameLauncherScreen]
 * for why applying it any earlier breaks navigation.
 *
 * Returns the resolved profile so the caller can log/display it.
 */
private suspend fun applyDisplayProfileAfterLaunch(
    context: Context,
    packageName: String,
): AppDisplayProfile? {
    if (!AdbDisplayController.isReady(context)) return null
    return runCatching {
        val current = AdbDisplayController.readDisplay(context) ?: return@runCatching null
        // Always (re)derive from the true physical panel size/stable density,
        // never from whatever size/density might currently be force-applied
        // from a previous session, so the percent-based calculation can't
        // compound across launches.
        val stored = AppDisplayProfileStore.captureOriginalIfMissing(context, packageName, current)
        if (stored.originalWidth <= 0 || stored.originalHeight <= 0) return@runCatching stored

        if (stored.enabled && stored.percent < 100) {
            val applied = AdbDisplayController.apply(context, stored)
            if (applied) {
                DisplayOverrideState.markApplied(context, packageName)
            }
            NovaLog.i(
                TAG_PRE_LAUNCH_DISPLAY,
                "Post-launch display for $packageName: ${stored.percent}% -> " +
                    "${stored.calculatedWidth}x${stored.calculatedHeight} @ ${stored.calculatedDpi}dpi " +
                    "applied=$applied",
            )
        } else {
            // No per-app override for this app (or 100%): clear any stale
            // forced size/density left over from a previous app's session.
            AdbDisplayController.reset(context)
            DisplayOverrideState.clear(context)
        }
        stored
    }.onFailure {
        NovaLog.e(TAG_PRE_LAUNCH_DISPLAY, "applyDisplayProfileAfterLaunch failed", it)
    }.getOrNull()
}

@Composable
fun GameLauncherScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var filter by remember { mutableStateOf(1) } // 0 = all, 1 = games, 2 = apps
    var query by remember { mutableStateOf("") }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAddGameDialog by remember { mutableStateOf(false) }
    var launchingApp by remember { mutableStateOf<LaunchableApp?>(null) }
    var overlayEnabled by remember { mutableStateOf(isOverlayEnabledOnLaunch(context)) }
    var lastPlayedPackage by remember { mutableStateOf(getLastPlayedPackage(context)) }

    val prefs = remember { NovaPreferences(context) }
    val configState by produceConfigState(prefs).collectAsState()
    val glassUi = LocalAppAppearance.current.isGlass
    val glassDark = LocalGlassDark.current
    val glassIntensity = LocalGlassIntensity.current

    suspend fun launchApp(app: LaunchableApp) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.toast_no_launch_button, app.label),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    suspend fun refresh() {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    fun restoreOriginalDisplay() {
        scope.launch(Dispatchers.IO) {
            val ok = AdbDisplayController.reset(context)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (ok) "คืนค่าหน้าจอเดิมแล้ว" else "ไม่สามารถคืนค่าหน้าจอได้ หรือไม่มีสิทธิ์",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        // Do not auto-restore display size/DPI here. A crash or app restart must
        // never decide to change the user's screen. Restoration is explicit.
        refresh()
    }

    // IMPORTANT: the resize (AdbDisplayController.apply) must never run while
    // THIS screen is still the foreground activity — a display size/density
    // change is a configuration change, and since GameLauncherScreen's host
    // Activity doesn't declare configChanges for that, Android recreates it,
    // which resets Compose Navigation back to its start destination. That's
    // what caused the "bounces back to GameLauncher, never enters the game"
    // bug: applying the resize before firing the launch intent hit our own
    // activity instead of the target's. So: fire the launch intent first —
    // handing the foreground to the target app — THEN apply the resize
    // (once the target has had a brief moment to come up), so the
    // configuration change lands on the target app, not on us.
    LaunchedEffect(launchingApp) {
        val target = launchingApp ?: return@LaunchedEffect

        launchApp(target)
        setLastPlayedPackage(context, target.packageName)
        lastPlayedPackage = target.packageName

        val profile = withContext(Dispatchers.IO) {
            AppDisplayProfileStore.load(context, target.packageName)
        }
        if (profile.enabled) {
            delay(POST_LAUNCH_DISPLAY_DELAY_MS)
            withContext(Dispatchers.IO) {
                applyDisplayProfileAfterLaunch(context, target.packageName)
            }
        }

        // Launching from Game Launcher must NOT start capture or frame generation.
        // Start only the lightweight overlay host so the SettingsDrawer icon is
        // immediately available. The real session begins only after the user
        // presses START SESSION in the drawer. Skipped entirely when the user
        // has switched the overlay off from the header toggle — the target
        // app then runs with no overlay/drawer at all.
        if (overlayEnabled && !NovaForegroundService.isRunning.value) {
            delay(220L)
            ContextCompat.startForegroundService(
                context,
                NovaForegroundService.buildShowDrawerIntent(context, target.packageName)
            )
        }

        launchingApp = null
    }

    val games = remember(apps) { apps.filter { it.isGame } }
    val normalApps = remember(apps) { apps.filter { !it.isGame } }
    val visibleApps = remember(apps, filter, query) {
        val source = when (filter) {
            1 -> games
            2 -> normalApps
            else -> apps
        }
        if (query.isBlank()) source
        else source.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    // The "Add game" tile must ALWAYS be reachable on the Games tab — even when no
    // installed app is classified as a game (fresh phone) — otherwise there is no
    // way to add one manually. Only hidden while the user is actively searching.
    val showAddTile = filter == 1 && query.isBlank()

    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed
    )

    Box(modifier = Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            val drawerShape = if (glassUi) RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)
            else androidx.compose.material3.DrawerDefaults.shape
            androidx.compose.material3.ModalDrawerSheet(
                modifier = if (glassUi) Modifier.glassSurface(
                    shape = drawerShape,
                    dark = glassDark,
                    strong = true,
                    intensity = glassIntensity,
                ) else Modifier,
                drawerShape = drawerShape,
                drawerContainerColor = if (glassUi) Color.Transparent else MaterialTheme.colorScheme.background,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                drawerTonalElevation = if (glassUi) 0.dp else androidx.compose.material3.DrawerDefaults.ModalDrawerElevation,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NovaLogoMark(size = 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "NovaFrame",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                            )
                            Text(
                                stringResource(R.string.home_launcher),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(stringResource(R.string.home_my_games)) },
                        selected = filter == 1,
                        onClick = {
                            scope.launch { drawerState.close() }
                            filter = 1
                        },
                        icon = { Icon(Icons.Filled.Gamepad, null) },
                    )
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(stringResource(R.string.profile_button)) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate(Routes.PROFILE)
                        },
                        icon = { Icon(Icons.Filled.AccountCircle, null) },
                    )
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(stringResource(R.string.home_settings)) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate(Routes.SETTINGS)
                        },
                        icon = { Icon(Icons.Filled.DisplaySettings, null) },
                    )
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(stringResource(R.string.home_language)) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showLanguageDialog = true
                        },
                        icon = { Icon(Icons.Filled.Language, null) },
                    )
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(stringResource(R.string.credits_title)) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate(Routes.CREDITS)
                        },
                        icon = { Icon(Icons.Filled.Info, null) },
                    )

                    Spacer(Modifier.weight(1f))

                    NovaCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.home_device),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            android.os.Build.MODEL,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                        val mem = android.app.ActivityManager.MemoryInfo()
                        am?.getMemoryInfo(mem)
                        val totalGb = mem.totalMem / (1024.0 * 1024.0 * 1024.0)
                        Text(
                            "RAM %.1f GB".format(totalGb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "NovaFrame v${com.firstt175.novaframe.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            // Header: logo + title.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovaLogoMark(size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_launcher),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                    )
                    Text(
                        stringResource(R.string.home_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.IconButton(
                    onClick = { restoreOriginalDisplay() },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "คืนค่าหน้าจอเดิม",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                // Header overlay toggle: controls whether launching a game from
                // this screen also arms the overlay/settings-drawer. Purely a
                // pre-launch preference — it has no effect on a session that's
                // already running.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NovaSwitch(
                        checked = overlayEnabled,
                        onCheckedChange = {
                            overlayEnabled = it
                            setOverlayEnabledOnLaunch(context, it)
                        },
                    )
                    Text(
                        stringResource(
                            if (overlayEnabled) R.string.home_overlay_on else R.string.home_overlay_off
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Search bar, deliberately full width like the reference.
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .then(
                        if (glassUi) Modifier.glassSurface(
                            shape = RoundedCornerShape(50),
                            dark = glassDark,
                            intensity = glassIntensity,
                            lift = false,
                        ) else Modifier,
                    ),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                placeholder = { Text(stringResource(R.string.home_search)) },
                shape = if (glassUi) RoundedCornerShape(50) else RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = if (glassUi) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = if (glassUi) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = if (glassUi) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                    unfocusedBorderColor = if (glassUi) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                ),
            )

            Spacer(Modifier.height(18.dp))

            val lastApp = if (glassUi || query.isNotBlank()) null
            else apps.firstOrNull { it.packageName == lastPlayedPackage }
            if (lastApp != null) {
                val lastIcon = rememberAppIconPainter(lastApp.packageName, 72)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(24.dp),
                        )
                        .padding(16.dp),
                ) {
                    Text(
                        stringResource(R.string.home_last_played),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (lastIcon != null) {
                            Image(
                                painter = lastIcon,
                                contentDescription = lastApp.label,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Apps,
                                    null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            lastApp.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { launchingApp = lastApp },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            stringResource(
                                if (overlayEnabled) R.string.home_launch_overlay else R.string.home_launch
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_my_games),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
            }

            // Game grid is the main visual area; it remains the only scrolling
            // region so the header and bottom navigation stay fixed.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (visibleApps.isEmpty() && !showAddTile) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Apps, null, Modifier.size(52.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.empty_no_apps_title),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        // Adaptive instead of a hardcoded column count: in
                        // portrait this settles at ~3 columns (same as
                        // before), but in landscape — or on a tablet — the
                        // extra width now fills in with more columns instead
                        // of stretching each tile into an oversized card.
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 2.dp,
                            bottom = 18.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        gridItems(visibleApps, key = { it.packageName }) { app ->
                            LauncherGameTile(
                                nav = nav,
                                app = app,
                                onLaunch = { launchingApp = app },
                                onRemove = {
                                    removeGameFromLauncher(context, app.packageName)
                                    scope.launch { refresh() }
                                },
                            )
                        }

                        if (showAddTile) {
                            item(key = "add-game") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.82f)
                                        .then(
                                            if (glassUi) Modifier.glassSurface(
                                                shape = RoundedCornerShape(22.dp),
                                                dark = glassDark,
                                                intensity = glassIntensity,
                                                lift = false,
                                            ) else Modifier
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                        )
                                        .clickable { showAddGameDialog = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Filled.Add, null, Modifier.size(38.dp))
                                        Spacer(Modifier.height(6.dp))
                                        Text(stringResource(R.string.home_add_game))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom navigation: Settings / My Games / Gallery / Profile.
            // iOS 27: a floating glass capsule over a uniform edge scrim; Normal: solid bar.
            // The highlight is a single liquid pill that slides to whichever tab is tapped.
            var navIndex by remember { mutableIntStateOf(1) }
            var navLocked by remember { mutableStateOf(false) }
            val navBackEntry by nav.currentBackStackEntryAsState()
            LaunchedEffect(navBackEntry?.destination?.route) {
                // Back on the launcher: pill returns to "My Games" and taps work again.
                if (navBackEntry?.destination?.route == Routes.HOME) {
                    navIndex = 1
                    navLocked = false
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (glassUi) Modifier
                            .glassEdgeScrim(glassDark, top = false, intensity = glassIntensity)
                            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp)
                        else Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
                    ),
            ) {
                LiquidBottomBar(
                    selectedIndex = navIndex,
                    tabs = listOf(
                        BottomTabSpec(Icons.Filled.DisplaySettings, stringResource(R.string.home_settings)),
                        BottomTabSpec(Icons.Filled.Gamepad, stringResource(R.string.home_my_games)),
                        BottomTabSpec(Icons.Filled.PhotoLibrary, stringResource(R.string.home_gallery)),
                        BottomTabSpec(Icons.Filled.AccountCircle, stringResource(R.string.home_profile)),
                    ),
                    onTabClick = { index ->
                        if (navLocked || index == navIndex) return@LiquidBottomBar
                        navIndex = index // the pill starts flowing to the tapped tab right now
                        if (index == 1) {
                            filter = 1
                        } else {
                            navLocked = true
                            scope.launch {
                                delay(300) // let the drop land before the screen changes
                                nav.navigate(
                                    when (index) {
                                        0 -> Routes.SETTINGS
                                        2 -> Routes.RECORDINGS
                                        else -> Routes.PROFILE
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }

        if (showAddGameDialog) {
            AddGameDialog(
                installedApps = apps,
                onAdd = { packageName ->
                    unhideGame(context, packageName)
                    addManualGame(context, packageName)
                    showAddGameDialog = false
                    scope.launch { refresh() }
                },
                onDismiss = { showAddGameDialog = false },
            )
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(onDismiss = { showLanguageDialog = false })
        }

        // Retain the existing overflow actions without changing their behavior.
        Box {
            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.language_menu_item)) },
                    leadingIcon = { Icon(Icons.Filled.Language, null) },
                    onClick = {
                        showMoreMenu = false
                        showLanguageDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.credits_title)) },
                    leadingIcon = { Icon(Icons.Filled.Info, null) },
                    onClick = {
                        showMoreMenu = false
                        nav.navigate(Routes.CREDITS)
                    },
                )
            }
        }
    }
    }
}

private data class BottomTabSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
)

private val BottomTabHeight = 58.dp

/**
 * Bottom bar with ONE highlight pill that physically travels to the tapped tab.
 * Liquid feel: the leading edge of the pill springs ahead fast while the trailing edge
 * lags behind, so the pill stretches like a water drop mid-flight, then snaps back with a
 * small wobble when it lands.
 */
@Composable
private fun LiquidBottomBar(
    selectedIndex: Int,
    tabs: List<BottomTabSpec>,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glassUi = LocalAppAppearance.current.isGlass
    val glassDark = LocalGlassDark.current
    val glassIntensity = LocalGlassIntensity.current
    val primary = MaterialTheme.colorScheme.primary
    val trackShape = RoundedCornerShape(50)
    val pillShape = RoundedCornerShape(50)
    val n = tabs.size

    // Remember which way the pill is travelling so the correct edge leads.
    val lastIndex = remember { IntArray(1) { selectedIndex } }
    val goingRight = remember { BooleanArray(1) { true } }
    if (selectedIndex != lastIndex[0]) {
        goingRight[0] = selectedIndex > lastIndex[0]
        lastIndex[0] = selectedIndex
    }
    val leadSpec = spring<Float>(dampingRatio = 0.62f, stiffness = 640f)
    val trailSpec = spring<Float>(dampingRatio = 0.80f, stiffness = 200f)
    val leftEdge by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (goingRight[0]) trailSpec else leadSpec,
        label = "navPillLeft",
    )
    val rightEdge by animateFloatAsState(
        targetValue = selectedIndex + 1f,
        animationSpec = if (goingRight[0]) leadSpec else trailSpec,
        label = "navPillRight",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (glassUi) Modifier
                    .glassSurface(
                        shape = trackShape,
                        dark = glassDark,
                        strong = true,
                        intensity = glassIntensity,
                    )
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                else Modifier
                    .clip(trackShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, trackShape)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ),
    ) {
        val itemWidth = maxWidth / n
        // Keep the spring overshoot inside the capsule so the pill never pokes out of the track.
        val left = leftEdge.coerceIn(0f, n - 0.85f)
        val right = rightEdge.coerceIn(left + 0.85f, n.toFloat())
        val pillSpan = right - left
        val stretch = (pillSpan - 1f).coerceIn(0f, 1.2f)

        // The moving pill (drawn behind the icons).
        Box(
            modifier = Modifier
                .offset(x = itemWidth * left)
                .width(itemWidth * pillSpan)
                .height(BottomTabHeight)
                .graphicsLayer {
                    // Squash a little while stretched, like a drop of water in motion.
                    scaleY = 1f - 0.07f * stretch
                }
                .then(
                    if (glassUi) Modifier.glassSurface(
                        shape = pillShape,
                        dark = glassDark,
                        strong = true,
                        intensity = glassIntensity,
                        tint = primary,
                        lift = false,
                    ) else Modifier.background(MaterialTheme.colorScheme.primaryContainer, pillShape),
                ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                LiquidBottomItem(
                    selected = index == selectedIndex,
                    icon = tab.icon,
                    label = tab.label,
                    shape = pillShape,
                    onClick = { onTabClick(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LiquidBottomItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 220),
        label = "navItemColor",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 420f),
        label = "navItemIconScale",
    )
    Column(
        modifier = modifier
            .height(BottomTabHeight)
            .clip(shape)
            .waterClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier
                .size(25.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            tint = contentColor,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun AddGameDialog(
    installedApps: List<LaunchableApp>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The Add Game picker is an app picker, not a "non-game" picker.
    // Show every launchable installed app so the user can manually add any
    // application/game, including APKs that Android does not classify as a game.
    val candidates = installedApps
        .sortedBy { it.label.lowercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_add_game)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.home_no_games))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(candidates, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onAdd(app.packageName) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Gamepad, null, Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Filled.Add, stringResource(R.string.home_add))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        },
    )
}

@Composable
private fun LauncherGameTile(
    nav: NavHostController,
    app: LaunchableApp,
    onLaunch: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(AppDisplayProfileStore.load(context, app.packageName)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (LocalAppAppearance.current.isGlass) Modifier else Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(vertical = 10.dp),
            )
            .combinedClickable(
                onClick = onLaunch,
                onLongClick = { showMenu = true },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            val iconPainter = rememberAppIconPainter(app.packageName, 72)
            if (iconPainter != null) {
                val tileGlass = LocalAppAppearance.current.isGlass
                val tileShape = RoundedCornerShape(18.dp)
                Image(
                    painter = iconPainter,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(76.dp)
                        .then(
                            if (tileGlass) Modifier.shadow(
                                elevation = 10.dp,
                                shape = tileShape,
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.35f),
                                spotColor = Color.Black.copy(alpha = 0.45f),
                            ) else Modifier,
                        )
                        .clip(tileShape)
                        .then(
                            if (tileGlass) Modifier.border(
                                0.75.dp,
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f)),
                                ),
                                tileShape,
                            ) else Modifier,
                        ),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Apps, null, Modifier.size(32.dp))
                }
            }

            if (profile.enabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        null,
                        Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.height(7.dp))
        Text(
            app.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        val removedMessage = stringResource(R.string.home_removed, app.label)
        AppQuickActionsMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onSettings = { showMenu = false; showSettings = true },
            onAppInfo = { showMenu = false; openAppInfo(context, app.packageName) },
            onRemove = onRemove?.let { remove ->
                {
                    showMenu = false
                    remove()
                    Toast.makeText(context, removedMessage, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (showSettings) {
        AppCardSettingsDialog(
            nav = nav,
            packageName = app.packageName,
            label = app.label,
            initial = profile,
            onDismiss = { showSettings = false },
            onSaved = {
                profile = it
                showSettings = false
            },
        )
    }
}

@Composable
private fun LanguagePickerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    var selected by remember { mutableStateOf(AppLanguagePrefs.get(ctx)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_menu_item)) },
        text = {
            Column {
                val options = listOf(
                    AppLanguage.SYSTEM to stringResource(R.string.language_system),
                    AppLanguage.ENGLISH to stringResource(R.string.language_english),
                    AppLanguage.THAI to stringResource(R.string.language_thai),
                )
                options.forEach { (lang, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = lang },
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == lang,
                            onClick = { selected = lang },
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                AppLanguagePrefs.set(ctx, selected)
                onDismiss()
                activity?.recreate()
            }) { Text(stringResource(R.string.language_apply)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_dialog_dismiss))
            }
        },
    )
}

/**
 * Long-press context menu shared by every layout (list row, grid tile,
 * Switch-style tile): settings, app info — the same pair a regular
 * Android home screen shows on long-press, minus uninstall.
 */
@Composable
private fun AppQuickActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onAppInfo: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_settings)) },
            leadingIcon = { Icon(Icons.Filled.DisplaySettings, contentDescription = null) },
            onClick = onSettings,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_app_info)) },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = onAppInfo,
        )
        if (onRemove != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_remove)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = onRemove,
            )
        }
    }
}

private const val TAG_APP_CARD_SETTINGS = "NovaAppCardSettings"

@Composable
private fun AppCardSettingsDialog(
    nav: NavHostController,
    packageName: String,
    label: String,
    initial: AppDisplayProfile,
    onDismiss: () -> Unit,
    onSaved: (AppDisplayProfile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(initial) }
    var percent by remember { mutableStateOf(initial.percent.toFloat()) }
    var clean by remember { mutableStateOf(initial.dynamicClean) }
    var noAnimations by remember { mutableStateOf(initial.disableAnimations) }
    var keepAwake by remember { mutableStateOf(initial.keepAwake) }
    var fixedPerfMode by remember { mutableStateOf(initial.fixedPerformanceMode) }
    var dozeWhitelist by remember { mutableStateOf(initial.dozeWhitelist) }
    var forceStopBg by remember { mutableStateOf(initial.forceStopBackground) }
    var refreshRateHz by remember { mutableStateOf(initial.lockRefreshRateHz) }
    var wifiLock by remember { mutableStateOf(initial.wifiHighPerfLock) }
    var stallTimeoutSec by remember { mutableStateOf(initial.sessionStallTimeoutSec) }
    var info by remember { mutableStateOf<PhysicalDisplayInfo?>(null) }
    val supportedHz = remember { getSupportedRefreshRates(context) }
    val refreshRateOptions = remember(supportedHz) { listOf(0) + supportedHz }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_app_settings_title, label)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NovaSpacing.md),
            ) {
                val secureGranted = ShizukuDisplayPermission.hasWriteSecureSettings(context)
                // Shizuku/WRITE_SECURE_SETTINGS granting itself now lives only
                // on the single Setup screen — this dialog just flags it here
                // when it's still missing, instead of duplicating the flow.
                if (!secureGranted) {
                    NovaCard(
                        onClick = { nav.navigate(Routes.SETUP) },
                        accent = true,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.status_no_permission), fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.home_setup_permissions),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusPill(label = stringResource(R.string.home_not_granted), tone = StatusTone.Warn)
                        }
                    }
                }

                NovaCard {
                    SectionHeader(eyebrow = stringResource(R.string.home_display))
                    Spacer(Modifier.height(NovaSpacing.sm))
                    if (profile.originalWidth > 0) {
                        Text(
                            stringResource(R.string.display_original_format, profile.originalWidth, profile.originalHeight, profile.originalDpi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.display_calculated_format, profile.calculatedWidth, profile.calculatedHeight, profile.calculatedDpi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(R.string.display_original_not_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(NovaSpacing.sm))
                    ValueSlider(
                        title = stringResource(R.string.home_resolution),
                        valueDisplay = "${percent.toInt()}%",
                        description = null,
                        value = percent,
                        onValueChange = { percent = ((it / 5f).toInt() * 5).coerceIn(25, 100).toFloat() },
                        range = 25f..100f,
                        steps = 14,
                    )
                }

                NovaCard {
                    SectionHeader(eyebrow = stringResource(R.string.home_session_behavior))
                    Spacer(Modifier.height(NovaSpacing.sm))
                    ToggleRow(
                        icon = Icons.Filled.Refresh,
                        title = stringResource(R.string.home_dynamic_background),
                        checked = clean,
                        onCheckedChange = { clean = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.Speed,
                        title = stringResource(R.string.disable_animations_title),
                        checked = noAnimations,
                        onCheckedChange = { noAnimations = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.BatteryFull,
                        title = stringResource(R.string.keep_awake_title),
                        checked = keepAwake,
                        onCheckedChange = { keepAwake = it },
                    )
                    Spacer(Modifier.height(NovaSpacing.sm))
                    ValueSlider(
                        title = "Session Stall Timeout",
                        valueDisplay = if (stallTimeoutSec == 0) "Off" else "${stallTimeoutSec}s",
                        description = "ถ้าเกมนี้ค้างที่ fps 0 (ออกจากแอปแล้ว) นานเกินเวลานี้ จะปิด overlay ให้อัตโนมัติ",
                        value = stallTimeoutSec.toFloat(),
                        onValueChange = { stallTimeoutSec = it.toInt().coerceIn(0, 120) },
                        range = 0f..120f,
                        steps = 119,
                    )
                }

                val shizukuReady = ShizukuDisplayPermission.isShizukuAvailable()
                NovaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(
                            eyebrow = stringResource(R.string.home_performance),
                            title = null,
                            modifier = Modifier.weight(1f),
                        )
                        if (!shizukuReady) {
                            StatusPill(label = stringResource(R.string.home_requires_shizuku), tone = StatusTone.Warn)
                        }
                    }
                    Spacer(Modifier.height(NovaSpacing.sm))
                    ToggleRow(
                        icon = Icons.Filled.Speed,
                        title = stringResource(R.string.home_fixed_performance),
                        description = stringResource(R.string.home_fixed_performance_desc),
                        checked = fixedPerfMode,
                        onCheckedChange = { fixedPerfMode = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.BatteryFull,
                        title = stringResource(R.string.home_doze),
                        description = stringResource(R.string.home_doze_desc),
                        checked = dozeWhitelist,
                        onCheckedChange = { dozeWhitelist = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.Apps,
                        title = stringResource(R.string.home_force_stop),
                        description = stringResource(R.string.home_force_stop_desc),
                        checked = forceStopBg,
                        onCheckedChange = { forceStopBg = it },
                    )
                    ToggleRow(
                        icon = Icons.Filled.DisplaySettings,
                        title = stringResource(R.string.home_wifi),
                        description = stringResource(R.string.home_wifi_desc),
                        checked = wifiLock,
                        onCheckedChange = { wifiLock = it },
                    )
                }

                NovaCard {
                    SectionHeader(eyebrow = stringResource(R.string.home_refresh))
                    Spacer(Modifier.height(NovaSpacing.sm))
                    if (supportedHz.isEmpty()) {
                        Text(
                            stringResource(R.string.home_refresh_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        refreshRateOptions.forEach { hz ->
                            NovaFilterChip(
                                selected = refreshRateHz == hz,
                                onClick = { refreshRateHz = hz },
                                label = { Text(if (hz == 0) stringResource(R.string.home_automatic) else "${hz}Hz") },
                            )
                        }
                    }
                    Spacer(Modifier.height(NovaSpacing.sm))
                    Text(
                        stringResource(R.string.limit_background_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (!AdbDisplayController.isReady(context)) {
                    if (ShizukuDisplayPermission.isShizukuAvailable()) {
                        scope.launch {
                            val granted = ShizukuDisplayPermission.grantWriteSecureSettings(context)
                            if (!granted) Toast.makeText(context, context.getString(R.string.toast_shizuku_grant_failed), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        AdbDisplayController.requestPermission()
                        val command = AdbDisplayController.grantCommand()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ADB grant", command))
                        Toast.makeText(context, context.getString(R.string.toast_no_permission_copied_adb), Toast.LENGTH_LONG).show()
                    }
                    return@Button
                }
                val current = AdbDisplayController.readDisplay(context)
                if (current == null) {
                    NovaLog.w(TAG_APP_CARD_SETTINGS, "Save[$packageName]: readDisplay() returned null, see NovaAdbDisplay log above")
                    Toast.makeText(context, context.getString(R.string.toast_read_display_failed), Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val captured = AppDisplayProfileStore.captureOriginalIfMissing(context, packageName, current)
                val saved = AppDisplayProfileStore.withPercent(context, packageName, percent.toInt()).copy(
                    dynamicClean = clean,
                    maxBackgroundApps = 1,
                    disableAnimations = noAnimations,
                    keepAwake = keepAwake,
                    fixedPerformanceMode = fixedPerfMode,
                    dozeWhitelist = dozeWhitelist,
                    forceStopBackground = forceStopBg,
                    lockRefreshRateHz = refreshRateHz,
                    wifiHighPerfLock = wifiLock,
                    sessionStallTimeoutSec = stallTimeoutSec,
                ).also { AppDisplayProfileStore.save(context, packageName, it) }
                onSaved(saved)
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Button(onClick = {
                if (!AdbDisplayController.isReady(context)) {
                    NovaLog.w(TAG_APP_CARD_SETTINGS, "ReadReal[$packageName]: not ready, prompting for permission")
                    AdbDisplayController.requestPermission()
                    val command = AdbDisplayController.grantCommand()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ADB grant", command))
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_adb_copied_with_command, command),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    val current = AdbDisplayController.readDisplay(context)
                    if (current == null) {
                        NovaLog.w(TAG_APP_CARD_SETTINGS, "ReadReal[$packageName]: readDisplay() returned null, see NovaAdbDisplay log above")
                    }
                    if (current != null) info = current
                    val captured = current?.let { AppDisplayProfileStore.captureOriginalIfMissing(context, packageName, it) }
                    if (captured != null) {
                        profile = captured
                        percent = captured.percent.toFloat()
                    }
                }
            }) { Text(stringResource(R.string.action_read_real_display)) }
        },
    )
}

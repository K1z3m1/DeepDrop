@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.browser.tabs

import androidx.compose.runtime.getValue
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prirai.android.lr.BrowserActivity
import com.prirai.android.lr.R
import com.prirai.android.lr.browser.BrowsingMode
import com.prirai.android.lr.browser.BrowsingModeManager
import com.prirai.android.lr.browser.tabgroups.UnifiedTabGroupManager
import com.prirai.android.lr.browser.tabs.compose.ProfileCardsPage
import com.prirai.android.lr.browser.tabs.compose.TabViewModel
import com.prirai.android.lr.browser.tabs.compose.TabSheetListView
import com.prirai.android.lr.browser.tabs.compose.TabSheetGridView
import com.prirai.android.lr.databinding.FragmentTabsBottomSheetBinding
import com.prirai.android.lr.ext.components
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.flowScoped
import androidx.core.content.edit
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.browser.profile.ProfileCreateDialog
import com.prirai.android.lr.browser.profile.ProfileEditDialog
import com.prirai.android.lr.browser.profile.ProfileExportManager
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.browser.tabs.compose.GlobalSnackbarManager
import com.prirai.android.lr.browser.tabs.compose.TabOrderManager
import com.prirai.android.lr.browser.tabs.compose.TabSheetStateManager
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.settings.ThemeChoice
import com.prirai.android.lr.theme.ThemeManager
import com.prirai.android.lr.ui.theme.LRTheme

class TabsBottomSheetFragment : DialogFragment() {
    private var _binding: FragmentTabsBottomSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var browsingModeManager: BrowsingModeManager
    private lateinit var unifiedGroupManager: UnifiedTabGroupManager

    private var composeOrderManager: TabOrderManager? = null
    private var tabViewModel: TabViewModel? = null

    private val profileStateTrigger = mutableStateOf(0) // Triggers recomposition on profile/mode changes
    private val showMenuState = mutableStateOf(false) // Controls context menu visibility
    private val menuTabState = mutableStateOf<TabSessionState?>(null) // Currently selected tab for menu
    private val menuIsInGroupState = mutableStateOf(false) // Whether selected tab is in a group

    private var isInitializing = true
    private val isGridViewState = mutableStateOf(false)
    private val selectedPageState = mutableStateOf(0)
    private var isGridView: Boolean
        get() = isGridViewState.value
        set(value) { isGridViewState.value = value }
    private val viewPrefs by lazy {
        requireContext().getSharedPreferences("tabs_view_prefs", Context.MODE_PRIVATE)
    }

    private val importProfileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val exportManager = ProfileExportManager
                    .getInstance(requireContext())
                val imported = exportManager.importProfile(uri)
                profileStateTrigger.value++
                Toast.makeText(
                    requireContext(),
                    "Profile \"${imported.name}\" imported",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Import failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        const val TAG = "TabsBottomSheet"
        fun newInstance() = TabsBottomSheetFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTabsBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = (activity as BrowserActivity)
        browsingModeManager = activity.browsingModeManager
        unifiedGroupManager = UnifiedTabGroupManager.getInstance(requireContext())

        val store = requireContext().components.store
        val selectedTab = store.state.tabs.find { it.id == store.state.selectedTabId }
        if (selectedTab != null) {
            val isPrivate = selectedTab.content.private
            val tabContextId = selectedTab.contextId

            if (isPrivate && browsingModeManager.mode != BrowsingMode.Private) {
                browsingModeManager.mode = BrowsingMode.Private
            } else if (!isPrivate && browsingModeManager.mode != BrowsingMode.Normal) {
                browsingModeManager.mode = BrowsingMode.Normal
            }

            if (!isPrivate) {
                val profileManager =
                    ProfileManager.getInstance(requireContext())
                when {
                    tabContextId == null || tabContextId == "profile_default" -> {
                        val defaultProfile = profileManager.getAllProfiles().find { it.id == "default" }
                        if (defaultProfile != null && browsingModeManager.currentProfile.id != "default") {
                            profileManager.setActiveProfile(defaultProfile)
                            browsingModeManager.currentProfile = defaultProfile
                        }
                    }

                    tabContextId.startsWith("profile_") -> {
                        val profileId = tabContextId.removePrefix("profile_")
                        val targetProfile = profileManager.getAllProfiles().find { it.id == profileId }
                        if (targetProfile != null && browsingModeManager.currentProfile.id != profileId) {
                            profileManager.setActiveProfile(targetProfile)
                            browsingModeManager.currentProfile = targetProfile
                        }
                    }
                }
            }
        }

        setupUI()

            composeOrderManager = TabOrderManager.getInstance(
                requireContext(),
                unifiedGroupManager
            )
            setupComposeTabViews()
        profileStateTrigger.value++

        isInitializing = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogTheme)
    }

    override fun onStart() {
        super.onStart()

        dialog?.window?.let { window ->
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            val isDark = ThemeManager.isDarkMode(requireContext())
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
            }

            ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                insets
            }
        }
    }

    private fun setupUI() {
        ThemeManager.isDarkMode(requireContext())
        val isAmoled = ThemeManager.isAmoledActive(requireContext())

        if (isAmoled) {
            dialog?.window?.decorView?.setBackgroundColor(Color.BLACK)
        }

        isGridViewState.value = viewPrefs.getBoolean("is_grid_view", false)
    }

    private fun showTabSearch() {
        val searchFragment = TabSearchFragment.newInstance()
        searchFragment.show(parentFragmentManager, TabSearchFragment.TAG)
    }

    private fun switchToProfile(profileId: String, isPrivate: Boolean) {
        if (isInitializing) return

        val profileManager = ProfileManager.getInstance(requireContext())
        if (isPrivate) {
            if (!browsingModeManager.mode.isPrivate) {
                browsingModeManager.mode = BrowsingMode.Private
                profileManager.setPrivateMode(true)
                profileStateTrigger.value++
            }
        } else {
            val profiles = profileManager.getAllProfiles()
            val profile = profiles.find { it.id == profileId }
            if (profile != null && (browsingModeManager.mode.isPrivate || profile.id != browsingModeManager.currentProfile.id)) {
                browsingModeManager.currentProfile = profile
                browsingModeManager.mode = BrowsingMode.Normal
                profileManager.setActiveProfile(profile)
                profileManager.setPrivateMode(false)
                profileStateTrigger.value++
            }
        }
    }

    private fun showMoveToProfileDialog(tabIds: List<String>, isGroup: Boolean = false) {
        val profileManager = ProfileManager.getInstance(requireContext())
        val profiles = profileManager.getAllProfiles()

        val items = profiles.map { it.name }.toMutableList()
        items.add(getString(com.prirai.android.lr.R.string.private_mode_short))

        val tabWord = if (isGroup) "group" else if (tabIds.size > 1) "tabs" else "tab"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Move $tabWord to Profile")
            .setItems(items.toTypedArray()) { _, which ->
                val targetProfileId = if (which == items.size - 1) {
                    "private"
                } else {
                    profiles[which].id
                }

                val migratedCount = profileManager.migrateTabsToProfile(tabIds, targetProfileId)

                Toast.makeText(
                    requireContext(),
                    "Moved $migratedCount $tabWord to ${items[which]}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNewTab() {
        lifecycleScope.launch {
            val isPrivate = browsingModeManager.mode.isPrivate
            val currentProfile = browsingModeManager.currentProfile
            val contextId = BrowserProfile
                .contextIdFor(currentProfile, isPrivate)

            requireContext().components.tabsUseCases.addTab(
                url = "about:homepage",
                private = isPrivate,
                contextId = contextId,
                selectTab = true
            )

            try {
                NavHostFragment.findNavController(this@TabsBottomSheetFragment)
                    .navigate(R.id.homeFragment)
                view?.post { dismiss() }
            } catch (e: Exception) {
                view?.post { dismiss() }
            }
        }
    }

    private fun showProfileCreateDialog() {
        val composeView = ComposeView(requireContext())
        val userPreferences = UserPreferences(requireContext())
        val themeChoice =
            ThemeChoice.entries.toTypedArray()[userPreferences.appThemeChoice]
        val isDark = when (themeChoice) {
            ThemeChoice.DARK -> true
            ThemeChoice.LIGHT -> false
            ThemeChoice.SYSTEM ->
                requireContext().resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
        }

        composeView.setContent {
            LRTheme(
                darkTheme = isDark,
                isPrivateMode = browsingModeManager.mode == BrowsingMode.Private,
                amoledMode = userPreferences.amoledMode,
                dynamicColor = userPreferences.dynamicColors
            ) {
                ProfileCreateDialog(
                    onDismiss = {
                        (composeView.parent as? ViewGroup)?.removeView(composeView)
                    },
                    onConfirm = { name, color, emoji ->
                        val profileManager =
                            ProfileManager.getInstance(requireContext())
                        val newProfile = profileManager.createProfile(name, color, emoji)

                        profileStateTrigger.value++

                        browsingModeManager.currentProfile = newProfile
                        browsingModeManager.mode = BrowsingMode.Normal
                        (composeView.parent as? ViewGroup)?.removeView(composeView)
                    },
                    onImport = {
                        (composeView.parent as? ViewGroup)?.removeView(composeView)
                        importProfileLauncher.launch("*/*")
                    }
                )
            }
        }

        (binding.root as ViewGroup).addView(composeView)
    }

    private fun showProfileEditDialog(profile: BrowserProfile) {
        val composeView = ComposeView(requireContext())
        val userPreferences = UserPreferences(requireContext())
        val themeChoice =
            ThemeChoice.entries.toTypedArray()[userPreferences.appThemeChoice]
        val isDark = when (themeChoice) {
            ThemeChoice.DARK -> true
            ThemeChoice.LIGHT -> false
            ThemeChoice.SYSTEM ->
                requireContext().resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
        }

        composeView.setContent {
            LRTheme(
                darkTheme = isDark,
                isPrivateMode = browsingModeManager.mode == BrowsingMode.Private,
                amoledMode = userPreferences.amoledMode,
                dynamicColor = userPreferences.dynamicColors
            ) {
                ProfileEditDialog(
                    profile = profile,
                    onDismiss = {
                        (composeView.parent as? ViewGroup)?.removeView(composeView)
                    },
                    onConfirm = { name, color, emoji ->
                        val profileManager =
                            ProfileManager.getInstance(requireContext())
                        val updatedProfile = profile.copy(name = name, color = color, emoji = emoji)
                        profileManager.updateProfile(updatedProfile)

                        if (profile.id == browsingModeManager.currentProfile.id) {
                            browsingModeManager.currentProfile = updatedProfile
                        }

                        profileStateTrigger.value++

                        (composeView.parent as? ViewGroup)?.removeView(composeView)
                    },
                    onDelete = {
                        val profileManager =
                            ProfileManager.getInstance(requireContext())

                        if (profile.id == browsingModeManager.currentProfile.id) {
                            browsingModeManager.currentProfile =
                                BrowserProfile.getDefaultProfile()
                        }

                        profileManager.deleteProfile(profile.id)

                        profileStateTrigger.value++
                        (composeView.parent as? ViewGroup)?.removeView(composeView)
                    },
                    onExport = {
                        (composeView.parent as? ViewGroup)?.removeView(composeView)
                        lifecycleScope.launch {
                            try {
                                val exportManager = ProfileExportManager
                                    .getInstance(requireContext())
                                val zipUri = exportManager.exportProfile(profile)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, zipUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(
                                    Intent.createChooser(shareIntent, "Share Profile")
                                )
                            } catch (e: Exception) {
                                Toast.makeText(
                                    requireContext(),
                                    "Export failed: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
            }
        }

        (binding.root as ViewGroup).addView(composeView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        
        TabSheetStateManager.notifyTabSheetDismissed()
        
        _binding = null
    }

    private fun setupComposeTabViews() {
        binding.tabsRecyclerView.visibility = View.GONE

        val container = binding.tabsRecyclerView.parent as? ViewGroup ?: return

        container.findViewWithTag<ComposeView>("tabs_compose")?.let {
            container.removeView(it)
        }

        val composeView = ComposeView(requireContext()).apply {
            tag = "tabs_compose"
            setContent {
                val prefs = UserPreferences(requireContext())
                val isAmoledActive = prefs.amoledMode
                
                LRTheme(amoledMode = isAmoledActive) {
                    ComposeTabContent()
                }
            }
        }

        val layoutParams = binding.tabsRecyclerView.layoutParams
        composeView.layoutParams = layoutParams
        container.addView(composeView)
    }

    @Composable
    private fun ComposeTabContent() {
        val showMenu by showMenuState
        val menuTab by menuTabState
        val menuIsInGroup by menuIsInGroupState

        val viewModel = remember {
            TabViewModel(requireContext(), unifiedGroupManager).also {
                tabViewModel = it
            }
        }

        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            GlobalSnackbarManager.getInstance().coroutineScope = scope
        }

        val snackbarHostState =
            GlobalSnackbarManager.getInstance().snackbarHostState

        val trigger by profileStateTrigger
        LaunchedEffect(trigger) {
            val store = requireContext().components.store
            val browsingMode = browsingModeManager.mode
            val currentProfile = browsingModeManager.currentProfile
            val isPrivateMode = browsingMode.isPrivate
            val profileId = if (isPrivateMode) "private" else currentProfile.id

            fun filterTabs(tabs: List<TabSessionState>) = tabs.filter { tab ->
                val tabIsPrivate = tab.content.private
                if (tabIsPrivate != isPrivateMode) {
                    false
                } else if (isPrivateMode) {
                    tab.contextId == "private"
                } else {
                    val expectedContextId = "profile_${currentProfile.id}"
                    if (currentProfile.id == "default") {
                        (tab.contextId == expectedContextId) || (tab.contextId == null)
                    } else {
                        tab.contextId == expectedContextId
                    }
                }
            }

            launch {
                store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
                    flow.collect { state ->
                        val filteredTabs = filterTabs(state.tabs)
                        viewModel.loadTabsForProfile(profileId, filteredTabs, state.selectedTabId)
                    }
                }
            }

            launch {
                unifiedGroupManager.groupEvents.collect { event ->
                    val state = store.state
                    val filteredTabs = filterTabs(state.tabs)
                    viewModel.loadTabsForProfile(profileId, filteredTabs, state.selectedTabId)
                }
            }
        }

        Scaffold(
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(bottom = 80.dp) // Above bottom buttons
                )
            }
        ) { paddingValues ->
            val isGridView by isGridViewState
            val tabs by viewModel.tabs.collectAsState()
            val selectedPage by selectedPageState
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                TabRow(
                    selectedTabIndex = selectedPage,
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                ) {
                    Tab(
                        selected = selectedPage == 0,
                        onClick = { selectedPageState.value = 0 },
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.tabs))
                                Surface(
                                    shape = CircleShape,
                                    color = if (selectedPage == 0) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    contentColor = if (selectedPage == 0) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = tabs.size.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedPage == 1,
                        onClick = { selectedPageState.value = 1 },
                        text = { Text(stringResource(R.string.profiles)) }
                    )
                }

                if (selectedPage == 1) {
                    val trigger by profileStateTrigger
                    val profiles = remember(trigger) {
                        ProfileManager
                            .getInstance(requireContext())
                            .getAllProfiles()
                    }
                    val tabCounts = remember(trigger, tabs.size) {
                        val allTabs = requireContext().components.store.state.tabs
                        val byContext = HashMap<String?, Int>()
                        var privateCount = 0
                        allTabs.forEach { tab ->
                            if (tab.content.private) privateCount++
                            else byContext[tab.contextId] = (byContext[tab.contextId] ?: 0) + 1
                        }
                        byContext to privateCount
                    }
                    ProfileCardsPage(
                        profiles = profiles,
                        currentProfileId = browsingModeManager.currentProfile.id,
                        isPrivateMode = browsingModeManager.mode == BrowsingMode.Private,
                        tabCountFor = { profile ->
                            val counts = tabCounts.first
                            if (profile.id == "default") {
                                (counts[null] ?: 0) + (counts["profile_default"] ?: 0)
                            } else {
                                counts["profile_${profile.id}"] ?: 0
                            }
                        },
                        privateTabCount = tabCounts.second,
                        onProfileClick = { profile ->
                            switchToProfile(profile.id, false)
                            selectedPageState.value = 0
                        },
                        onProfileLongClick = { profile -> showProfileEditDialog(profile) },
                        onPrivateClick = {
                            switchToProfile("private", true)
                            selectedPageState.value = 0
                        },
                        onAddClick = { showProfileCreateDialog() },
                        modifier = Modifier.weight(1f)
                    )
                } else Box(
                    modifier = Modifier.weight(1f)
                ) {
                    if (isGridView) {
                        TabSheetGridView(
                            viewModel = viewModel,
                            orderManager = composeOrderManager!!,
                            onTabClick = ::handleTabClickCompose,
                            onTabClose = ::handleTabCloseCompose,
                            onTabLongPress = ::handleTabLongPressCompose,
                            onGroupClick = { groupId ->
                                viewModel.toggleGroupExpanded(groupId)
                            },
                            onGroupOptionsClick = { groupId ->
                            }
                        )
                    } else {
                        TabSheetListView(
                            viewModel = viewModel,
                            orderManager = composeOrderManager!!,
                            onTabClick = ::handleTabClickCompose,
                            onTabClose = ::handleTabCloseCompose,
                            onTabLongPress = ::handleTabLongPressCompose,
                            onGroupClick = { groupId ->
                                viewModel.toggleGroupExpanded(groupId)
                            },
                            onGroupOptionsClick = { groupId ->
                            }
                        )
                    }

                    if (showMenu && menuTab != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    showMenuState.value = false
                                    menuTabState.value = null
                                }
                        )

                        TabContextMenu(
                            tab = menuTab!!,
                            isInGroup = menuIsInGroup,
                            onDismiss = {
                                showMenuState.value = false
                                menuTabState.value = null
                            },
                            onMoveToProfile = {
                                showMoveToProfileDialog(listOf(menuTab!!.id))
                                showMenuState.value = false
                                menuTabState.value = null
                            },
                            onRemoveFromGroup = {
                                lifecycleScope.launch {
                                    tabViewModel?.removeTabFromGroup(menuTab!!.id)
                                }
                                showMenuState.value = false
                                menuTabState.value = null
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                        )
                    }
                }

                if (!showMenu) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                        ) {
                            if (selectedPage == 0) {
                                FilledTonalIconButton(onClick = { showTabSearch() }) {
                                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search_tabs_bookmarks_history))
                                }
                                FilledTonalIconButton(
                                    onClick = {
                                        val newValue = !isGridView
                                        isGridViewState.value = newValue
                                        viewPrefs.edit { putBoolean("is_grid_view", newValue) }
                                    }
                                ) {
                                    if (isGridView) {
                                        Icon(Icons.Rounded.ViewList, contentDescription = stringResource(R.string.list_view))
                                    } else {
                                        Icon(Icons.Rounded.GridView, contentDescription = stringResource(R.string.grid_view))
                                    }
                                }
                                FilledIconButton(
                                    onClick = {
                                        addNewTab()
                                        view?.post { dismiss() }
                                    }
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.new_tab))
                                }
                            } else {
                                FilledIconButton(onClick = { showProfileCreateDialog() }) {
                                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.create_profile))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleTabClickCompose(tabId: String) {
        lifecycleScope.launch {
            val store = requireContext().components.store
            val tab = store.state.tabs.find { it.id == tabId }

            if (tab != null) {
                val isPrivate = tab.content.private
                val tabContextId = tab.contextId

                if (isPrivate) {
                    if (browsingModeManager.mode != BrowsingMode.Private) {
                        browsingModeManager.mode = BrowsingMode.Private
                    }
                } else {
                    if (browsingModeManager.mode != BrowsingMode.Normal) {
                        browsingModeManager.mode = BrowsingMode.Normal
                    }

                    val profileManager =
                        ProfileManager.getInstance(requireContext())
                    when {
                        tabContextId == null || tabContextId == "profile_default" -> {
                            val defaultProfile = profileManager.getAllProfiles().find { it.id == "default" }
                            if (defaultProfile != null && browsingModeManager.currentProfile.id != "default") {
                                profileManager.setActiveProfile(defaultProfile)
                                browsingModeManager.currentProfile = defaultProfile
                            }
                        }

                        tabContextId.startsWith("profile_") -> {
                            val profileId = tabContextId.removePrefix("profile_")
                            val targetProfile = profileManager.getAllProfiles().find { it.id == profileId }
                            if (targetProfile != null && browsingModeManager.currentProfile.id != profileId) {
                                profileManager.setActiveProfile(targetProfile)
                                browsingModeManager.currentProfile = targetProfile
                            }
                        }
                    }
                }
            }

            requireContext().components.tabsUseCases.selectTab(tabId)
            dismiss()
        }
    }

    private fun handleTabCloseCompose(tabId: String) {
        tabViewModel?.closeTab(tabId, showUndo = true)
    }

    private fun handleTabLongPressCompose(tab: TabSessionState, isInGroup: Boolean) {
        menuTabState.value = tab
        menuIsInGroupState.value = isInGroup
        showMenuState.value = true
    }

    @Composable
    private fun TabContextMenu(
        tab: TabSessionState,
        isInGroup: Boolean,
        onDismiss: () -> Unit,
        onMoveToProfile: () -> Unit,
        onRemoveFromGroup: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        AnimatedVisibility(
            visible = true,
            enter = androidx.compose.animation.slideInVertically(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()
            ) { it } + androidx.compose.animation.fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = androidx.compose.animation.slideOutVertically(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()
            ) { it } + androidx.compose.animation.fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = modifier
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = tab.content.title.ifEmpty { stringResource(R.string.tab) },
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.close_menu)
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    if (isInGroup) {
                        MenuOption(
                            icon = Icons.Default.Clear,
                            text = stringResource(R.string.remove_from_island),
                            onClick = onRemoveFromGroup
                        )
                    }

                    MenuOption(
                        icon = Icons.Default.AccountCircle,
                        text = stringResource(R.string.move_to_profile),
                        onClick = onMoveToProfile
                    )

                    MenuOption(
                        icon = Icons.Default.Close,
                        text = stringResource(R.string.menu_close_tab),
                        onClick = {
                            handleTabCloseCompose(tab.id)
                            onDismiss()
                        },
                        contentColor = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    @Composable
    private fun MenuOption(
        icon: ImageVector,
        text: String,
        onClick: () -> Unit,
        contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = text,
                    color = contentColor
                )
            },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = contentColor
                )
            },
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

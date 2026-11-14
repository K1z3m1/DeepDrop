@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.browser.bookmark.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.prirai.android.lr.NavGraphDirections
import com.prirai.android.lr.R
import com.prirai.android.lr.browser.bookmark.items.BookmarkFolderItem
import com.prirai.android.lr.browser.bookmark.items.BookmarkSiteItem
import com.prirai.android.lr.browser.bookmark.repository.BookmarkManager
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.theme.ThemeManager
import com.prirai.android.lr.ui.theme.LRTheme

class BookmarksBottomSheetFragment : BottomSheetDialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val manager = BookmarkManager.getInstance(requireContext())
        val startFolder = resolveStartFolder(manager)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val context = LocalContext.current
                val prefs = UserPreferences(context)
                LRTheme(
                    darkTheme = ThemeManager.isDarkMode(context),
                    amoledMode = prefs.amoledMode,
                    dynamicColor = prefs.dynamicColors,
                ) {
                    BookmarksSheet(
                        manager = manager,
                        initialFolder = startFolder,
                        onOpenBookmark = ::openBookmark,
                        onDismiss = ::dismiss,
                    )
                }
            }
        }
    }

    private fun resolveStartFolder(manager: BookmarkManager): BookmarkFolderItem {
        val folderId = arguments?.getLong(ARG_FOLDER_ID, -1L) ?: -1L
        if (folderId == -1L) {
            return manager.root
        }
        return findFolderById(manager.root, folderId) ?: manager.root
    }

    private fun findFolderById(folder: BookmarkFolderItem, targetId: Long): BookmarkFolderItem? {
        if (folder.id == targetId) return folder
        for (item in folder.list) {
            if (item is BookmarkFolderItem) {
                val found = findFolderById(item, targetId)
                if (found != null) return found
            }
        }
        return null
    }

    private fun openBookmark(site: BookmarkSiteItem) {
        requireContext().components.tabsUseCases.addTab.invoke(
            url = site.url,
            selectTab = true,
        )
        try {
            val navController = NavHostFragment.findNavController(this)
            if (navController.currentDestination?.id == R.id.homeFragment) {
                navController.navigate(NavGraphDirections.actionGlobalBrowser(null))
            }
        } catch (e: Exception) {
        }
        dismiss()
    }

    companion object {
        private const val ARG_FOLDER_ID = "folder_id"

        fun newInstance(folderId: Long = -1L): BookmarksBottomSheetFragment {
            return BookmarksBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_FOLDER_ID, folderId)
                }
            }
        }
    }
}

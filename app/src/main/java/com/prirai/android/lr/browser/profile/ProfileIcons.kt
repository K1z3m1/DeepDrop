package com.prirai.android.lr.browser.profile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector

object ProfileIcons {
    val KEYS = listOf(
        "person",
        "school",
        "work",
        "sports",
        "shopping",
        "bank",
        "art",
        "gaming",
        "trophy",
    )

    private val legacyEmojiMap: Map<String, String> = mapOf(
        "👤" to "person",
        "🎓" to "school",
        "💼" to "work",
        "⚽" to "sports",
        "🛒" to "shopping",
        "🏦" to "bank",
        "🎨" to "art",
        "🎮" to "gaming",
        "🔵" to "person",
        "🟢" to "person",
        "🟡" to "person",
        "🟣" to "person",
        "🟠" to "person",
        "🔴" to "person",
    )

    private val icons: Map<String, ImageVector> = mapOf(
        "person" to Icons.Filled.Person,
        "school" to Icons.Filled.School,
        "work" to Icons.Filled.Work,
        "sports" to Icons.Filled.SportsSoccer,
        "shopping" to Icons.Filled.ShoppingCart,
        "bank" to Icons.Filled.Paid,
        "art" to Icons.Filled.Brush,
        "gaming" to Icons.Filled.SportsEsports,
        "trophy" to Icons.Filled.EmojiEvents,
    )

    fun iconFor(stored: String?): ImageVector =
        icons[normalize(stored)] ?: Icons.Filled.Person

    private fun normalize(stored: String?): String =
        legacyEmojiMap[stored] ?: stored ?: "person"
}

package org.koitharu.kotatsu.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.koitharu.kotatsu.R

@Keep
enum class ColorScheme(
	@StyleRes val styleResId: Int,
	@StringRes val titleResId: Int,
) {

	MONOCHROME(R.style.ThemeOverlay_Kotatsu_Monochrome, R.string.theme_name_monochrome),
	;

	companion object {

		val default: ColorScheme
			get() = MONOCHROME

		fun getAvailableList(): List<ColorScheme> = entries
	}
}

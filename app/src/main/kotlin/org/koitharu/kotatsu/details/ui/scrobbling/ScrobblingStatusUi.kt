package org.koitharu.kotatsu.details.ui.scrobbling

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus

@get:StringRes
val ScrobblingStatus.labelResId: Int
	get() = when (this) {
		ScrobblingStatus.PLANNED -> R.string.status_planned
		ScrobblingStatus.READING -> R.string.status_reading
		ScrobblingStatus.RE_READING -> R.string.status_re_reading
		ScrobblingStatus.COMPLETED -> R.string.status_completed
		ScrobblingStatus.ON_HOLD -> R.string.status_on_hold
		ScrobblingStatus.DROPPED -> R.string.status_dropped
	}

@get:DrawableRes
val ScrobblingStatus.iconResId: Int
	get() = when (this) {
		ScrobblingStatus.PLANNED -> R.drawable.ic_bookmark
		ScrobblingStatus.READING -> R.drawable.ic_eye
		ScrobblingStatus.RE_READING -> R.drawable.ic_history
		ScrobblingStatus.COMPLETED -> R.drawable.ic_eye_check
		ScrobblingStatus.ON_HOLD -> R.drawable.ic_status_on_hold
		ScrobblingStatus.DROPPED -> R.drawable.ic_status_dropped
	}

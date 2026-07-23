package org.koitharu.kotatsu.reader.ui.pager.doublepage

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.reader.ui.pager.standard.PageHolder

fun RecyclerView.visiblePageHolders(): Sequence<PageHolder> {
	val lm = layoutManager as? LinearLayoutManager ?: return emptySequence()
	return (lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()).asSequence()
		.mapNotNull { findViewHolderForAdapterPosition(it) as? PageHolder }
}


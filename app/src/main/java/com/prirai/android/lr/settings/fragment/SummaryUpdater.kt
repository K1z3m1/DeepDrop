package com.prirai.android.lr.settings.fragment

import androidx.preference.Preference

class SummaryUpdater(private val preference: Preference) {
    fun updateSummary(text: String) {
        preference.summary = text
    }
}
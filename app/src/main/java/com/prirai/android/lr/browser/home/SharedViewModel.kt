package com.prirai.android.lr.browser.home

import androidx.lifecycle.ViewModel
import mozilla.components.browser.state.state.content.DownloadState

class SharedViewModel : ViewModel() {
    var downloadDialogState: HashMap<String?, Pair<DownloadState?, Boolean>> = HashMap()
}

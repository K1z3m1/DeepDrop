package com.prirai.android.lr.ssl

import android.content.Context
import androidx.fragment.app.FragmentActivity

fun Context.showSslDialog() {
    val activity = this as? FragmentActivity ?: return
    
    val bottomSheet = ConnectionInfoBottomSheet.newInstance()
    bottomSheet.show(activity.supportFragmentManager, ConnectionInfoBottomSheet.TAG)
}

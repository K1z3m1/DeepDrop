package com.firstt175.novaframe.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.firstt175.novaframe.prefs.AppLanguagePrefs
import com.firstt175.novaframe.session.service.NovaForegroundService

/**
 * Translucent activity used to obtain the RECORD_AUDIO runtime permission
 * from a non-Activity context (the running [NovaForegroundService], which
 * has no window of its own to host the system permission dialog).
 *
 * Launched only when the user selects "อัดหน้าจอ" (recording) with the
 * Microphone toggle on and RECORD_AUDIO isn't granted yet — see
 * [NovaForegroundService.handleRecordingSelection]. Whatever the user
 * answers, the result is handed straight back to the service via
 * [NovaForegroundService.notifyMicPermissionResolved] so the SESSION MODE
 * selection in the drawer can finish (and lock) instead of silently doing
 * nothing, which was the previous behavior.
 */
class MicPermissionRequestActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguagePrefs.wrap(newBase))
    }

    companion object {
        fun buildIntent(ctx: Context): Intent =
            Intent(ctx, MicPermissionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        NovaForegroundService.notifyMicPermissionResolved(granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            // Lost the race against another grant (e.g. from a system
            // settings screen) between the service's check and this
            // activity actually starting — nothing to prompt for.
            NovaForegroundService.notifyMicPermissionResolved(true)
            finish()
            return
        }
        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }
}

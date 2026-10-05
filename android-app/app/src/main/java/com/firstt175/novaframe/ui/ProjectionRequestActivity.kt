package com.firstt175.novaframe.ui

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.firstt175.novaframe.R
import com.firstt175.novaframe.prefs.AppLanguagePrefs
import com.firstt175.novaframe.prefs.NovaPreferences
import com.firstt175.novaframe.session.service.NovaForegroundService
/**
 * Translucent activity used to obtain a
 * MediaProjection consent token from a non-foreground context.
 *
 * The accessibility service that detects foreground apps cannot launch
 * `createScreenCaptureIntent()` directly — Android requires an Activity context
 * to request it. Hosting the prompt here also satisfies the Android 12+
 * requirement that a `mediaProjection` foreground service be started from an
 * Activity-visible context.
 */
class ProjectionRequestActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguagePrefs.wrap(newBase))
    }

    companion object {
        private const val TAG = "ProjReqActivity"
        private const val EXTRA_TARGET_PACKAGE = "target_package"

        fun buildIntent(ctx: Context, targetPackage: String): Intent =
            Intent(ctx, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
    }

    private var targetPackage: String? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pkg = targetPackage
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null || pkg == null) {
            Toast.makeText(this, R.string.perm_capture_denied, Toast.LENGTH_SHORT).show()
            finish()
            return@registerForActivityResult
        }
        val prefs = NovaPreferences(this).load()
        val intent = NovaForegroundService.buildStartIntent(
            ctx = this,
            resultCode = result.resultCode,
            resultData = data,
            targetPackage = pkg,
            hudEnabled = prefs.hudEnabled,
        )
        ContextCompat.startForegroundService(this, intent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (targetPackage == null) {
            Log.w(TAG, "No target package provided — finishing")
            finish()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: the system picker offers "A single app" — that is the mode we
            // want (only the game is captured, our overlay can never be captured back).
            // The API cannot force it, so remind the user which option to pick.
            Toast.makeText(this, R.string.perm_capture_pick_app, Toast.LENGTH_LONG).show()
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }
}

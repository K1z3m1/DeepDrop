package app.fluxa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible helper: the record button in the panel needs the system screen-capture permission,
 * which can only be asked from an activity. It asks for the microphone first (once), then the
 * capture dialog, and hands the result to [CaptureService] (panel-only session).
 */
class RecordConsentActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("fluxa", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // already waiting on a dialog
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(KEY_AUDIO_ASKED, false)
        ) {
            prefs.edit().putBoolean(KEY_AUDIO_ASKED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        openCaptureDialog()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            I18n.toastMake(this, "ไม่อนุญาตเสียง · จะอัดแค่ภาพ", Toast.LENGTH_LONG).show()
        }
        openCaptureDialog()
    }

    private fun openCaptureDialog() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            I18n.toastMake(this, "ยกเลิกการแชร์หน้าจอ", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        runCatching {
            startService(
                Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_RECORD_GRANT)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, data),
            )
        }
        finish()
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_AUDIO = 2
        const val KEY_AUDIO_ASKED = "rec_audio_asked"
    }
}

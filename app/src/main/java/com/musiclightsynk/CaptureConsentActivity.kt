package com.musiclightsynk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity that asks for screen-capture permission and hands the grant to
 * [ScreenCaptureService]. After `adb shell appops set com.musiclightsynk PROJECT_MEDIA allow`
 * the system approves it instantly without showing a dialog.
 *
 * Exported but protected by android.permission.DUMP, so only ADB (shell) and this app can start it:
 *   adb shell am start -n com.musiclightsynk/.CaptureConsentActivity
 */
class CaptureConsentActivity : Activity() {

    companion object {
        private const val REQUEST = 42

        fun intent(ctx: Context): Intent = Intent(ctx, CaptureConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (ScreenCaptureService.isRunning) {
            finish()
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "Screen capture is not available on this device", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, resultCode, data)
            } else {
                Toast.makeText(this, "Screen capture was not allowed", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}

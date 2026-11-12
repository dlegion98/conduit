package dev.conduit.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import dev.conduit.feature.MirrorService
import dev.conduit.net.LinkManager

/** Shows Android's screen-capture consent dialog, then starts MirrorService. */
class MirrorConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Mirroring needs the whole screen so remote control coordinates line up.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ)
    }

    @Deprecated("Simple one-shot result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            MirrorService.start(
                this, resultCode, data,
                intent.getIntExtra("maxSize", 1920), intent.getIntExtra("bitrate", 8_000_000), intent.getIntExtra("fps", 60)
            )
        } else {
            LinkManager.send("error") { put("feature", "mirror"); put("message", "Screen sharing was declined on the phone") }
        }
        finish()
    }

    companion object { private const val REQ = 41 }
}

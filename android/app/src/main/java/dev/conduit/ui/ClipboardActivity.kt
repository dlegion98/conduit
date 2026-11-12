package dev.conduit.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import dev.conduit.feature.ClipboardHandler

/** Invisible activity: Android only lets a focused app read the clipboard. */
class ClipboardActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            val result = runCatching { ClipboardHandler.sendLocal(this) }.getOrElse { it.message ?: "Error" }
            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
            finish()
            if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }
}

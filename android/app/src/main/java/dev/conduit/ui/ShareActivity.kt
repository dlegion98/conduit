package dev.conduit.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.conduit.feature.FileTransfer
import dev.conduit.net.LinkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share sheet target: links open on the Mac, text goes to its clipboard, files go to Downloads. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LinkManager.isConnected) {
            Toast.makeText(this, "Mac not connected", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val uris = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        }
        if (uris.isNotEmpty()) {
            Toast.makeText(this, "Sending ${uris.size} file${if (uris.size > 1) "s" else ""} to Mac…", Toast.LENGTH_SHORT).show()
            // Stay alive (invisibly) until the transfer finishes: our read access to the files ends with this activity.
            val job = FileTransfer.push(applicationContext, uris)
            LinkManager.scope.launch {
                job.join()
                withContext(Dispatchers.Main) { finish() }
            }
            moveTaskToBack(true)
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (text.isNullOrEmpty()) { finish(); return }
        val url = Regex("https?://\\S+").find(text)?.value
        if (url != null) {
            LinkManager.send("open-url") { put("url", url) }
            Toast.makeText(this, "Opened on Mac", Toast.LENGTH_SHORT).show()
        } else {
            LinkManager.send("clipboard") { put("text", text) }
            Toast.makeText(this, "Copied to Mac clipboard", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

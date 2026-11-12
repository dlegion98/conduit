package dev.conduit.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import dev.conduit.feature.CameraService
import dev.conduit.net.LinkManager

/**
 * Android only grants camera access to a foreground service started while the app is
 * in the foreground, so this invisible activity starts CameraService and closes.
 */
class CameraLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start(); finish()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else LinkManager.send("error") { put("feature", "camera"); put("message", "Camera permission was declined on the phone") }
        finish()
    }

    private fun start() {
        CameraService.start(
            this,
            intent.getStringExtra("facing") ?: "back",
            intent.getIntExtra("width", 1920),
            intent.getIntExtra("height", 1080),
            intent.getIntExtra("fps", 30),
        )
    }
}

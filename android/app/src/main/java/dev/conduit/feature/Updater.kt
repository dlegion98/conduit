package dev.conduit.feature

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dev.conduit.ConduitApp
import dev.conduit.R
import dev.conduit.net.LinkManager
import dev.conduit.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app from the GitHub release without a browser: reads version.json,
 * downloads the APK and installs it with PackageInstaller. After the first in-app update,
 * Android 12+ installs later updates without asking.
 */
object Updater {
    private const val MANIFEST = "https://github.com/dlegion98/conduit/releases/latest/download/version.json"
    private const val NOTIF_ID = 90
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val version: String) : State()
        data class Available(val build: Long, val version: String, val notes: String, val url: String, val size: Long) : State()
        data class Downloading(val version: String, val progress: Float) : State()
        data object Installing : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun currentBuild(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"

    /** Checks at most every few hours unless [force]; posts a notification when [notify] and one is found. */
    fun check(context: Context, force: Boolean = false, notify: Boolean = false) {
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("lastCheck", 0) < CHECK_EVERY_MS && _state.value !is State.Failed) return
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        LinkManager.scope.launch {
            _state.value = State.Checking
            try {
                val json = JSONObject(httpGet(MANIFEST))
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                val build = json.getLong("build")
                val android = json.getJSONObject("android")
                if (build > currentBuild(context)) {
                    val available = State.Available(build, json.getString("version"), json.optString("notes"), android.getString("url"), android.optLong("size"))
                    _state.value = available
                    if (notify && prefs.getLong("notified", 0) != build) {
                        prefs.edit().putLong("notified", build).apply()
                        notifyAvailable(context, available)
                    }
                } else {
                    _state.value = State.UpToDate(currentVersion(context))
                }
            } catch (e: Exception) {
                Log.w("Conduit", "update check failed", e)
                _state.value = if (force) State.Failed("Couldn't check for updates") else State.Idle
            }
        }
    }

    /** Android requires a one-time "Install unknown apps" permission for Conduit. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context) {
        val update = _state.value as? State.Available ?: return
        val app = context.applicationContext
        LinkManager.scope.launch {
            try {
                val apk = File(app.cacheDir, "update.apk")
                download(update.url, apk) { p -> _state.value = State.Downloading(update.version, p) }
                _state.value = State.Installing
                installApk(app, apk)
            } catch (e: Exception) {
                Log.w("Conduit", "update failed", e)
                _state.value = State.Failed(e.message ?: "Update failed")
            }
        }
    }

    private fun installApk(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Silent when Conduit installed the current version itself (Android 12+).
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("conduit.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure {
                    Launcher.notifyTap(context, confirm, "Conduit update ready", "Tap to install")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle // the app restarts on the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)"
                _state.value = State.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || msg.contains("signatures", true))
                        "This version is signed differently. Uninstall Conduit once and install it from the release page; after that, updates work in the app."
                    else msg
                )
            }
        }
    }

    private fun notifyAvailable(context: Context, update: State.Available) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, ConduitApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Conduit ${update.version} is available")
            .setContentText(update.notes.ifBlank { "Open Conduit to update" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000; instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    private fun download(url: String, to: File, progress: (Float) -> Unit) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = true
        }
        val total = c.contentLengthLong
        var done = 0L
        var last = -1
        c.inputStream.use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; progress(pct / 100f) }
                }
            }
        }
        c.disconnect()
        if (total > 0 && done != total) error("Download was interrupted")
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onInstallResult(context, intent)
}

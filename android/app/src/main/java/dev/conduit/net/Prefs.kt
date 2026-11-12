package dev.conduit.net

import android.content.Context

/** User preferences on the phone. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Skip mirroring notifications while you're using the phone (it's unlocked and the screen is on). */
    fun onlyWhenLocked(c: Context) = p(c).getBoolean("onlyWhenLocked", false)
    fun setOnlyWhenLocked(c: Context, v: Boolean) = p(c).edit().putBoolean("onlyWhenLocked", v).apply()
}

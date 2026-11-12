package dev.conduit.feature

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Turns clicks, drags and typing on the Mac's mirror window into taps, swipes and
 * text on the phone. It only acts on input from the paired Mac and never reads or
 * stores screen content. Being an accessibility service also lets Conduit open
 * screens from the background (camera, mirror consent, links), which Android
 * otherwise blocks.
 */
class RemoteControlService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    @Suppress("DEPRECATION")
    private fun screen(): DisplayMetrics {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return DisplayMetrics().also { d.getRealMetrics(it) }
    }

    private fun px(x: Double, y: Double): Pair<Float, Float> {
        val m = screen()
        return (x.coerceIn(0.0, 1.0) * (m.widthPixels - 1)).toFloat() to (y.coerceIn(0.0, 1.0) * (m.heightPixels - 1)).toFloat()
    }

    private fun stroke(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 59_000)))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun perform(msg: JSONObject) {
        when (msg.optString("action")) {
            "tap" -> {
                val (x, y) = px(msg.optDouble("x"), msg.optDouble("y"))
                stroke(Path().apply { moveTo(x, y) }, 40)
            }
            "longpress" -> {
                val (x, y) = px(msg.optDouble("x"), msg.optDouble("y"))
                stroke(Path().apply { moveTo(x, y) }, 650)
            }
            "gesture" -> {
                val pts = msg.optJSONArray("points") ?: return
                if (pts.length() == 0) return
                val path = Path()
                for (i in 0 until pts.length()) {
                    val p = pts.getJSONArray(i)
                    val (x, y) = px(p.getDouble(0), p.getDouble(1))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                stroke(path, msg.optLong("durationMs", 300))
            }
            "global" -> performGlobalAction(
                when (msg.optString("key")) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                    "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
                    "lock" -> GLOBAL_ACTION_LOCK_SCREEN
                    "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
                    else -> return
                }
            )
            "text" -> editFocused { text, start, end -> Triple(text.replaceRange(start, end, msg.optString("text")), start + msg.optString("text").length, true) }
            "backspace" -> editFocused { text, start, end ->
                when {
                    start != end -> Triple(text.removeRange(start, end), start, true)
                    start > 0 -> Triple(text.removeRange(start - 1, start), start - 1, true)
                    else -> Triple(text, start, false)
                }
            }
            "enter" -> {
                val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
    }

    /** Edits the focused text field: [edit] gets (text, selStart, selEnd) and returns (newText, cursor, changed). */
    private fun editFocused(edit: (String, Int, Int) -> Triple<String, Int, Boolean>) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!node.isEditable) return
        val current = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        var start = node.textSelectionStart.takeIf { it >= 0 } ?: current.length
        var end = node.textSelectionEnd.takeIf { it >= 0 } ?: current.length
        if (start > end) start = end.also { end = start }
        start = start.coerceIn(0, current.length); end = end.coerceIn(0, current.length)
        val (newText, cursor, changed) = edit(current, start, end)
        if (!changed) return
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
    }

    companion object {
        @Volatile var instance: RemoteControlService? = null
            private set

        fun handle(msg: JSONObject) {
            val svc = instance
            if (svc == null) {
                dev.conduit.net.LinkManager.send("error") {
                    put("feature", "control")
                    put("message", "Turn on Conduit remote control in your phone's Accessibility settings")
                }
                return
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { svc.perform(msg) }
        }
    }
}

package dev.conduit.feature

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
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
            "dpad" -> dpad(msg.optString("key"), msg.optBoolean("shift"))
            "pinch" -> pinch(msg.optDouble("x", 0.5), msg.optDouble("y", 0.5), msg.optDouble("scale", 1.0), msg.optLong("durationMs", 400))
            "enter" -> {
                val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
    }

    /**
     * Arrow keys and Tab from the Mac. In a text field, left/right move the cursor (Shift extends
     * the selection); Tab moves to the next field; everything else is a D-pad press (Android 13+).
     */
    private fun dpad(key: String, shift: Boolean) {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && (key == "left" || key == "right")) {
            moveCursor(focused, if (key == "left") -1 else 1, shift)
            return
        }
        if (key == "tab") {
            val next = focused?.focusSearch(if (shift) View.FOCUS_BACKWARD else View.FOCUS_FORWARD)
            if (next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        performGlobalAction(
            when (key) {
                "up" -> GLOBAL_ACTION_DPAD_UP
                "down" -> GLOBAL_ACTION_DPAD_DOWN
                "left" -> GLOBAL_ACTION_DPAD_LEFT
                "right" -> GLOBAL_ACTION_DPAD_RIGHT
                "center" -> GLOBAL_ACTION_DPAD_CENTER
                "tab" -> if (shift) GLOBAL_ACTION_DPAD_UP else GLOBAL_ACTION_DPAD_DOWN
                else -> return
            }
        )
    }

    private fun moveCursor(node: AccessibilityNodeInfo, delta: Int, extend: Boolean) {
        val length = if (node.isShowingHintText) 0 else node.text?.length ?: 0
        val start = node.textSelectionStart.takeIf { it >= 0 }?.coerceIn(0, length) ?: length
        val end = node.textSelectionEnd.takeIf { it >= 0 }?.coerceIn(0, length) ?: length
        val (newStart, newEnd) = when {
            extend -> start to (end + delta).coerceIn(0, length)
            start != end -> (if (delta < 0) minOf(start, end) else maxOf(start, end)).let { it to it }
            else -> (start + delta).coerceIn(0, length).let { it to it }
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newEnd)
        })
    }

    /** Trackpad pinch: two fingers moving apart (scale > 1, zoom in) or together around (x, y). */
    private fun pinch(x: Double, y: Double, scale: Double, durationMs: Long) {
        val s = scale.coerceIn(0.2, 5.0)
        if (kotlin.math.abs(s - 1) < 0.05) return
        val m = screen()
        val w = m.widthPixels.toFloat(); val h = m.heightPixels.toFloat()
        val minDim = minOf(w, h)
        val (cx, cy) = px(x, y)
        val r0 = minDim * if (s >= 1) 0.08f else 0.32f
        val r1 = (r0 * s.toFloat()).coerceIn(minDim * 0.03f, minDim * 0.42f)
        val d = 0.7071f
        fun finger(sign: Float) = Path().apply {
            moveTo((cx + sign * r0 * d).coerceIn(0f, w - 1), (cy + sign * r0 * d).coerceIn(0f, h - 1))
            lineTo((cx + sign * r1 * d).coerceIn(0f, w - 1), (cy + sign * r1 * d).coerceIn(0f, h - 1))
        }
        val duration = durationMs.coerceIn(100, 2_000)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(finger(1f), 0, duration))
            .addStroke(GestureDescription.StrokeDescription(finger(-1f), 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
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

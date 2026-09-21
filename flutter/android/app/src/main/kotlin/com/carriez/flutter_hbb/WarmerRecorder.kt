package com.carriez.flutter_hbb

/**
 * WarmerRecorder — on-device macro recorder for client self-service templates (no ADB).
 *
 * Observes taps/swipes RustDesk injects through InputService and turns them into a
 * replayable template. Text fields are handled by INLINE MARKING (Variant A):
 * the client taps a field, then issues record_mark_field {var} — the recorder marks
 * that field as an `input` step, suppresses the following keyboard taps, and captures
 * the final text from the a11y tree (findFocus). A light auto-detect (isEditable)
 * stays as a fallback for native fields the client didn't mark.
 *
 * Toggled via WarmerCommandExecutor: record_start / record_mark_field / record_stop.
 * Hooked from InputService.onMouseInput (LEFT_UP). Passive — never blocks injection.
 */
import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object WarmerRecorder {
    private const val TAG = "WarmerRec"
    private const val KEYBOARD_TOP_Y = 1400   // taps below this while capturing = typing (suppressed)

    @Volatile var recording: Boolean = false
        private set

    private val steps = JSONArray()
    private var lastActionAt = 0L

    // Field currently being captured (after a mark or an editable-tap). Text is read
    // and the `input` step emitted when the next non-keyboard action happens.
    private var capturing = false
    private var capX = -1
    private var capY = -1
    private var capAnchor: String? = null
    private var capVar: String? = null
    private var fieldCounter = 0

    @Synchronized
    fun start(svc: AccessibilityService) {
        while (steps.length() > 0) steps.remove(0)
        recording = true
        lastActionAt = System.currentTimeMillis()
        capturing = false; capX = -1; capY = -1; capAnchor = null; capVar = null
        fieldCounter = 0
        WarmerOverlay.show(svc)
        Log.i(TAG, "recording started")
    }

    @Synchronized
    fun stop(svc: AccessibilityService?): JSONObject {
        if (svc != null && capturing) flushInput(svc)
        recording = false
        WarmerOverlay.hide()
        val out = JSONObject().apply { put("steps", cloneSteps()); put("count", steps.length()) }
        Log.i(TAG, "recording stopped: ${steps.length()} steps")
        return out
    }

    /** Inline field marking (Variant A). Marks the currently-focused field as an input
     *  step named [varName]; strips any keyboard taps already recorded for it. */
    @Synchronized
    fun markField(svc: AccessibilityService, varName: String?): JSONObject {
        if (!recording) return JSONObject().apply { put("ok", false); put("error", "not recording") }
        // Resolve the field: prefer the actually-focused input, else the last tap.
        var fx = -1; var fy = -1; var anchor: String? = null
        try {
            val f = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null) { val r = Rect(); f.getBoundsInScreen(r); fx = r.centerX(); fy = r.centerY(); anchor = anchorOf(f); f.recycleSafe() }
        } catch (_: Exception) {}
        // Strip trailing keyboard-region taps + the field-focus tap already recorded.
        for (i in steps.length() - 1 downTo 0) {
            val s = steps.optJSONObject(i) ?: break
            if (s.optString("type") == "tap") {
                val ty = s.optInt("y", 0)
                if (ty > KEYBOARD_TOP_Y) { steps.remove(i); continue }           // typed keyboard tap
                if (fx < 0) { fx = s.optInt("x"); fy = ty; anchor = s.optString("label", null) }
                steps.remove(i); break                                            // the field-focus tap
            } else break
        }
        capturing = true; capX = fx; capY = fy; capAnchor = anchor; capVar = if (varName.isNullOrBlank()) "field_${++fieldCounter}" else varName
        Log.i(TAG, "field marked var=$capVar at ($capX,$capY)")
        return JSONObject().apply { put("ok", true); put("field", anchor ?: JSONObject.NULL); put("var", capVar ?: JSONObject.NULL); put("x", capX); put("y", capY) }
    }

    /** Called from InputService on a completed pointer-up. */
    @Synchronized
    fun onPointerUp(svc: AccessibilityService, x: Int, y: Int, isTap: Boolean, downX: Int, downY: Int) {
        if (!recording) return
        try {
            val now = System.currentTimeMillis()
            if (isTap && WarmerOverlay.rect?.contains(x, y) == true) { markField(svc, null); return }
            if (isTap) {
                // While capturing a field, keyboard-area taps are the user typing → suppress.
                if (capturing && y > KEYBOARD_TOP_Y) return
                if (capturing) flushInput(svc)   // a tap outside the keyboard ends text entry
                val node = nodeAt(svc, x, y)
                val editable = node?.let { isEditable(it) } == true
                if (editable && !capturing) {     // auto-detect fallback: start capturing (unnamed)
                    capturing = true; capX = x; capY = y; capAnchor = anchorOf(node); capVar = null
                    node?.recycleSafe(); return
                }
                steps.put(JSONObject().apply {
                    put("type", "tap"); put("x", x); put("y", y); put("wait", waitMs(now))
                    anchorOf(node)?.let { put("label", it) }
                })
                node?.recycleSafe()
            } else {
                if (capturing) flushInput(svc)
                val dx = x - downX; val dy = y - downY
                steps.put(
                    if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= 100)
                        JSONObject().apply { put("type", "scroll"); put("direction", if (dy < 0) "down" else "up"); put("wait", waitMs(now)) }
                    else
                        JSONObject().apply { put("type", "swipe"); put("x0", downX); put("y0", downY); put("x1", x); put("y1", y); put("wait", waitMs(now)) }
                )
            }
        } catch (e: Exception) { Log.w(TAG, "onPointerUp: ${e.message}") }
    }

    private fun flushInput(svc: AccessibilityService) {
        if (!capturing) return
        var text: String? = null
        try {
            val f = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            f?.let { it.refreshSafe(); text = it.text?.toString(); it.recycleSafe() }
            if (text == null && capX >= 0) { nodeAt(svc, capX, capY)?.let { it.refreshSafe(); text = it.text?.toString(); it.recycleSafe() } }
        } catch (_: Exception) {}
        steps.put(JSONObject().apply {
            put("type", "input"); put("x", capX); put("y", capY)
            capAnchor?.let { put("field", it) }
            put("text", text ?: "")
            put("var", capVar ?: JSONObject.NULL)
            put("wait", waitMs(System.currentTimeMillis()))
        })
        capturing = false; capX = -1; capY = -1; capAnchor = null; capVar = null
    }

    private fun cloneSteps(): JSONArray { val a = JSONArray(); for (i in 0 until steps.length()) a.put(steps.get(i)); return a }
    private fun waitMs(now: Long): Long { val d = now - lastActionAt; lastActionAt = now; return d.coerceIn(0, 8000) }

    private fun nodeAt(svc: AccessibilityService, x: Int, y: Int): AccessibilityNodeInfo? {
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return null
        return deepest(root, x, y)
    }
    private fun deepest(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val r = Rect(); node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val hit = deepest(c, x, y)
            if (hit != null) { if (hit !== c) c.recycleSafe(); return hit }
            c.recycleSafe()
        }
        return node
    }
    private fun isEditable(n: AccessibilityNodeInfo): Boolean =
        try { n.isEditable || (n.className?.toString()?.contains("EditText") == true) } catch (_: Exception) { false }
    private fun anchorOf(n: AccessibilityNodeInfo?): String? {
        if (n == null) return null
        return try {
            n.viewIdResourceName
                ?: (if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null)
                ?: n.contentDescription?.toString() ?: n.text?.toString()
        } catch (_: Exception) { null }
    }
    private fun AccessibilityNodeInfo.recycleSafe() { try { @Suppress("DEPRECATION") recycle() } catch (_: Exception) {} }
    private fun AccessibilityNodeInfo.refreshSafe() { try { refresh() } catch (_: Exception) {} }
}

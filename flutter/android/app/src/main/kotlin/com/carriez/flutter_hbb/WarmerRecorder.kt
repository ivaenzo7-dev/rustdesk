package com.carriez.flutter_hbb

/**
 * WarmerRecorder — on-device macro recorder for client self-service templates.
 *
 * No ADB: it observes the taps/swipes that RustDesk injects through InputService
 * (the same remote input the renter performs from the web client) and turns them
 * into a replayable template. Text fields are auto-detected (AccessibilityNodeInfo
 * .isEditable) and captured as `input` steps (the typed text is read from the a11y
 * tree, independent of how it was typed) so they can be parameterised later.
 *
 * Hooked from InputService.onMouseInput (LEFT_UP) and toggled via WarmerCommandExecutor
 * (record_start / record_stop). Recording is passive — it never blocks injection.
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

    @Volatile var recording: Boolean = false
        private set

    private val steps = JSONArray()
    private var startedAt = 0L
    private var lastActionAt = 0L

    // A field the user just focused; its text is flushed when the next structural
    // action happens (so intermediate keyboard taps/keystrokes are ignored and we
    // record the semantic result — the final text — instead).
    private var pendingFieldX = -1
    private var pendingFieldY = -1
    private var pendingFieldAnchor: String? = null

    @Synchronized
    fun start() {
        steps.length().let { while (steps.length() > 0) steps.remove(0) }
        recording = true
        startedAt = System.currentTimeMillis()
        lastActionAt = startedAt
        pendingFieldX = -1; pendingFieldY = -1; pendingFieldAnchor = null
        Log.i(TAG, "recording started")
    }

    @Synchronized
    fun stop(svc: AccessibilityService?): JSONObject {
        if (svc != null) flushField(svc)   // capture any field left focused
        recording = false
        val out = JSONObject().apply {
            put("steps", cloneSteps())
            put("count", steps.length())
        }
        Log.i(TAG, "recording stopped: ${steps.length()} steps")
        return out
    }

    private fun cloneSteps(): JSONArray {
        val a = JSONArray()
        for (i in 0 until steps.length()) a.put(steps.get(i))
        return a
    }

    private fun waitMs(now: Long): Long {
        val d = now - lastActionAt
        lastActionAt = now
        return d.coerceIn(0, 8000)
    }

    /** Called from InputService on a completed pointer-up. */
    @Synchronized
    fun onPointerUp(svc: AccessibilityService, x: Int, y: Int, isTap: Boolean, downX: Int, downY: Int) {
        if (!recording) return
        try {
            val now = System.currentTimeMillis()
            if (isTap) {
                val node = nodeAt(svc, x, y)
                val editable = node?.let { isEditable(it) } == true
                if (editable) {
                    // Focusing a text field: remember it; text captured on flush.
                    flushField(svc)                       // flush a previous field, if any
                    pendingFieldX = x; pendingFieldY = y
                    pendingFieldAnchor = anchorOf(node)
                    node?.recycleSafe()
                    return
                }
                flushField(svc)                            // a non-field tap flushes the field first
                val step = JSONObject().apply {
                    put("type", "tap"); put("x", x); put("y", y)
                    put("wait", waitMs(now))
                    anchorOf(node)?.let { put("label", it) }
                }
                steps.put(step)
                node?.recycleSafe()
            } else {
                flushField(svc)
                val dx = x - downX; val dy = y - downY
                val step = if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= 120) {
                    JSONObject().apply { put("type", "scroll"); put("direction", if (dy < 0) "down" else "up"); put("wait", waitMs(now)) }
                } else {
                    JSONObject().apply { put("type", "swipe"); put("x0", downX); put("y0", downY); put("x1", x); put("y1", y); put("wait", waitMs(now)) }
                }
                steps.put(step)
            }
        } catch (e: Exception) { Log.w(TAG, "onPointerUp: ${e.message}") }
    }

    /** Emit an `input` step for the currently-focused field (text read from the tree). */
    private fun flushField(svc: AccessibilityService) {
        if (pendingFieldX < 0) return
        var text: String? = null
        try {
            val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focused?.let { it.refreshSafe(); text = it.text?.toString(); it.recycleSafe() }
            if (text == null) { val n = nodeAt(svc, pendingFieldX, pendingFieldY); n?.let { it.refreshSafe(); text = it.text?.toString(); it.recycleSafe() } }
        } catch (_: Exception) {}
        val step = JSONObject().apply {
            put("type", "input")
            put("x", pendingFieldX); put("y", pendingFieldY)
            pendingFieldAnchor?.let { put("field", it) }
            put("text", text ?: "")
            put("var", JSONObject.NULL)     // named later in the review UI
            put("wait", waitMs(System.currentTimeMillis()))
        }
        steps.put(step)
        pendingFieldX = -1; pendingFieldY = -1; pendingFieldAnchor = null
    }

    // ── a11y helpers ──────────────────────────────────────────────────────────
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

    private fun isEditable(n: AccessibilityNodeInfo): Boolean {
        return try {
            n.isEditable || (n.className?.toString()?.contains("EditText") == true)
        } catch (_: Exception) { false }
    }

    private fun anchorOf(n: AccessibilityNodeInfo?): String? {
        if (n == null) return null
        return try {
            n.viewIdResourceName
                ?: (if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null)
                ?: n.contentDescription?.toString()
                ?: n.text?.toString()
        } catch (_: Exception) { null }
    }

    private fun AccessibilityNodeInfo.recycleSafe() { try { @Suppress("DEPRECATION") recycle() } catch (_: Exception) {} }
    private fun AccessibilityNodeInfo.refreshSafe() { try { refresh() } catch (_: Exception) {} }
}

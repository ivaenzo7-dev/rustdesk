package com.carriez.flutter_hbb

/**
 * WarmerOverlay — the on-screen "mark field" bubble shown while recording (Variant A UX).
 *
 * An AccessibilityService can add a TYPE_ACCESSIBILITY_OVERLAY window without the
 * SYSTEM_ALERT_WINDOW permission. The client (driving via RustDesk) taps a text field,
 * then taps this bubble → WarmerRecorder marks the focused field. The recorder knows the
 * bubble's screen rect and does NOT record taps that land on it.
 */
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.widget.TextView

object WarmerOverlay {
    private const val TAG = "WarmerOverlay"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
    @Volatile private var wm: WindowManager? = null
    @Volatile var rect: Rect? = null          // bubble bounds on screen (recorder skips taps here)
        private set

    fun show(svc: AccessibilityService) {
        main.post {
            if (view != null) return@post
            try {
                val ctx: Context = svc
                val label = TextView(ctx).apply {
                    text = "●  Пометить поле"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    val pad = dp(ctx, 14); setPadding(pad + dp(ctx, 4), dp(ctx, 10), pad + dp(ctx, 4), dp(ctx, 10))
                    background = GradientDrawable().apply { cornerRadius = dp(ctx, 24).toFloat(); setColor(Color.parseColor("#16a34a")) }
                    elevation = dp(ctx, 6).toFloat()
                }
                val type = if (Build.VERSION.SDK_INT >= 22)
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,  // touchable (consumes the tap), just not focusable
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(ctx, 12); y = 0 }
                val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                w.addView(label, lp); wm = w; view = label
                label.post {
                    val loc = IntArray(2); label.getLocationOnScreen(loc)
                    rect = Rect(loc[0], loc[1], loc[0] + label.width, loc[1] + label.height)
                    Log.i(TAG, "bubble shown at $rect")
                }
            } catch (e: Exception) { Log.w(TAG, "show failed: ${e.message}") }
        }
    }

    fun hide() {
        main.post {
            try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
            view = null; rect = null
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}

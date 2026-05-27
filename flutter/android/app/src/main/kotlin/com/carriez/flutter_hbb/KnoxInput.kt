package com.carriez.flutter_hbb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Knox-based privileged input injection (Samsung only).
 *
 * RustDesk normally injects taps through AccessibilityService.dispatchGesture()
 * — a low-trust path. Android's anti-tapjacking / anti-fraud guard rejects those
 * synthetic gestures on secure surfaces (Google Play sign-in, runtime-permission
 * dialogs, FLAG_SECURE windows), so the renter "can't press" those screens.
 *
 * Samsung Knox's RemoteInjectionManager.injectPointerEvent() injects real
 * MotionEvents at a privileged level (the same channel ESPER/TeamViewer use),
 * which the guard treats as a genuine finger. This object wires that in via
 * REFLECTION, so the SAME APK still runs on non-Samsung devices: the Knox
 * classes are simply absent there -> ready stays false -> InputService uses the
 * dispatchGesture fallback.
 *
 * Self-healing: `ready` becomes true once the Knox classes resolve. Each
 * injectMotion() reports success as "the call did not throw"; a transient
 * failure (e.g. license not active yet -> SecurityException) makes that one
 * event fall back, and the next event retries Knox once the license lands.
 */
object KnoxInput {
    private const val TAG = "KnoxInput"

    @Volatile
    var ready = false
        private set

    private var injector: Any? = null          // RemoteInjectionManager instance
    private var injectMethod: Method? = null
    private var injectArgc = 2                  // (MotionEvent, boolean) by default
    private var receiverRegistered = false

    /** Call once early (MainApplication.onCreate). Safe to call repeatedly. */
    fun activate(context: Context) {
        if (ready) return
        val ctx = context.applicationContext

        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Log.i(TAG, "non-Samsung device (${Build.MANUFACTURER}) — Knox disabled, gesture fallback")
            return
        }

        val key = try {
            BuildConfig.KNOX_LICENSE_KEY
        } catch (e: Throwable) {
            ""
        }
        if (key.isBlank()) {
            Log.w(TAG, "KNOX_LICENSE_KEY empty — Knox disabled (set CI secret / key.properties)")
            return
        }

        try {
            // Class name differs across Knox versions: modern Knox (API 39 on
            // Android 16 / One UI) ships ...remotecontrol.RemoteInjection; older
            // Knox used ...RemoteInjectionManager. Verified on SM-S711U: the jar
            // contains com.samsung.android.knox.remotecontrol.RemoteInjection.
            var rimClass: Class<*>? = null
            for (cn in listOf(
                "com.samsung.android.knox.remotecontrol.RemoteInjection",
                "com.samsung.android.knox.remotecontrol.RemoteInjectionManager"
            )) {
                try { rimClass = Class.forName(cn); break } catch (_: ClassNotFoundException) {}
            }
            if (rimClass == null) {
                Log.w(TAG, "Knox RemoteInjection class not present — gesture fallback")
                ready = false
                return
            }
            // getInstance is getInstance(Context) on some versions, no-arg on others.
            injector = try {
                rimClass.getMethod("getInstance", Context::class.java).invoke(null, ctx)
            } catch (e: NoSuchMethodException) {
                rimClass.getMethod("getInstance").invoke(null)
            }
            injectMethod = resolveInjectMethod(rimClass)

            if (injector == null || injectMethod == null) {
                Log.e(TAG, "Knox present but injectPointerEvent not resolvable — gesture fallback")
                ready = false
                return
            }

            registerLicenseReceiver(ctx)
            activateLicense(ctx, key)

            ready = true
            Log.i(TAG, "Knox injector resolved (argc=$injectArgc), ready=true (license activation requested)")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Knox SDK not present — gesture fallback. ${e.message}")
            ready = false
        } catch (e: Throwable) {
            Log.e(TAG, "Knox activate failed: ${e.javaClass.simpleName}: ${e.message}")
            ready = false
        }
    }

    // Signature is uncertain across Knox versions: prefer (MotionEvent, boolean),
    // fall back to (MotionEvent), then scan. We can't verify against the jar, so
    // be defensive — picking the wrong arity would silently disable Knox.
    private fun resolveInjectMethod(rimClass: Class<*>): Method? {
        try {
            injectArgc = 2
            return rimClass.getMethod("injectPointerEvent", MotionEvent::class.java, Boolean::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
        }
        try {
            injectArgc = 1
            return rimClass.getMethod("injectPointerEvent", MotionEvent::class.java)
        } catch (_: NoSuchMethodException) {
        }
        val m = rimClass.methods.firstOrNull {
            it.name == "injectPointerEvent" &&
                it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == MotionEvent::class.java
        }
        if (m != null) injectArgc = m.parameterTypes.size
        return m
    }

    private fun activateLicense(ctx: Context, key: String) {
        try {
            val klmClass = Class.forName("com.samsung.android.knox.license.KnoxEnterpriseLicenseManager")
            val klm = klmClass.getMethod("getInstance", Context::class.java).invoke(null, ctx)
            klmClass.getMethod("activateLicense", String::class.java).invoke(klm, key)
            Log.i(TAG, "KPE license activation requested")
        } catch (e: Throwable) {
            Log.e(TAG, "activateLicense failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Diagnostics only: logs the license activation result to logcat. */
    private fun registerLicenseReceiver(ctx: Context) {
        if (receiverRegistered) return
        val action = try {
            Class.forName("com.samsung.android.knox.license.KnoxEnterpriseLicenseManager")
                .getField("ACTION_LICENSE_STATUS").get(null) as String
        } catch (e: Throwable) {
            "com.samsung.android.knox.intent.action.LICENSE_STATUS"
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                val err = intent.getIntExtra("com.samsung.android.knox.intent.extra.LICENSE_ERROR_CODE", -1)
                val result = intent.getIntExtra("com.samsung.android.knox.intent.extra.LICENSE_RESULT_TYPE", -1)
                Log.i(TAG, "Knox license status: errorCode=$err resultType=$result (0 = success)")
            }
        }
        try {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "license receiver register failed: ${e.message}")
        }
    }

    /**
     * Inject one MotionEvent (ACTION_DOWN/MOVE/UP) at screen-pixel coords.
     * Returns true if Knox accepted it (call did not throw); false -> caller
     * must use the gesture fallback.
     */
    fun injectMotion(action: Int, x: Float, y: Float, downTime: Long): Boolean {
        val inj = injector ?: return false
        val m = injectMethod ?: return false
        var ev: MotionEvent? = null
        return try {
            val now = SystemClock.uptimeMillis()
            ev = MotionEvent.obtain(downTime, now, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            // `mode = true` matches Knox sample usage. Return value is ignored on
            // purpose: success == "did not throw" (handles void/int/boolean returns).
            if (injectArgc == 2) m.invoke(inj, ev, true) else m.invoke(inj, ev)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "injectMotion failed (falling back): ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            ev?.recycle()
        }
    }
}

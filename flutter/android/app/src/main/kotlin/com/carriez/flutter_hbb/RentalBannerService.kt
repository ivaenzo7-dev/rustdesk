package com.carriez.flutter_hbb

/**
 * RentalBannerService — координатор жизненного цикла «шторы» «ИДЁТ АРЕНДА».
 *
 * Цель: пока админ подключён к телефону через RustDesk, сотрудник
 * (физически держащий устройство) НЕ должен видеть, что админ делает.
 *
 * Сам foreground-сервис нужен как точка входа из rust-callback / Dart.
 * Реальное окно шторы создаётся одним из двух способов:
 *   • ОСНОВНОЙ — InputService.showCurtain(): TYPE_ACCESSIBILITY_OVERLAY.
 *     Доверенный overlay, Android 12+ не зажимает его альфу до 0.8 —
 *     штора полностью непрозрачна.
 *   • FALLBACK — собственное TYPE_APPLICATION_OVERLAY окно, если
 *     accessibility-сервис не запущен. Там альфа зажимается до 0.8.
 *
 * Штора сквозная для касаний (FLAG_NOT_TOUCHABLE): инжектированные жесты
 * админа проходят сквозь неё в приложение. Из MediaProjection-стрима штора
 * скрывается через SurfaceControl#setSkipScreenshot (нужен разблокированный
 * hidden-API — на флоте hidden_api_policy=1 раскатывается через Esper).
 *
 * Жизненный цикл:
 *   • show() — при авторизации клиента (rust add_connection / Dart sendLoginResponse)
 *   • hide() — при уходе клиента (rust stop_capture / Dart onClientRemove) или стопе
 */

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast

class RentalBannerService : Service() {

    companion object {
        private const val TAG = "RentalBanner"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_SHOW = "com.carriez.flutter_hbb.BANNER_SHOW"
        const val ACTION_HIDE = "com.carriez.flutter_hbb.BANNER_HIDE"

        // -------------------------------------------------------------------
        // ВРЕМЕННО ВЫКЛЮЧЕНО (2026-05-31).
        //
        // Без hidden_api_policy=1 на устройстве штора закрывает экран не только
        // от сотрудника, но и от админа: setSkipScreenshot — hidden API, без
        // глобального разрешения он не применяется, штора попадает в стрим.
        //
        // На флот ~60 телефонов настройка ещё не раскатана. Пока не выставлена
        // на всех — оставляем фичу выключенной: show() возвращает false без
        // побочных эффектов. Все .hide() оставлены как было (идемпотентны).
        //
        // Включение — после раскатки на все устройства:
        //   adb shell settings put global hidden_api_policy_pre_p_apps 1
        //   adb shell settings put global hidden_api_policy_p_apps 1
        //   adb shell settings put global hidden_api_policy 1
        //   adb shell settings get global hidden_api_policy   # должно вернуть 1
        // → выставить ENABLED = true и пересобрать APK.
        // -------------------------------------------------------------------
        private const val ENABLED = false

        @Volatile var isShowing = false
            private set

        /**
         * Запускает штору. Возвращает false, если SYSTEM_ALERT_WINDOW
         * не выдан (звонящая сторона должна обработать — например,
         * не давать сессии начаться).
         */
        fun show(context: Context): Boolean {
            if (!ENABLED) {
                Log.w(TAG, "show() SKIPPED — RentalBanner временно отключён (ENABLED=false; см. комментарий в RentalBannerService.kt)")
                return false
            }
            Log.i(TAG, "show() entry, sdk=${Build.VERSION.SDK_INT}, isShowing=$isShowing")
            toastUi(context, "RentalBanner: show() called")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val canDraw = android.provider.Settings.canDrawOverlays(context)
                Log.i(TAG, "show() canDrawOverlays=$canDraw")
                if (!canDraw) {
                    Log.e(TAG, "show() FAILED — SYSTEM_ALERT_WINDOW NOT GRANTED")
                    Log.e(TAG, "Fix: Settings → Apps → RustDesk → Display over other apps → Allow")
                    toastUi(context, "RentalBanner ERROR: overlay-permission NOT granted")
                    return false
                }
            }
            val intent = Intent(context, RentalBannerService::class.java).apply {
                action = ACTION_SHOW
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.i(TAG, "show() calling startForegroundService()")
                    context.startForegroundService(intent)
                } else {
                    Log.i(TAG, "show() calling startService() (pre-O)")
                    context.startService(intent)
                }
                Log.i(TAG, "show() startService returned cleanly")
            } catch (e: Exception) {
                Log.e(TAG, "show() startForegroundService THREW: ${e.javaClass.simpleName}: ${e.message}", e)
                toastUi(context, "RentalBanner ERROR: startService failed: ${e.message}")
                return false
            }
            return true
        }

        fun hide(context: Context) {
            Log.i(TAG, "hide() entry, isShowing=$isShowing")
            toastUi(context, "RentalBanner: hide() called")
            try {
                context.startService(
                    Intent(context, RentalBannerService::class.java).apply {
                        action = ACTION_HIDE
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "hide() startService failed (likely OK if not running): ${e.message}")
            }
        }

        /** Toast в UI-thread — пользователь видит, что что-то происходит, без logcat. */
        private fun toastUi(context: Context, msg: String) {
            try {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) { /* ignore — toast — best-effort диагностика */ }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var shownViaAccessibility = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> { hideOverlay(); stopSelf() }
            else -> Log.w(TAG, "onStartCommand: unknown action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Overlay
    // -----------------------------------------------------------------------

    private fun showOverlay() {
        Log.i(TAG, "showOverlay() entry, shownViaAccessibility=$shownViaAccessibility overlayView=${overlayView != null}")
        // startForeground обязателен в течение 5 секунд после
        // startForegroundService() — иначе ANR (ForegroundServiceDidNotStartInTimeException).
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "showOverlay: startForeground OK")
        } catch (e: Exception) {
            Log.e(TAG, "showOverlay: startForeground FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
        }

        if (shownViaAccessibility || overlayView != null) {
            Log.d(TAG, "showOverlay: already shown, skip")
            return
        }

        // ОСНОВНОЙ ПУТЬ: штора через InputService как TYPE_ACCESSIBILITY_OVERLAY.
        // Это доверенный overlay — Android 12+ не зажимает его альфу до 0.8,
        // штора полностью непрозрачна. Доступно, пока accessibility-сервис жив.
        val inputSvc = InputService.ctx
        if (inputSvc != null) {
            Log.i(TAG, "showOverlay: delegating to InputService (TYPE_ACCESSIBILITY_OVERLAY)")
            inputSvc.showCurtain()
            shownViaAccessibility = true
            isShowing = true
            return
        }

        // FALLBACK: accessibility-сервис не запущен → TYPE_APPLICATION_OVERLAY.
        // Android 12+ зажмёт альфу до 0.8 — сотрудник увидит ~20% просвета.
        // Это деградированный режим: без accessibility полноценно не закрыть.
        Log.w(TAG, "showOverlay: InputService NOT running — fallback TYPE_APPLICATION_OVERLAY (alpha capped 0.8)")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ERROR

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = RentalCurtainView.build(this)
        try {
            windowManager?.addView(view, params)
            overlayView = view
            isShowing = true
            Log.i(TAG, "✅ fallback overlay ADDED (TYPE_APPLICATION_OVERLAY)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ showOverlay addView FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            return
        }

        RentalCurtainView.applySkipScreenshot(view)
    }

    private fun hideOverlay() {
        Log.i(TAG, "hideOverlay() entry, shownViaAccessibility=$shownViaAccessibility overlayView=${overlayView != null}")
        // Убираем обе возможные шторы — вызовы идемпотентны.
        if (shownViaAccessibility) {
            InputService.ctx?.hideCurtain()
            shownViaAccessibility = false
        }
        overlayView?.let {
            try {
                windowManager?.removeView(it)
                Log.i(TAG, "hideOverlay: fallback overlay removeView OK")
            } catch (e: Exception) { Log.e(TAG, "hideOverlay removeView FAILED: ${e.message}") }
        }
        overlayView = null
        isShowing = false
        Log.i(TAG, "Privacy overlay hidden")
    }

    // -----------------------------------------------------------------------
    // Notification (требование foreground service)
    // -----------------------------------------------------------------------

    private fun buildNotification(): android.app.Notification {
        val channelId = "rental_banner"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                channelId, "Rental Privacy Screen",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
        return android.app.Notification.Builder(this, channelId)
            .setContentTitle("Идёт аренда")
            .setContentText("Удалённая сессия активна")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

}

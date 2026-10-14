package com.carriez.flutter_hbb

/**
 * Handle events from flutter
 * Request MediaProjection permission
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import ffi.FFI

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.os.PowerManager
import android.text.TextUtils
import android.util.Log
import android.view.WindowManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import com.hjq.permissions.XXPermissions
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread


class MainActivity : FlutterActivity() {
    companion object {
        var flutterMethodChannel: MethodChannel? = null
        private var _rdClipboardManager: RdClipboardManager? = null
        val rdClipboardManager: RdClipboardManager?
            get() = _rdClipboardManager
    }

    private val channelTag = "mChannel"
    private val logTag = "mMainActivity"
    private var mainService: MainService? = null

    private var isAudioStart = false
    private val audioRecordHandle = AudioRecordHandle(this, { false }, { isAudioStart })

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        if (MainService.isReady) {
            Intent(activity, MainService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
        flutterMethodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelTag
        )
        initFlutterChannel(flutterMethodChannel!!)
        // Инициализируем CaptureController с MethodChannel
        CaptureController.init(this, flutterEngine.dartExecutor.binaryMessenger)
        // Инициализируем конфиг XML рендера
        XmlRenderConfigManager.init(this, flutterEngine.dartExecutor.binaryMessenger)
        thread {
            try { setCodecInfo() } catch (e: Exception) {
                Log.e("MainActivity", "Failed to setCodecInfo: ${e.message}", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Берём реальное состояние: isOpen — сервис живёт прямо сейчас,
        // wasInputEnabled — пользователь его включал (сохранено в SharedPreferences).
        // Если сервис был убит Doze но пользователь его включал — показываем true
        // (он восстановится сам как только accessibility service перезапустится).
        val inputActive = InputService.isOpen ||
                InputService.wasInputEnabled(applicationContext)
        activity.runOnUiThread {
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to inputActive.toString())
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (_rdClipboardManager == null) {
            _rdClipboardManager = RdClipboardManager(
                getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            )
            FFI.setClipboardManager(_rdClipboardManager!!)
        }
        // Запускаем KeepAlive сервис — держит процесс живым для InputService
        KeepAliveService.start(this)
        // Запускаем Watchdog — авторестарт InputService при падении
        AccessibilityWatchdog.start(this)
        // Запрашиваем разрешение на overlay (нужно для занавески)
        requestOverlayPermissionIfNeeded()
        // Запрашиваем Accessibility при первом запуске
        requestAccessibilityIfNeeded()
        // Запрашиваем исключение из battery optimization
        requestBatteryOptimizationExemption()
        // Start MainService early so WebSocket authentication begins before screen sharing
        startService(Intent(this, MainService::class.java))
    }

    /**
     * Проверяем включён ли наш InputService в Accessibility.
     * Если нет — показываем диалог с объяснением и открываем настройки.
     * Проверка делается только один раз (SharedPreferences flag).
     */
    private fun requestAccessibilityIfNeeded() {
        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean("accessibility_requested", false)
        if (alreadyAsked && isAccessibilityEnabled()) return

        if (!isAccessibilityEnabled()) {
            prefs.edit().putBoolean("accessibility_requested", true).apply()
            // Небольшая задержка чтобы Activity успела создаться
            android.os.Handler(mainLooper).postDelayed({
                showAccessibilityDialog()
            }, 800)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expectedService = "$packageName/${InputService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any {
            it.equals(expectedService, ignoreCase = true)
        }
    }

    private fun showAccessibilityDialog() {
        if (isFinishing || isDestroyed) return
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Accessibility Permission Required")
        builder.setMessage(
            "RustDesk needs Accessibility Service to:\n\n" +
            "- Control input remotely (gestures, keyboard)\n" +
            "- XML-based screen capture (no MediaProjection needed)\n\n" +
            "Please enable RustDesk in the next screen."
        )
        builder.setPositiveButton("Open Settings") { dialog, which ->
            // Deep link прямо на наш сервис — не нужно искать в списке
            startActivity(InputService.buildAccessibilityDeepLink(this))
        }
        builder.setNegativeButton("Later") { dialog, which -> }
        builder.setCancelable(true)
        builder.show()
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (Settings.canDrawOverlays(this)) return

        // На MIUI показываем диалог и открываем настройки
        android.os.Handler(mainLooper).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("Permission Required")
            builder.setMessage(
                "RustDesk needs permission to display over other apps.\n\n" +
                "Please enable 'Display pop-up windows' in the next screen."
            )
            builder.setPositiveButton("Open Settings") { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback для MIUI где стандартный intent может не работать
                    try {
                        val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                            putExtra("extra_pkgname", packageName)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
            builder.setNegativeButton("Later", null)
            builder.show()
        }, 1_500L) // После диалога Accessibility
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean("battery_opt_requested", false)
        if (alreadyAsked) return
        prefs.edit().putBoolean("battery_opt_requested", true).apply()

        // Показываем диалог с объяснением
        android.os.Handler(mainLooper).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("Keep Service Running")
            builder.setMessage(
                "To prevent Android from stopping Input Control and XML Capture " +
                "after long periods of inactivity, please disable battery optimization for RustDesk.\n\n" +
                "Settings - Apps - RustDesk - Battery - Unrestricted"
            )
            builder.setPositiveButton("Open Settings") { _, _ ->
                try {
                    // Открываем страницу battery settings конкретно для нашего приложения
                    startActivity(Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                } catch (e: Exception) {
                    // Fallback на общие настройки батареи
                    startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
            }
            builder.setNegativeButton("Later", null)
            builder.show()
        }, 2_000L) // Задержка 2с чтобы не показывать одновременно с Accessibility диалогом
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
        }
        startActivityForResult(intent, REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION
            && resultCode == RES_FAILED
        ) {
            flutterMethodChannel?.invokeMethod("on_media_projection_canceled", null)
        }
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        mainService?.let { unbindService(serviceConnection) }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "onServiceConnected")
            mainService = (service as MainService.LocalBinder).getService()
            CaptureController.mainService = mainService
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "onServiceDisconnected")
            mainService = null
            CaptureController.mainService = null
        }
    }

    private fun initFlutterChannel(flutterMethodChannel: MethodChannel) {
        flutterMethodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "init_service" -> {
                    Intent(activity, MainService::class.java).also {
                        bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                    }
                    if (MainService.isReady) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    requestMediaProjection()
                    result.success(true)
                }
                // Новый метод: запуск через XML capture (без MediaProjection)
                "init_service_xml" -> {
                    Intent(activity, MainService::class.java).also {
                        bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                    }
                    if (!isAccessibilityEnabled()) {
                        showAccessibilityDialog()
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    // Стартуем MainService без MediaProjection
                    // (он нужен для audio, file transfer и т.д.)
                    val serviceIntent = Intent(activity, MainService::class.java)
                    startService(serviceIntent)
                    // Запускаем XmlCapture через CaptureController
                    CaptureController.startXmlIfNeeded(this)
                    result.success(true)
                }
                "start_capture" -> {
                    mainService?.let { result.success(it.startCapture()) }
                        ?: result.success(false)
                }
                "stop_service" -> {
                    CaptureController.stopXml()
                    mainService?.let { it.destroy(); result.success(true) }
                        ?: result.success(false)
                }
                "check_permission" -> {
                    if (call.arguments is String) {
                        result.success(XXPermissions.isGranted(context, call.arguments as String))
                    } else result.success(false)
                }
                "request_permission" -> {
                    if (call.arguments is String) {
                        requestPermission(context, call.arguments as String)
                        result.success(true)
                    } else result.success(false)
                }
                START_ACTION -> {
                    if (call.arguments is String) {
                        startAction(context, call.arguments as String)
                        result.success(true)
                    } else result.success(false)
                }
                "check_video_permission" -> {
                    mainService?.let { result.success(it.checkMediaPermission()) }
                        ?: result.success(false)
                }
                "check_service" -> {
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "media", "value" to MainService.isReady.toString())
                    )
                    // Уведомляем flutter о текущем методе захвата
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "capture_method", "value" to CaptureController.activeMethod)
                    )
                    result.success(true)
                }
                "warmer_set_rustdesk_id" -> {
                    // Persist the RustDesk peer ID for WarmerService to register with
                    // the OpenClaw bridge as a uniquely-addressable device.
                    val id = call.arguments as? String
                    if (!id.isNullOrEmpty()) {
                        context.getSharedPreferences(WarmerService.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(WarmerService.PREFS_KEY_ID, id).apply()
                    }
                    result.success(true)
                    return@setMethodCallHandler
                }
                "check_accessibility" -> {
                    result.success(isAccessibilityEnabled())
                }
                "enable_soft_keyboard" -> {
                    if (call.arguments as Boolean) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    }
                    result.success(true)
                }
                "try_sync_clipboard" -> {
                    rdClipboardManager?.syncClipboard(true)
                    result.success(true)
                }
                GET_START_ON_BOOT_OPT -> {
                    val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                    result.success(prefs.getBoolean(KEY_START_ON_BOOT_OPT, false))
                }
                SET_START_ON_BOOT_OPT -> {
                    if (call.arguments is Boolean) {
                        getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE).edit()
                            .putBoolean(KEY_START_ON_BOOT_OPT, call.arguments as Boolean).apply()
                        result.success(true)
                    } else result.success(false)
                }
                SYNC_APP_DIR_CONFIG_PATH -> {
                    if (call.arguments is String) {
                        getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE).edit()
                            .putString(KEY_APP_DIR_CONFIG_PATH, call.arguments as String).apply()
                        result.success(true)
                    } else result.success(false)
                }
                GET_VALUE -> {
                    if (call.arguments is String) {
                        if (call.arguments == KEY_IS_SUPPORT_VOICE_CALL) {
                            result.success(isSupportVoiceCall())
                        } else result.error("-1", "No such key", null)
                    } else result.success(null)
                }
                "on_voice_call_started" -> onVoiceCallStarted()
                "on_voice_call_closed" -> onVoiceCallClosed()
                "get_device_unique_id" -> {
                    result.success(MainService.instance?.getDeviceUniqueId() ?: "")
                }
                "get_current_otp" -> {
                    result.success("")
                }
                "show_privacy_screen" -> {
                    Log.i("RentalBanner", "[MainActivity] show_privacy_screen channel hit")
                    // true → штора запущена; false → SYSTEM_ALERT_WINDOW не выдан
                    // или startForegroundService упал. Сторона Dart может показать UI-предупреждение.
                    val ok = RentalBannerService.show(this)
                    Log.i("RentalBanner", "[MainActivity] show_privacy_screen returning ok=$ok")
                    result.success(ok)
                }
                "hide_privacy_screen" -> {
                    Log.i("RentalBanner", "[MainActivity] hide_privacy_screen channel hit")
                    RentalBannerService.hide(this)
                    result.success(null)
                }
                "privacy_screen_status" -> {
                    result.success(RentalBannerService.isShowing)
                }
                // Новый интерфейс DroidDesk: данные для чек-листа на вкладке Device.
                "dd_health" -> {
                    result.success(ddHealth())
                }
                // Открыть нужный раздел системных настроек (кнопки Grant в Service settings).
                "dd_open_settings" -> {
                    result.success(ddOpenSettings(call.arguments as? String ?: "app"))
                }
                else -> result.error("-1", "No such method", null)
            }
        }
    }

    private fun ddHealth(): HashMap<String, Any> {
        val m = HashMap<String, Any>()
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            m["batteryLevel"] = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val st = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            m["charging"] = (st?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        } catch (e: Exception) {
            Log.w(logTag, "ddHealth battery: ${e.message}")
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        m["ignoringBatteryOptimizations"] =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(packageName) else true
        m["canDrawOverlays"] =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        m["accessibility"] = isAccessibilityEnabled()
        var net = "none"
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (caps != null) {
                    // Wi-Fi и сотовую проверяем первыми: через VPN (tun0 у части парка)
                    // активная сеть несёт и TRANSPORT_VPN, и реальный транспорт под ним.
                    net = when {
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "other"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(logTag, "ddHealth network: ${e.message}")
        }
        m["network"] = net
        m["manufacturer"] = Build.MANUFACTURER ?: ""
        m["model"] = Build.MODEL ?: ""
        m["release"] = Build.VERSION.RELEASE ?: ""
        // Имя устройства из настроек (у Samsung — маркетинговое, «Galaxy S21 5G»).
        m["deviceName"] = try { Settings.Global.getString(contentResolver, "device_name") ?: "" } catch (_: Exception) { "" }
        return m
    }

    private fun ddOpenSettings(target: String): Boolean {
        val pkg = android.net.Uri.parse("package:$packageName")
        val intents = when (target) {
            "accessibility" -> listOf(InputService.buildAccessibilityDeepLink(this),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "battery" -> listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            "overlay" -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            else -> listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return true
            } catch (e: Exception) {
                Log.w(logTag, "ddOpenSettings($target): ${e.message}")
            }
        }
        return false
    }

    private fun setCodecInfo() {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos
        val codecArray = JSONArray()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wh = getScreenSize(windowManager)
        var w = wh.first; var h = wh.second
        val align = 64
        w = (w + align - 1) / align * align
        h = (h + align - 1) / align * align
        codecs.forEach { codec ->
            val codecObject = JSONObject()
            codecObject.put("name", codec.name)
            codecObject.put("is_encoder", codec.isEncoder)
            var hw: Boolean? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hw = codec.isHardwareAccelerated
            } else {
                if (listOf("OMX.google.", "OMX.SEC.", "c2.android").any { codec.name.startsWith(it, true) }) hw = false
                else if (listOf("c2.qti", "OMX.qcom.video", "OMX.Exynos", "OMX.hisi", "OMX.MTK", "OMX.Intel", "OMX.Nvidia").any { codec.name.startsWith(it, true) }) hw = true
            }
            if (hw != true) return@forEach
            codecObject.put("hw", hw)
            var mime_type = ""
            codec.supportedTypes.forEach { type ->
                if (listOf("video/avc", "video/hevc").contains(type)) mime_type = type
            }
            if (mime_type.isNotEmpty()) {
                codecObject.put("mime_type", mime_type)
                val caps = codec.getCapabilitiesForType(mime_type)
                if (codec.isEncoder && !caps.videoCapabilities.isSizeSupported(w, h) && !caps.videoCapabilities.isSizeSupported(h, w)) return@forEach
                codecObject.put("min_width", caps.videoCapabilities.supportedWidths.lower)
                codecObject.put("max_width", caps.videoCapabilities.supportedWidths.upper)
                codecObject.put("min_height", caps.videoCapabilities.supportedHeights.lower)
                codecObject.put("max_height", caps.videoCapabilities.supportedHeights.upper)
                val surface = caps.colorFormats.contains(COLOR_FormatSurface)
                codecObject.put("surface", surface)
                val nv12 = caps.colorFormats.contains(COLOR_FormatYUV420SemiPlanar)
                codecObject.put("nv12", nv12)
                if (!(nv12 || surface)) return@forEach
                codecObject.put("min_bitrate", caps.videoCapabilities.bitrateRange.lower / 1000)
                codecObject.put("max_bitrate", caps.videoCapabilities.bitrateRange.upper / 1000)
                if (!codec.isEncoder) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        codecObject.put("low_latency", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                    }
                    return@forEach
                }
                codecArray.put(codecObject)
            }
        }
        FFI.setCodecInfo(JSONObject().apply {
            put("version", Build.VERSION.SDK_INT)
            put("w", w); put("h", h); put("codecs", codecArray)
        }.toString())
    }

    private fun onVoiceCallStarted() {
        var ok = false
        mainService?.let { ok = it.onVoiceCallStarted() } ?: let {
            isAudioStart = true
            ok = audioRecordHandle.onVoiceCallStarted(null)
        }
        if (!ok) {
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call", "text" to "Failed to start voice call."))
        }
    }

    private fun onVoiceCallClosed() {
        var ok = false
        mainService?.let { ok = it.onVoiceCallClosed() } ?: let {
            isAudioStart = false
            ok = audioRecordHandle.onVoiceCallClosed(null)
        }
        if (!ok) {
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call", "text" to "Failed to stop voice call."))
        }
    }

    override fun onStop() {
        super.onStop()
        if (FFI.getLocalOption("disable-floating-window") != "Y" && MainService.isReady) {
            startService(Intent(this, FloatingWindowService::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, FloatingWindowService::class.java))
    }
}

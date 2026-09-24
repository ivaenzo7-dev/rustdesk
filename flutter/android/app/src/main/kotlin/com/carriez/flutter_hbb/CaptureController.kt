package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * CaptureController — выбор и переключение метода захвата экрана.
 *
 * Интеграция в MainActivity.configureFlutterEngine:
 *   CaptureController.init(this, flutterEngine.dartExecutor.binaryMessenger)
 *
 * Метод сохраняется в SharedPreferences и переживает перезапуск.
 */
object CaptureController {

    private const val TAG = "CaptureController"
    const val CHANNEL = "com.carriez.flutter_hbb/capture"
    private const val PREFS_NAME = "capture_prefs"
    private const val KEY_METHOD = "capture_method"
    const val METHOD_MP  = "mp"
    const val METHOD_XML = "xml"
    // Настоящие пиксели через AccessibilityService.takeScreenshot(), без
    // MediaProjection. ~3 кадра в секунду — платформа троттлит запросы.
    const val METHOD_SHOT = "shot"

    var activeMethod: String = METHOD_MP
        private set

    // Ссылка на MainService для управления VirtualDisplay
    var mainService: MainService? = null

    fun init(context: Context, messenger: BinaryMessenger) {
        activeMethod = prefs(context).getString(KEY_METHOD, METHOD_MP) ?: METHOD_MP

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "setXmlCapture" -> {
                    setMethod(context, METHOD_XML)
                    result.success(null)
                }
                "setScreenshotCapture" -> {
                    if (!ScreenshotCapture.isSupported()) {
                        result.error("unsupported", "takeScreenshot требует Android 11+", null)
                    } else {
                        setMethod(context, METHOD_SHOT)
                        result.success(null)
                    }
                }
                "setMediaProjection" -> {
                    stopNonMp()
                    setMethod(context, METHOD_MP)
                    result.success(null)
                }
                "getCaptureMethod" -> result.success(activeMethod)
                "startCapture" -> {
                    startXmlIfNeeded(context)
                    result.success(null)
                }
                "stopCapture" -> {
                    stopNonMp()
                    result.success(null)
                }
                "switchMethod" -> {
                    val method = call.arguments as? String ?: METHOD_MP
                    switchMethodDuringCapture(context, method)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun setMethod(context: Context, method: String) {
        activeMethod = method
        prefs(context).edit().putString(KEY_METHOD, method).apply()
        Log.i(TAG, "capture method → $method")
    }

    /**
     * Имя историческое — метод поднимает любой не-MP захват (XML или скриншоты).
     * Вызывается из MainActivity, поэтому сигнатуру не меняем.
     */
    fun startXmlIfNeeded(context: Context) {
        if (activeMethod == METHOD_MP) return
        val service = InputService.ctx
        if (service == null) {
            Log.w(TAG, "InputService не запущен — открываем настройки")
            context.startActivity(InputService.buildAccessibilityDeepLink(context))
            return
        }
        when (activeMethod) {
            METHOD_XML  -> XmlCapture.start(service)
            METHOD_SHOT -> ScreenshotCapture.start(service)
        }
    }

    fun stopXml() = stopNonMp()

    /** Оба не-MP метода пишут в один и тот же видео-поток — гасим сразу оба. */
    private fun stopNonMp() {
        if (XmlCapture.isActive()) XmlCapture.stop()
        if (ScreenshotCapture.isActive()) ScreenshotCapture.stop()
    }

    /**
     * Переключение метода во время активного захвата.
     *
     * MP → XML:
     *   1. stopCapture() — убирает VirtualDisplay и красный значок в статус-баре
     *   2. XmlCapture.start() — стартует XML захват с FFI.setFrameRawEnable("video", true)
     *
     * XML → MP:
     *   1. XmlCapture.stop() — останавливает XML захват с FFI.setFrameRawEnable("video", false)
     *   2. startCapture() — поднимает VirtualDisplay снова
     */
    private fun switchMethodDuringCapture(context: Context, method: String) {
        if (activeMethod == method) return

        when (method) {
            METHOD_XML, METHOD_SHOT -> {
                mainService?.stopCapture()
                stopNonMp()
                setMethod(context, method)
                // Небольшая задержка чтобы предыдущий источник освободил буферы
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startXmlIfNeeded(context)
                }, 150)
            }
            METHOD_MP -> {
                // stop() ждёт завершения своего потока внутри себя
                stopNonMp()
                setMethod(context, METHOD_MP)
                // Запускаем MP только после полной остановки
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    mainService?.startCapture()
                }, 150)
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

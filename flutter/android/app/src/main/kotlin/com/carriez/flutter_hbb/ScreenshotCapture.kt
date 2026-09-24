package com.carriez.flutter_hbb

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import ffi.FFI
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenshotCapture — третий метод захвата: настоящие пиксели экрана
 * через AccessibilityService.takeScreenshot(), без MediaProjection.
 *
 * Зачем: XML-режим рисует дерево AccessibilityNodeInfo, поэтому картинок,
 * WebView-содержимого и экранной клавиатуры в нём нет в принципе — их нет
 * в дереве. Здесь приходит обычный экран, как он есть.
 *
 * Цена: платформа троттлит запросы (порядка 3 кадров в секунду), так что
 * это режим «посмотреть и нажать», а не полноценное управление.
 *
 * Требует API 30+ и canTakeScreenshot="true" в accessibility_service_config.xml
 * (уже объявлено). Формат кадра тот же, что у MP и XML пайплайнов: RGBA_8888
 * размером SCREEN_INFO.
 */
object ScreenshotCapture {

    private const val TAG = "ScreenshotCapture"

    /**
     * Платформа не отдаёт кадры чаще, чем раз в
     * ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS (333 мс по умолчанию),
     * иначе возвращает ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT. Просим с запасом,
     * чтобы не тратить запросы впустую.
     */
    private const val FRAME_INTERVAL_MS = 400L

    private val isRunning = AtomicBoolean(false)

    /** Предыдущий кадр ещё не вернулся — новый не запрашиваем. */
    private val inFlight = AtomicBoolean(false)

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var byteBuffer: ByteBuffer? = null
    private var lastWidth = 0
    private var lastHeight = 0

    private val callbackExecutor = Executor { r -> captureHandler?.post(r) ?: r.run() }

    fun isActive(): Boolean = isRunning.get()

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun start(service: InputService) {
        if (!isSupported()) {
            Log.w(TAG, "takeScreenshot требует Android 11+, текущий SDK ${Build.VERSION.SDK_INT}")
            return
        }
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "already running")
            return
        }
        captureThread = HandlerThread("ScreenshotCaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        inFlight.set(false)

        // Без этого Rust не откроет видео-поток и клиент повиснет на ожидании изображения.
        FFI.setFrameRawEnable("video", true)
        FFI.refreshScreen()
        scheduleNextFrame(service)
        Log.i(TAG, "started, интервал ${FRAME_INTERVAL_MS}ms")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        captureHandler?.removeCallbacksAndMessages(null)
        val thread = captureThread
        captureThread = null
        captureHandler = null

        // Дожидаемся потока, чтобы последний кадр дописался ДО setFrameRawEnable(false):
        // иначе Rust закроет буфер, пока мы ещё в него пишем.
        val javaThread = thread?.looper?.thread
        thread?.quitSafely()
        try { javaThread?.join(300) } catch (_: InterruptedException) {}

        byteBuffer = null
        lastWidth = 0
        lastHeight = 0
        inFlight.set(false)

        FFI.setFrameRawEnable("video", false)
        Log.i(TAG, "stopped")
    }

    private fun scheduleNextFrame(service: InputService) {
        if (!isRunning.get()) return
        captureHandler?.postDelayed({
            grabFrame(service)
            scheduleNextFrame(service)
        }, FRAME_INTERVAL_MS)
    }

    private fun grabFrame(service: InputService) {
        if (!isRunning.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // Запрос ещё в полёте — пропускаем такт, а не копим очередь.
        if (!inFlight.compareAndSet(false, true)) return

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                callbackExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            deliver(result)
                        } catch (e: Throwable) {
                            Log.e(TAG, "deliver error", e)
                        } finally {
                            inFlight.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        // Чаще всего errorCode 3 — запросы чаще разрешённого интервала.
                        Log.w(TAG, "takeScreenshot failed, code=$errorCode")
                        inFlight.set(false)
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "takeScreenshot threw", e)
            inFlight.set(false)
        }
    }

    private fun deliver(result: AccessibilityService.ScreenshotResult) {
        if (!isRunning.get()) return
        val w = SCREEN_INFO.width
        val h = SCREEN_INFO.height
        if (w <= 0 || h <= 0) return

        var hardware: Bitmap? = null
        var software: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val hb = result.hardwareBuffer
            try {
                hardware = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
            } finally {
                // HardwareBuffer держит память графики — закрываем всегда.
                hb.close()
            }
            val hw = hardware ?: return

            // HARDWARE-битмап нельзя читать напрямую — нужна софтовая копия.
            software = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return

            val src = if (software.width != w || software.height != h) {
                scaled = Bitmap.createScaledBitmap(software, w, h, true)
                scaled!!
            } else software

            if (byteBuffer == null || lastWidth != w || lastHeight != h) {
                byteBuffer = ByteBuffer.allocateDirect(w * h * 4)
                lastWidth = w
                lastHeight = h
                Log.d(TAG, "buffer reallocated: ${w}x${h}")
            }
            val buf = byteBuffer ?: return

            buf.rewind()
            src.copyPixelsToBuffer(buf)
            buf.rewind()

            if (!isRunning.get()) return
            FFI.onVideoFrameUpdate(buf)
        } finally {
            scaled?.recycle()
            software?.recycle()
            hardware?.recycle()
        }
    }
}

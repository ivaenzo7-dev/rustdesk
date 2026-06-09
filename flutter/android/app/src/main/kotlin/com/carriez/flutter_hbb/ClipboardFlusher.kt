package com.carriez.flutter_hbb

/**
 * ClipboardFlusher — вытесняет историю буфера обмена телефона.
 *
 * Зачем: Samsung Keyboard (com.samsung.android.honeyboard) хранит recent-list
 * клипов внутри своего приватного `/data/data/`-каталога. Обычное приложение
 * без root / Knox / signature permission туда не достанет, а Esper-shell
 * упирается в CLEAR_APP_USER_DATA. История переживает `pm clear` агента и
 * становится видна следующему арендатору при открытии клавиатуры.
 *
 * Идея: Samsung Keyboard ведёт recent-list ограниченного размера (~20–30
 * элементов на One UI 5–7), слушает события системного клипборда через
 * собственный ClipboardSaveService и добавляет каждый новый клип в очередь.
 * Лупим N уникальных невидимых клипов с паузой между ними — старые
 * (потенциально приватные) записи вытесняются за хвост и исчезают.
 *
 * Уникальность достигается варьируемым количеством пробелов: Samsung
 * дедуплицирует подряд идущие одинаковые клипы, поэтому каждый клип
 * должен отличаться от соседа.
 *
 * Финальным шагом вызывается ClipboardManager.clearPrimaryClip() — закрывает
 * дыру с системным primary clip в /data/clipboard/, который как раз и
 * переживает `pm clear` пакета клавиатуры.
 *
 * Триггер: MainService — после rust-callback'а stop_capture и в onDestroy.
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object ClipboardFlusher {

    private const val TAG = "ClipboardFlusher"

    // 40 заходов с запасом покрывают recent-list любой One UI 5–7
    // (~20–30 элементов). Меньше — могут остаться хвостовые записи.
    private const val DEFAULT_COUNT = 40

    // Пауза между заливками. Без неё Samsung-сервис не успевает
    // обработать каждое событие и часть теряется.
    private const val STEP_DELAY_MS = 60L

    private val isRunning = AtomicBoolean(false)

    /**
     * Асинхронно вытесняет историю буфера обмена. Если уже идёт —
     * повторный вызов игнорируется (предыдущий доработает).
     */
    fun flushAsync(context: Context, count: Int = DEFAULT_COUNT) {
        if (!isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "flushAsync: уже идёт, skip")
            return
        }
        val appContext = context.applicationContext
        val safeCount = count.coerceIn(10, 200)

        val thread = HandlerThread("ClipboardFlusher").also { it.start() }
        Handler(thread.looper).post {
            try {
                flush(appContext, safeCount)
            } catch (e: Exception) {
                Log.e(TAG, "flush failed: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                isRunning.set(false)
                thread.quitSafely()
            }
        }
    }

    private fun flush(context: Context, count: Int) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            Log.w(TAG, "ClipboardManager == null — abort")
            return
        }

        Log.i(TAG, "flush start: count=$count")
        var written = 0
        for (i in 0 until count) {
            try {
                // Варьируем количество пробелов (1..5), чтобы Samsung не
                // дедуплицировал соседние клипы. Визуально все они "пустые".
                val pad = " ".repeat(i % 5 + 1)
                cm.setPrimaryClip(ClipData.newPlainText("", pad))
                written++
            } catch (e: Exception) {
                // Single-step failures не должны прерывать поток
                Log.w(TAG, "setPrimaryClip step=$i failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            try {
                Thread.sleep(STEP_DELAY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }

        // Финальный шаг — стираем системный primary clip целиком.
        // Это покрывает /data/clipboard/, до которого pm clear не дотягивается.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                cm.clearPrimaryClip()
            } catch (e: Exception) {
                Log.w(TAG, "clearPrimaryClip failed: ${e.message}")
            }
        }
        Log.i(TAG, "flush done: written=$written")
    }
}

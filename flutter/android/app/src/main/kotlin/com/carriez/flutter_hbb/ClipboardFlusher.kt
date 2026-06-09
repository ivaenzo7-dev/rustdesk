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
 * Идея: Samsung Keyboard слушает события системного клипборда и кладёт
 * каждый клип в свой recent-list. Лупим N клипов с уникальным невидимым
 * содержимым — старые (потенциально приватные) записи вытесняются за хвост.
 *
 * v2 (2026-06-07) — фикс дедупликации:
 *   v1 использовал пробелы разной длины (" ", "  ", "   "...). Samsung
 *   нормализовал whitespace и схлопывал 40 клипов в 2-3 записи (подтверждено
 *   logcat'ом на A26 Android 16: flush done written=40, в history панели
 *   видно ~3 записи). Решение — использовать невидимые Unicode-символы
 *   ZWSP (U+200B), ZWNJ (U+200C), ZWJ (U+200D), BOM (U+FEFF) в уникальной
 *   комбинации на каждой итерации. Samsung не может нормализовать их без
 *   нарушения настоящего содержимого, поэтому каждый клип уникален и
 *   попадает в history отдельной записью.
 *
 * Финальным шагом вызывается ClipboardManager.clearPrimaryClip() — закрывает
 * дыру с системным primary clip в /data/clipboard/, который как раз и
 * переживает `pm clear` пакета клавиатуры.
 *
 * Триггер: MainService — после rust-callback'а stop_capture и в onDestroy.
 *
 * Ограничение: text-флуш НЕ вытесняет image-клипы Samsung Keyboard (там
 * отдельная очередь). Картинки придётся вытеснять image-флушем — отдельный
 * шаг с FileProvider, отложен на v3.
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

    // 60 заходов с запасом — на A26 Android 16 / One UI 8 recent-list
    // может быть длиннее 30. v1 с 40 не дошёл до настоящих вытеснений
    // из-за дедупликации; теперь каждый клип уникален → каждый записывается.
    private const val DEFAULT_COUNT = 60

    // Пауза между заливками — иначе Samsung-сервис теряет события.
    private const val STEP_DELAY_MS = 60L

    // 4 невидимых Unicode-символа: ZWSP, ZWNJ, ZWJ, BOM.
    // Любые их комбинации визуально пусты, но уникальны для дедупликатора.
    // Записаны через \u-эскейпы — невидимые литералы в исходниках коварны.
    private val ZW_CHARS = charArrayOf('\u200B', '\u200C', '\u200D', '\uFEFF')

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

    /**
     * Кодирует index в строку из ZW-символов base-4.
     * index 0 → ZWSP, index 1 → ZWNJ, index 4 → ZWSP+ZWNJ, и т.д.
     * Каждый index даёт уникальную (невидимую) последовательность.
     */
    private fun encodeUniqueZW(index: Int): String {
        if (index == 0) return ZW_CHARS[0].toString()
        val sb = StringBuilder()
        var n = index
        while (n > 0) {
            sb.append(ZW_CHARS[n and 0x3])
            n = n shr 2
        }
        return sb.toString()
    }

    private fun flush(context: Context, count: Int) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            Log.w(TAG, "ClipboardManager == null — abort")
            return
        }

        Log.i(TAG, "flush start: count=$count (ZW-unicode unique)")
        var written = 0
        val startedAt = System.currentTimeMillis()

        for (i in 0 until count) {
            try {
                // Уникальная ZW-последовательность + один видимый пробел.
                // Пробел нужен, чтобы Samsung не отбраковал клип как "пустой".
                val text = " " + encodeUniqueZW(i)
                // Label тоже уникальный — на случай если дедупликатор смотрит на него.
                val label = "fl_${startedAt}_$i"
                cm.setPrimaryClip(ClipData.newPlainText(label, text))
                written++
            } catch (e: Exception) {
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
        Log.i(TAG, "flush done: written=$written (${System.currentTimeMillis() - startedAt}ms)")
    }
}

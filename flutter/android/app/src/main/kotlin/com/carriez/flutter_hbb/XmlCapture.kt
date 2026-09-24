package com.carriez.flutter_hbb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import ffi.FFI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * XmlCapture — альтернативный метод захвата экрана через AccessibilityService.
 *
 * Формат вывода идентичен MediaProjection pipeline:
 *   Bitmap(ARGB_8888) → copyPixelsToBuffer → ByteBuffer(RGBA) → FFI.onVideoFrameUpdate(buffer)
 *
 * Rust-сторона получает те же данные что и от ImageReader в createSurface().
 */
object XmlCapture {

    private const val TAG = "XmlCapture"
    private const val TARGET_FPS = 15
    @Volatile private var frameIntervalMs = 1000L / TARGET_FPS

    /** Применить новый конфиг на лету — вызывается из XmlRenderConfigManager */
    fun applyConfig(config: XmlRenderConfig) {
        frameIntervalMs = 1000L / config.frameRate.toLong()
        android.util.Log.d(TAG, "config applied: fps=${config.frameRate} scheme=${config.colorScheme}")
    }

    private val isRunning = AtomicBoolean(false)
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // Переиспользуемые объекты — не аллоцируем каждый кадр
    private var bitmap: Bitmap? = null
    private var byteBuffer: ByteBuffer? = null
    private var lastWidth = 0
    private var lastHeight = 0

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun start(service: InputService) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "already running")
            return
        }
        captureThread = HandlerThread("XmlCaptureThread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        // Сообщаем Rust что видео-поток начинается — без этого клиент висит на "waiting for image"
        FFI.setFrameRawEnable("video", true)
        // Инициализируем размеры экрана на Rust-стороне (аналог refreshScreen в startCapture)
        FFI.refreshScreen()
        scheduleNextFrame(service)
        Log.i(TAG, "started @ ${TARGET_FPS}fps")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        // Сначала останавливаем поток — это гарантирует что captureFrame не запустится снова.
        // quitSafely() дожидается завершения текущей задачи в очереди.
        captureHandler?.removeCallbacksAndMessages(null)
        val thread = captureThread
        captureThread = null
        captureHandler = null

        // Ждём завершения потока — максимум 200мс.
        // Это гарантирует что последний captureFrame завершён ДО setFrameRawEnable(false).
        val javaThread = thread?.looper?.thread
        thread?.quitSafely()
        try { javaThread?.join(200) } catch (_: InterruptedException) {}

        bitmap?.recycle()
        bitmap = null
        byteBuffer = null
        lastWidth = 0
        lastHeight = 0

        // Только после полной остановки потока сообщаем Rust.
        // Если вызвать раньше — Rust закроет буфер пока мы ещё пишем в него.
        FFI.setFrameRawEnable("video", false)
        Log.i(TAG, "stopped")
    }

    fun isActive(): Boolean = isRunning.get()

    // -----------------------------------------------------------------------
    // Capture loop
    // -----------------------------------------------------------------------

    private fun scheduleNextFrame(service: InputService) {
        if (!isRunning.get()) return
        captureHandler?.postDelayed({
            captureFrame(service)
            scheduleNextFrame(service)
        }, frameIntervalMs)
    }

    private fun captureFrame(service: InputService) {
        try {
            // Берём размеры из того же SCREEN_INFO что использует MP pipeline
            val w = SCREEN_INFO.width
            val h = SCREEN_INFO.height
            if (w <= 0 || h <= 0) return

            // Переаллоцируем bitmap только при смене размера экрана
            if (bitmap == null || lastWidth != w || lastHeight != h) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                // RGBA: 4 байта на пиксель — точно как PixelFormat.RGBA_8888 в ImageReader
                byteBuffer = ByteBuffer.allocateDirect(w * h * 4)
                lastWidth = w
                lastHeight = h
                Log.d(TAG, "bitmap reallocated: ${w}x${h}")
            }

            val bmp = bitmap ?: return
            val buf = byteBuffer ?: return

            // Рисуем UI дерево на canvas
            val canvas = Canvas(bmp)
            canvas.drawColor(XmlRenderConfigManager.current.backgroundColor())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val windows = service.getWindowsList().sortedBy { it.layer }
                for (window in windows) {
                    // Экранная клавиатура как каркас бесполезна: клавиши Samsung не
                    // помечены clickable (фон почти прозрачный), а Shift/Backspace/пробел
                    // вообще без текста — на их месте пустота. При этом печатать её не
                    // нужно: текст с десктопа идёт напрямую через
                    // InputService.onKeyEvent -> InputConnection.commitText/sendKeyEvent.
                    // Прячем ТОЛЬКО из отрисовки — в системе IME остаётся открытой,
                    // иначе getCurrentInputConnection() отвалится и ввод сломается.
                    if (XmlRenderConfigManager.current.hideKeyboard &&
                        window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        continue
                    }
                    val root = window.root ?: continue
                    renderNode(canvas, root)
                    root.recycle()
                }
            } else {
                val root = service.getRootNode()
                if (root != null) {
                    renderNode(canvas, root)
                    root.recycle()
                }
            }

            // Bitmap → ByteBuffer (ARGB_8888 = RGBA на Android)
            buf.rewind()
            bmp.copyPixelsToBuffer(buf)
            buf.rewind()

            if (!isRunning.get()) {
                Log.d(TAG, "captureFrame: skipping FFI call — already stopped")
                return
            }

            // В XML режиме занавеска не попадает в захват автоматически —
            // мы рисуем дерево AccessibilityNodeInfo а не пиксели экрана.
            // Overlay TYPE_APPLICATION_OVERLAY не входит в accessibility дерево.
            FFI.onVideoFrameUpdate(buf)

        } catch (e: Exception) {
            Log.e(TAG, "captureFrame error", e)
        }
    }

    // -----------------------------------------------------------------------
    // Render UI tree
    // -----------------------------------------------------------------------

    private val bgPaint          = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint      = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val clickBorderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    // TextPaint нужен для StaticLayout
    private val textPaint        = TextPaint(Paint.ANTI_ALIAS_FLAG)
    // Виджет-специфичные Paint объекты
    private val widgetFillPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val widgetStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val checkPaint       = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val checkPath        = Path()
    private val bounds           = Rect()
    private val rectF            = RectF()
    private val tmpRectF         = RectF()

    private fun renderNode(canvas: Canvas, node: AccessibilityNodeInfo, depth: Int = 0) {
        val cfg = XmlRenderConfigManager.current

        // Пропускаем невидимые если включено
        if (cfg.skipInvisible && !node.isVisibleToUser) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                renderNode(canvas, child, depth + 1)
                child.recycle()
            }
            return
        }

        if (depth > cfg.maxDepth) return

        node.getBoundsInScreen(bounds)

        if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            val scale = SCREEN_INFO.scale.toFloat()
            rectF.set(
                bounds.left / scale,
                bounds.top / scale,
                bounds.right / scale,
                bounds.bottom / scale
            )

            // API 33+: уточняем bounds через ExtraRenderingInfo.layoutSize
            // layoutSize даёт реальные размеры View до clip/scroll — точнее getBoundsInScreen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    node.extraRenderingInfo?.layoutSize?.let { size ->
                        if (size.width > 0 && size.height > 0) {
                            // Используем layoutSize только для высоты — ширина из bounds точнее
                            // (bounds учитывает видимую область, layoutSize — полный размер)
                            val layoutH = size.height / scale
                            // Корректируем только если layoutSize близок к bounds (±20%)
                            val boundsH = rectF.height()
                            if (layoutH > 0 && kotlin.math.abs(layoutH - boundsH) / boundsH < 0.2f) {
                                // bounds точнее для позиционирования — не меняем top/left
                                // но можем использовать layoutH для текстового padding
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Фон листового узла
            if (node.childCount == 0) {
                bgPaint.color = cfg.nodeBgColor(
                    node.isClickable, node.isFocused,
                    node.isEditable, node.isCheckable, depth)
                val a = (Color.alpha(bgPaint.color) * cfg.contrast).toInt().coerceIn(0, 255)
                bgPaint.alpha = a
                canvas.drawRect(rectF, bgPaint)
            }

            // Границы
            if (cfg.showClickableIndicators && node.isClickable) {
                clickBorderPaint.color = cfg.clickableBorderColor()
                canvas.drawRect(rectF, clickBorderPaint)
            } else if (cfg.showWindowBorders) {
                borderPaint.color = cfg.defaultBorderColor()
                canvas.drawRect(rectF, borderPaint)
            }

            // Специальные виджеты — рисуем поверх фона
            val className = node.className?.toString() ?: ""
            val isCheck = isCheckable(node)
            val isSeeK  = isSeekBar(className)
            when {
                isSeeK   -> drawSeekBar(canvas, node, rectF, scale)
                isCheck  -> drawCheckable(canvas, node, rectF, scale, cfg.colorScheme)
            }

            // Текст и иконки — только в листовых нодах
            if (node.childCount == 0 && !isSeeK) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()

                // Пробуем нарисовать иконку по contentDescription
                // Иконка рисуется если нет текста (чисто иконочная кнопка)
                // или если это ImageView/ImageButton
                val isImageNode = className.contains("Image", ignoreCase = true)
                val iconDrawn = if (isImageNode || text.isNullOrBlank()) {
                    IconRenderer.drawIfIcon(
                        canvas, desc, className, rectF, scale, cfg.textColor()
                    )
                } else false

                // Текст
                if (cfg.showTextContent && !iconDrawn) {
                    val displayText = text ?: desc
                    if (!displayText.isNullOrBlank()) {
                        val textBounds = if (isCheck) {
                            val iconW = (rectF.height() * 1.2f).coerceIn(16f / scale, 36f / scale)
                            RectF(rectF.left, rectF.top, rectF.right - iconW - 4f / scale, rectF.bottom)
                        } else {
                            rectF
                        }
                        val textSize = getNodeTextSize(node, cfg.textSize / scale)
                        drawNodeText(
                            canvas, displayText, textBounds, textSize, cfg.textColor(),
                            centerHorizontally = node.isClickable
                        )
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            renderNode(canvas, child, depth + 1)
            child.recycle()
        }
    }

    // -----------------------------------------------------------------------
    // ExtraRenderingInfo — реальный размер текста (API 33+)
    // -----------------------------------------------------------------------

    /**
     * Возвращает реальный textSizeInPx из AccessibilityNodeInfo.ExtraRenderingInfo (API 33+).
     * Это точный размер шрифта как на устройстве — без угадывания.
     *
     * Fallback: cfg.textSize (пользовательская настройка) на старых Android.
     */
    private fun getNodeTextSize(node: AccessibilityNodeInfo, fallback: Float): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return fallback
        return try {
            val info = node.extraRenderingInfo ?: return fallback
            val sizePx = info.textSizeInPx
            // sizePx > 0 означает что нода реально TextView с текстом
            if (sizePx > 0f) {
                // ExtraRenderingInfo даёт размер в физических пикселях —
                // масштабируем так же как координаты
                sizePx / SCREEN_INFO.scale.toFloat()
            } else {
                fallback
            }
        } catch (_: Exception) { fallback }
    }

    // -----------------------------------------------------------------------
    // Helpers для определения типа виджета
    // -----------------------------------------------------------------------

    private fun isSeekBar(className: String) =
        className.endsWith("SeekBar") || className.endsWith("Slider")

    private fun isCheckable(node: AccessibilityNodeInfo) =
        node.isCheckable || node.isChecked ||
        node.className?.toString()?.let {
            it.endsWith("CheckBox") || it.endsWith("RadioButton") ||
            it.endsWith("Switch") || it.endsWith("ToggleButton") ||
            it.endsWith("CheckedTextView")
        } == true

    // -----------------------------------------------------------------------
    // SeekBar / Slider
    // -----------------------------------------------------------------------

    /**
     * Пробуем извлечь прогресс из текста типа "50%", "3/10", "50"
     */
    private fun parseProgressFromText(text: String?): Float? {
        if (text.isNullOrBlank()) return null
        return try {
            when {
                text.contains('%') ->
                    text.replace('%', ' ').trim().toFloat() / 100f
                text.contains('/') -> {
                    val parts = text.split('/')
                    if (parts.size == 2) {
                        val cur = parts[0].trim().toFloat()
                        val max = parts[1].trim().toFloat()
                        if (max > 0) cur / max else null
                    } else null
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun drawSeekBar(
        canvas: Canvas,
        node: AccessibilityNodeInfo,
        r: RectF,
        scale: Float
    ) {
        val rangeInfo = node.rangeInfo
        // Логируем один раз чтобы видеть что даёт AccessibilityNodeInfo
        Log.d("XmlCapture", "SeekBar rangeInfo=$rangeInfo " +
            "text=${node.text} desc=${node.contentDescription} " +
            "stateDesc=${if (Build.VERSION.SDK_INT >= 30) node.stateDescription else null}")

        val progress: Float
        if (rangeInfo != null) {
            val min = rangeInfo.min
            val max = rangeInfo.max
            val current = rangeInfo.current
            progress = if (max > min) (current - min) / (max - min) else 0f
        } else {
            // Fallback: пробуем распарсить из stateDescription или text
            // Например "50%" или "50/100"
            val stateText = if (Build.VERSION.SDK_INT >= 30) {
                node.stateDescription?.toString()
            } else null
            val rawText = stateText ?: node.text?.toString() ?: node.contentDescription?.toString()
            progress = parseProgressFromText(rawText) ?: 0.5f // 0.5 если не можем определить
        }

        val trackH    = (6f / scale).coerceAtLeast(3f)   // чуть толще
        val thumbR    = (12f / scale).coerceAtLeast(5f)
        val trackPad  = thumbR
        val centerY   = r.centerY()
        val trackLeft  = r.left + trackPad
        val trackRight = r.right - trackPad

        // Защита: если ширина слишком мала
        if (trackRight <= trackLeft) return

        val trackWidth = trackRight - trackLeft

        // Трек фон (серый)
        tmpRectF.set(trackLeft, centerY - trackH / 2f, trackRight, centerY + trackH / 2f)
        widgetFillPaint.color = Color.argb(150, 150, 150, 150)
        canvas.drawRoundRect(tmpRectF, trackH / 2f, trackH / 2f, widgetFillPaint)

        // Трек заполнение (синий) — минимум 2dp чтобы было видно
        val fillWidth  = (trackWidth * progress).coerceAtLeast(trackH)
        val fillRight  = trackLeft + fillWidth
        tmpRectF.set(trackLeft, centerY - trackH / 2f, fillRight, centerY + trackH / 2f)
        widgetFillPaint.color = Color.argb(230, 0, 120, 255)
        canvas.drawRoundRect(tmpRectF, trackH / 2f, trackH / 2f, widgetFillPaint)

        // Thumb (белый круг с тенью + синяя обводка)
        val thumbX = (trackLeft + trackWidth * progress).coerceIn(trackLeft + thumbR, trackRight - thumbR)
        // Тень
        widgetFillPaint.color = Color.argb(60, 0, 0, 0)
        canvas.drawCircle(thumbX + 1f / scale, centerY + 1f / scale, thumbR, widgetFillPaint)
        // Белый круг
        widgetFillPaint.color = Color.WHITE
        canvas.drawCircle(thumbX, centerY, thumbR, widgetFillPaint)
        // Обводка
        widgetStrokePaint.color       = Color.argb(230, 0, 100, 220)
        widgetStrokePaint.strokeWidth = (2f / scale).coerceAtLeast(1f)
        canvas.drawCircle(thumbX, centerY, thumbR, widgetStrokePaint)
    }

    // -----------------------------------------------------------------------
    // CheckBox / RadioButton / Switch / CheckedTextView
    // -----------------------------------------------------------------------

    private fun drawCheckable(
        canvas: Canvas,
        node: AccessibilityNodeInfo,
        r: RectF,
        scale: Float,
        colorScheme: XmlRenderConfig.ColorScheme
    ) {
        val isChecked = node.isChecked
        val className = node.className?.toString() ?: ""

        val accentColor = when (colorScheme) {
            XmlRenderConfig.ColorScheme.HIGH_CONTRAST -> Color.YELLOW
            XmlRenderConfig.ColorScheme.LIGHT         -> Color.argb(255, 0, 100, 220)
            else                                       -> Color.argb(255, 0, 140, 255)
        }
        val boxSize  = (r.height() * 0.55f).coerceIn(12f / scale, 28f / scale)
        val strokeW  = (2f / scale).coerceAtLeast(1f)

        when {
            className.endsWith("RadioButton") ->
                drawRadio(canvas, r, boxSize, strokeW, isChecked, accentColor)

            className.endsWith("Switch") || className.endsWith("ToggleButton") ->
                drawSwitch(canvas, r, boxSize, strokeW, isChecked, accentColor, scale)

            else -> // CheckBox, CheckedTextView, generic checkable
                drawCheckBox(canvas, r, boxSize, strokeW, isChecked, accentColor)
        }
    }

    private fun drawCheckBox(
        canvas: Canvas, r: RectF, size: Float, strokeW: Float,
        checked: Boolean, accent: Int
    ) {
        val cx = r.right - size / 2f - strokeW * 2
        val cy = r.centerY()
        val half = size / 2f
        tmpRectF.set(cx - half, cy - half, cx + half, cy + half)

        // Фон
        widgetFillPaint.color = if (checked) accent else Color.argb(60, 200, 200, 200)
        canvas.drawRoundRect(tmpRectF, size * 0.2f, size * 0.2f, widgetFillPaint)

        // Обводка
        widgetStrokePaint.color       = if (checked) accent else Color.argb(180, 150, 150, 150)
        widgetStrokePaint.strokeWidth = strokeW
        canvas.drawRoundRect(tmpRectF, size * 0.2f, size * 0.2f, widgetStrokePaint)

        // Галочка
        if (checked) {
            checkPaint.color       = Color.WHITE
            checkPaint.strokeWidth = strokeW * 1.8f
            checkPath.reset()
            checkPath.moveTo(cx - half * 0.55f, cy)
            checkPath.lineTo(cx - half * 0.1f,  cy + half * 0.45f)
            checkPath.lineTo(cx + half * 0.55f, cy - half * 0.45f)
            canvas.drawPath(checkPath, checkPaint)
        }
    }

    private fun drawRadio(
        canvas: Canvas, r: RectF, size: Float, strokeW: Float,
        checked: Boolean, accent: Int
    ) {
        val cx = r.right - size / 2f - strokeW * 2
        val cy = r.centerY()
        val radius = size / 2f

        widgetFillPaint.color = Color.argb(60, 200, 200, 200)
        canvas.drawCircle(cx, cy, radius, widgetFillPaint)

        widgetStrokePaint.color       = if (checked) accent else Color.argb(180, 150, 150, 150)
        widgetStrokePaint.strokeWidth = strokeW
        canvas.drawCircle(cx, cy, radius, widgetStrokePaint)

        if (checked) {
            widgetFillPaint.color = accent
            canvas.drawCircle(cx, cy, radius * 0.55f, widgetFillPaint)
        }
    }

    private fun drawSwitch(
        canvas: Canvas, r: RectF, size: Float, strokeW: Float,
        checked: Boolean, accent: Int, scale: Float
    ) {
        val trackW = size * 1.8f
        val trackH = size * 0.65f
        val cx     = r.right - trackW / 2f - strokeW * 2
        val cy     = r.centerY()

        // Трек
        tmpRectF.set(cx - trackW / 2f, cy - trackH / 2f, cx + trackW / 2f, cy + trackH / 2f)
        widgetFillPaint.color = if (checked) accent else Color.argb(120, 150, 150, 150)
        canvas.drawRoundRect(tmpRectF, trackH, trackH, widgetFillPaint)

        // Thumb
        val thumbR  = size * 0.42f
        val thumbX  = if (checked) cx + trackW / 2f - thumbR - strokeW
                      else         cx - trackW / 2f + thumbR + strokeW
        widgetFillPaint.color = Color.WHITE
        canvas.drawCircle(thumbX, cy, thumbR, widgetFillPaint)
    }

    /**
     * Рисует текст внутри bounds ноды с правильным переносом строк и вертикальным центрированием.
     * Использует StaticLayout для многострочности.
     */
    // Минимум, до которого разрешено ужимать шрифт при автоподборе.
    private const val MIN_TEXT_SCALE = 0.55f
    private const val LINE_SPACING = 1.1f

    private fun buildTextLayout(
        text: String, width: Int, size: Float,
        align: Layout.Alignment, availableHeight: Float
    ): StaticLayout {
        textPaint.textSize = size
        // maxLines считаем от реальной высоты бокса. Раньше стоял Int.MAX_VALUE,
        // из-за чего setEllipsize(END) не работал вовсе: текст переносился на
        // строку, которая потом просто срезалась clipRect — получался обрубок
        // без многоточия («Conditions of », «Hel», «© … or its»).
        val lineHeight = textPaint.fontSpacing * LINE_SPACING
        val maxLines = kotlin.math.max(1, kotlin.math.floor(availableHeight / lineHeight).toInt())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, width)
                .setAlignment(align)
                .setLineSpacing(0f, LINE_SPACING)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, textPaint, width, align, LINE_SPACING, 0f, false)
        }
    }

    private fun drawNodeText(
        canvas: Canvas,
        text: String,
        nodeBounds: RectF,
        textSize: Float,
        textColor: Int,
        centerHorizontally: Boolean = false
    ) {
        val cfg = XmlRenderConfigManager.current
        val padding = textSize * 0.2f  // отступ пропорционален размеру текста
        val availableWidth = (nodeBounds.width() - padding * 2).toInt()
        val availableHeight = nodeBounds.height() - padding * 2

        if (availableWidth <= 0 || availableHeight <= 0) return

        textPaint.color    = textColor
        textPaint.isAntiAlias = true

        // Подписи кнопок на экране стоят по центру — при ALIGN_NORMAL они
        // уезжали в левый верхний угол («Submit» в углу широкой кнопки).
        val align = if (centerHorizontally) Layout.Alignment.ALIGN_CENTER
                    else Layout.Alignment.ALIGN_NORMAL

        var size = textSize
        var layout = buildTextLayout(text, availableWidth, size, align, availableHeight)

        // Сначала пробуем ужать шрифт, и только если не помогло — обрезаем.
        // Так «Conditions of Use» влезает целиком вместо «Conditions of ».
        if (cfg.autoFitText) {
            val minSize = textSize * MIN_TEXT_SCALE
            while (layout.height > availableHeight && size > minSize) {
                size = (size * 0.85f).coerceAtLeast(minSize)
                layout = buildTextLayout(text, availableWidth, size, align, availableHeight)
            }
        }

        val textHeight = layout.height.toFloat()

        // Вертикальное центрирование — если текст влезает
        val topOffset = if (textHeight <= availableHeight) {
            padding + (availableHeight - textHeight) / 2f
        } else {
            padding  // текст больше bounds — рисуем с отступа, StaticLayout сам обрежет
        }

        canvas.save()
        // Clip чтобы текст не вылезал за bounds ноды
        canvas.clipRect(nodeBounds)
        canvas.translate(nodeBounds.left + padding, nodeBounds.top + topOffset)
        layout.draw(canvas)
        canvas.restore()
    }
}

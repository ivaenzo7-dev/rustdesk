package com.carriez.flutter_hbb

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * XmlRenderConfig — настройки рендера XML capture.
 * Сохраняется в SharedPreferences как JSON.
 * Применяется на лету — XmlCapture читает currentConfig на каждый кадр.
 */
data class XmlRenderConfig(
    // ── Визуальные ──
    val textSize: Float = 26f,
    val elementOpacity: Int = 160,      // 0-255
    val contrast: Float = 1.0f,         // 0.5-2.0
    val colorScheme: ColorScheme = ColorScheme.DARK,

    // ── Информационные слои ──
    val showClickableIndicators: Boolean = true,
    val showWindowBorders: Boolean = false,
    val showTextContent: Boolean = true,

    // ── Производительность ──
    val frameRate: Int = 15,            // 5/10/15/30
    val maxDepth: Int = 20,
    val skipInvisible: Boolean = true,

    // ── Поведение ──
    // Не рисовать окно экранной клавиатуры: как каркас оно нечитаемо, а текст
    // с десктопа идёт мимо него напрямую в поле ввода.
    val hideKeyboard: Boolean = true,
    // Подгонять размер шрифта под бокс, прежде чем переносить и обрезать.
    val autoFitText: Boolean = true,
) {
    enum class ColorScheme { DARK, LIGHT, HIGH_CONTRAST }

    // Цвета фона для узлов в зависимости от схемы
    fun nodeBgColor(isClickable: Boolean, isFocused: Boolean, isEditable: Boolean,
                    isCheckable: Boolean, depth: Int): Int {
        val alpha = elementOpacity
        return when (colorScheme) {
            ColorScheme.DARK -> when {
                // Поле ввода проверяем ПЕРВЫМ. Раньше EditText попадал в ветку
                // isClickable && isFocused и заливался почти непрозрачным синим —
                // поле превращалось в сплошной прямоугольник, на котором не было
                // видно набранного текста. Держим заливку полупрозрачной.
                isEditable && isFocused  -> Color.argb(alpha / 2, 25, 90, 40)
                isEditable               -> Color.argb(alpha / 3, 20, 60, 20)
                isClickable && isFocused -> Color.argb((alpha * 1.2f).toInt().coerceAtMost(255), 30, 120, 200)
                isClickable              -> Color.argb(alpha, 40, 40, 60)
                isCheckable              -> Color.argb(alpha, 60, 40, 80)
                depth % 2 == 0           -> Color.argb(alpha / 3, 30, 30, 40)
                else                     -> Color.argb(alpha / 4, 50, 50, 70)
            }
            ColorScheme.LIGHT -> when {
                isEditable && isFocused  -> Color.argb(alpha / 2, 190, 245, 200)
                isEditable               -> Color.argb(alpha / 3, 200, 240, 200)
                isClickable && isFocused -> Color.argb(alpha, 100, 180, 255)
                isClickable              -> Color.argb(alpha, 200, 220, 255)
                isCheckable              -> Color.argb(alpha, 230, 200, 255)
                depth % 2 == 0           -> Color.argb(alpha / 3, 240, 240, 245)
                else                     -> Color.argb(alpha / 4, 220, 220, 230)
            }
            ColorScheme.HIGH_CONTRAST -> when {
                isClickable && isFocused -> Color.argb(255, 255, 220, 0)
                isClickable              -> Color.argb(255, 0, 120, 255)
                isEditable               -> Color.argb(255, 0, 180, 0)
                isCheckable              -> Color.argb(255, 180, 0, 255)
                else                     -> Color.argb(80, 255, 255, 255)
            }
        }
    }

    fun clickableBorderColor(): Int = when (colorScheme) {
        ColorScheme.DARK          -> Color.argb(180, 0, 200, 255)
        ColorScheme.LIGHT         -> Color.argb(200, 0, 100, 255)
        ColorScheme.HIGH_CONTRAST -> Color.argb(255, 255, 255, 0)
    }

    fun defaultBorderColor(): Int = when (colorScheme) {
        ColorScheme.DARK          -> Color.argb(60, 200, 200, 200)
        ColorScheme.LIGHT         -> Color.argb(80, 100, 100, 100)
        ColorScheme.HIGH_CONTRAST -> Color.argb(120, 255, 255, 255)
    }

    fun textColor(): Int = when (colorScheme) {
        ColorScheme.DARK          -> Color.WHITE
        ColorScheme.LIGHT         -> Color.BLACK
        ColorScheme.HIGH_CONTRAST -> Color.YELLOW
    }

    fun backgroundColor(): Int = when (colorScheme) {
        ColorScheme.DARK          -> Color.BLACK
        ColorScheme.LIGHT         -> Color.argb(255, 245, 245, 245)
        ColorScheme.HIGH_CONTRAST -> Color.BLACK
    }

    // ── Сериализация ──
    fun toJson(): JSONObject = JSONObject().apply {
        put("textSize", textSize)
        put("elementOpacity", elementOpacity)
        put("contrast", contrast)
        put("colorScheme", colorScheme.name)
        put("showClickableIndicators", showClickableIndicators)
        put("showWindowBorders", showWindowBorders)
        put("showTextContent", showTextContent)
        put("frameRate", frameRate)
        put("maxDepth", maxDepth)
        put("skipInvisible", skipInvisible)
        put("hideKeyboard", hideKeyboard)
        put("autoFitText", autoFitText)
    }

    companion object {
        fun fromJson(json: JSONObject): XmlRenderConfig = XmlRenderConfig(
            textSize              = json.optDouble("textSize", 26.0).toFloat(),
            elementOpacity        = json.optInt("elementOpacity", 160),
            contrast              = json.optDouble("contrast", 1.0).toFloat(),
            colorScheme           = runCatching {
                ColorScheme.valueOf(json.optString("colorScheme", "DARK"))
            }.getOrDefault(ColorScheme.DARK),
            showClickableIndicators = json.optBoolean("showClickableIndicators", true),
            showWindowBorders     = json.optBoolean("showWindowBorders", false),
            showTextContent       = json.optBoolean("showTextContent", true),
            frameRate             = json.optInt("frameRate", 15),
            maxDepth              = json.optInt("maxDepth", 20),
            skipInvisible         = json.optBoolean("skipInvisible", true),
            hideKeyboard          = json.optBoolean("hideKeyboard", true),
            autoFitText           = json.optBoolean("autoFitText", true),
        )
    }
}

// ---------------------------------------------------------------------------
// Manager — singleton, хранит текущий конфиг и обслуживает MethodChannel
// ---------------------------------------------------------------------------
object XmlRenderConfigManager {

    private const val PREFS = "xml_render_config"
    private const val KEY   = "config_json"
    const val CHANNEL = "com.carriez.flutter_hbb/xml_config"

    @Volatile
    var current: XmlRenderConfig = XmlRenderConfig()
        private set

    fun init(context: Context, messenger: BinaryMessenger) {
        // Загружаем сохранённый конфиг
        load(context)

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getConfig" -> {
                    result.success(current.toJson().toString())
                }
                "updateConfig" -> {
                    try {
                        val json = JSONObject(call.arguments as String)
                        current = XmlRenderConfig.fromJson(json)
                        save(context)
                        // Обновляем frameRate в XmlCapture на лету
                        XmlCapture.applyConfig(current)
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("PARSE_ERROR", e.message, null)
                    }
                }
                "resetConfig" -> {
                    current = XmlRenderConfig()
                    save(context)
                    XmlCapture.applyConfig(current)
                    result.success(current.toJson().toString())
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY, null) ?: return
        runCatching { current = XmlRenderConfig.fromJson(JSONObject(json)) }
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, current.toJson().toString()).apply()
    }
}

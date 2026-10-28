package com.pixeltaplock

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot

/**
 * Pure, testable evaluation models and logic for PixelTapLock's Hybrid Blank-Space Verification
 * (Check A: Coordinate Hit-Test & Edge Ignore Zones, Check B: Event-Correlation Debounce,
 * Check C: Active Workspace Filter).
 */
data class TapSample(
    val rawX: Float,
    val rawY: Float,
    val uptimeMillis: Long
) {
    /**
     * On Android 12-17, MotionEvent.ACTION_OUTSIDE delivered to a 1x1 FLAG_WATCH_OUTSIDE_TOUCH
     * overlay may mask coordinates to (0f, 0f). Coordinates are considered present only when
     * strictly positive as required by Check A.
     */
    val hasValidCoordinates: Boolean
        get() = rawX > 0f && rawY > 0f
}

data class ScreenBoundsConfig(
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val topIgnoreInsetPx: Int,
    val bottomIgnoreInsetPx: Int,
    val doubleTapSlopPx: Int
)

/**
 * Abstract representation of an AccessibilityNodeInfo node so hit-testing algorithms
 * can be verified both in unit tests and against live framework AccessibilityNodeInfo trees.
 */
data class NodeSnapshot(
    val boundsInScreen: Rect,
    val className: String?,
    val viewIdResourceName: String?,
    val contentDescription: String?,
    val isVisibleToUser: Boolean,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isFocusable: Boolean,
    val isRoot: Boolean,
    val childCount: Int,
    val children: List<NodeSnapshot> = emptyList(),
    val packageName: String? = null
)

object BlankSpaceHitTester {

    const val EVENT_CORRELATION_DEBOUNCE_MS = 60L
    const val SCREEN_WAKE_TRANSITION_GRACE_MS = 600L
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    private val DEDICATED_WORKSPACE_CLASSES = listOf(
        "Workspace",
        "CellLayout",
        "ShortcutAndWidgetContainer",
        "DragLayer",
        "PagedView",
        "NotificationStackScrollLayout",
        "NotificationPanelView",
        "KeyguardRootView",
        "SharedNotificationContainer"
    )

    private val GENERIC_CONTAINER_PATTERNS = listOf(
        "ViewPager",
        "RecyclerView",
        "ScrollView",
        "FrameLayout",
        "RelativeLayout",
        "LinearLayout",
        "ConstraintLayout",
        "CoordinatorLayout",
        "ViewGroup"
    )

    private val WORKSPACE_ID_PATTERNS = listOf(
        "workspace",
        "cell_layout",
        "drag_layer",
        "launcher",
        "scrim",
        "keyguard_root",
        "lock_screen",
        "notification_panel",
        "notification_stack",
        "shared_notification_container",
        "shade_window",
        "overview_panel"
    )

    private val INTERACTIVE_WIDGET_HOST_PATTERNS = listOf(
        "AppWidgetHostView",
        "LauncherAppWidgetHostView"
    )

    private val INTERACTIVE_LEAF_CLASS_PATTERNS = listOf(
        "ExpandableNotificationRow",
        "QSTileView",
        "BubbleTextView",
        "FolderIcon",
        "Folder"
    )

    private val BOUNCER_CLASS_PATTERNS = listOf(
        "LockPatternView",
        "PasswordTextView",
        "NumPadKey",
        "PinBouncer"
    )

    private val BOUNCER_ID_PATTERNS = listOf(
        "pinentry",
        "pin_entry",
        "lockpatternview",
        "lock_pattern_view",
        "passwordentry",
        "password_entry",
        "simpinentry",
        "key_enter",
        "delete_button",
        "bouncer_primary_message_area"
    )

    private val NON_BLANK_CONTAINER_ID_PATTERNS = listOf(
        "bouncer",
        "pin_entry",
        "pinentry",
        "password",
        "pattern",
        "camera",
        "photo",
        "filmstrip",
        "zoom",
        "viewfinder",
        "shutter"
    )

    /**
     * Returns true only when the device is interactive AND the physical display is fully in
     * [android.view.Display.STATE_ON] (not in STATE_OFF, STATE_DOZE, STATE_DOZE_SUSPEND, or
     * STATE_ON_SUSPEND). Prevents taps on Always-On Display (AOD) from being recorded as
     * lock-screen taps.
     */
    fun isDisplayFullyAwake(displayState: Int, isInteractive: Boolean): Boolean {
        return isInteractive && displayState == android.view.Display.STATE_ON
    }

    /**
     * Returns true when a tap occurs at or before [lastScreenWakeUptimeMillis] (while waking from
     * AOD/Off) or within [gracePeriodMs] after [lastScreenWakeUptimeMillis] (while the screen is
     * transitioning from AOD to the normal Lock Screen).
     */
    fun isWithinScreenWakeGracePeriod(
        tapUptimeMillis: Long,
        lastScreenWakeUptimeMillis: Long,
        gracePeriodMs: Long = SCREEN_WAKE_TRANSITION_GRACE_MS
    ): Boolean {
        if (lastScreenWakeUptimeMillis <= 0L) {
            return false
        }
        val effectiveGraceMs = gracePeriodMs.coerceAtLeast(0L)
        return tapUptimeMillis <= (lastScreenWakeUptimeMillis + effectiveGraceMs)
    }

    /**
     * Check A (Part 1): Validates whether a tap with valid coordinates falls inside
     * the top status bar or bottom gesture navigation bar ignore zones.
     */
    fun isWithinEdgeIgnoreZone(
        rawX: Float,
        rawY: Float,
        config: ScreenBoundsConfig
    ): Boolean {
        if (rawX <= 0f || rawY <= 0f) {
            return false
        }
        if (config.screenHeightPx <= 0 || config.screenWidthPx <= 0) {
            return false
        }
        val inTopStatusBarZone = rawY < config.topIgnoreInsetPx
        val inBottomNavZone = rawY > (config.screenHeightPx - config.bottomIgnoreInsetPx)
        return inTopStatusBarZone || inBottomNavZone
    }

    /**
     * Evaluates whether two consecutive outside taps qualify as a double-tap within
     * [doubleTapTimeoutMs] and within [ScreenBoundsConfig.doubleTapSlopPx].
     */
    fun isDoubleTapPair(
        firstTap: TapSample,
        secondTap: TapSample,
        doubleTapTimeoutMs: Int,
        config: ScreenBoundsConfig
    ): Boolean {
        val deltaMs = secondTap.uptimeMillis - firstTap.uptimeMillis
        if (deltaMs <= 15L || deltaMs > doubleTapTimeoutMs.toLong()) {
            return false
        }

        if (firstTap.hasValidCoordinates && secondTap.hasValidCoordinates) {
            val dx = (secondTap.rawX - firstTap.rawX).toDouble()
            val dy = (secondTap.rawY - firstTap.rawY).toDouble()
            val distance = hypot(dx, dy)
            if (distance > config.doubleTapSlopPx.toDouble()) {
                return false
            }
        }

        return true
    }

    /**
     * Check B (Event-Correlation Debounce):
     * Returns true if any click, long-click, scroll, or app/folder window transition
     * accessibility event occurred between [tap1UptimeMillis] and [debounceEndUptimeMillis].
     */
    fun wasInterruptedByInteractiveEvent(
        tap1UptimeMillis: Long,
        debounceEndUptimeMillis: Long,
        lastInteractiveEventUptimeMillis: Long
    ): Boolean {
        if (lastInteractiveEventUptimeMillis <= 0L) return false
        return lastInteractiveEventUptimeMillis in tap1UptimeMillis..debounceEndUptimeMillis
    }

    /**
     * Check A (Part 2): Traverses a live [AccessibilityNodeInfo] tree at ([rawX], [rawY])
     * and returns true if the coordinate hits an interactive child element (app icon, widget,
     * folder, notification card, quick-setting tile, lock-screen shortcut), excluding
     * the root workspace/pager container itself and our own BlankSpaceWidget.
     */
    fun hitsInteractiveElement(
        rootNode: AccessibilityNodeInfo?,
        rawX: Float,
        rawY: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        ownWidgetContentDescription: String? = null
    ): Boolean {
        if (rootNode == null || rawX <= 0f || rawY <= 0f) {
            return false
        }
        val x = rawX.toInt()
        val y = rawY.toInt()
        return checkLiveNodeRecursive(
            node = rootNode,
            x = x,
            y = y,
            isRoot = true,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            ownWidgetContentDescription = ownWidgetContentDescription,
            depth = 0
        )
    }

    private fun checkLiveNodeRecursive(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        isRoot: Boolean,
        screenWidthPx: Int,
        screenHeightPx: Int,
        ownWidgetContentDescription: String?,
        depth: Int
    ): Boolean {
        if (depth > 40) return false
        if (!node.isVisibleToUser) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (isRectEmpty(bounds) || !rectContains(bounds, x, y)) {
            return false
        }

        val contentDesc = node.contentDescription?.toString()
        if (!ownWidgetContentDescription.isNullOrEmpty() && contentDesc == ownWidgetContentDescription) {
            // Tapping the user's intentional BlankSpaceWidget is allowed.
            return false
        }

        val childCount = node.childCount
        // First inspect children (depth-first) to see if a specific leaf/child hits (x, y)
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val childHit = checkLiveNodeRecursive(
                node = child,
                x = x,
                y = y,
                isRoot = false,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                ownWidgetContentDescription = ownWidgetContentDescription,
                depth = depth + 1
            )
            if (childHit) {
                return true
            }
        }

        // Evaluate whether this node itself is an interactive element vs a root/workspace container
        val isInteractiveFlag = node.isClickable || node.isLongClickable || node.isFocusable
        if (!isInteractiveFlag) {
            return false
        }

        if (isExcludedWorkspaceContainer(
                isRoot = isRoot,
                bounds = bounds,
                className = node.className?.toString(),
                viewIdResourceName = node.viewIdResourceName,
                childCount = childCount,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                packageName = node.packageName?.toString()
            )
        ) {
            return false
        }

        return true
    }

    /**
     * Same hit-test logic over [NodeSnapshot] for deterministic unit testing.
     */
    fun hitsInteractiveSnapshot(
        root: NodeSnapshot?,
        rawX: Float,
        rawY: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        ownWidgetContentDescription: String? = null
    ): Boolean {
        if (root == null || rawX <= 0f || rawY <= 0f) return false
        val x = rawX.toInt()
        val y = rawY.toInt()
        return checkSnapshotRecursive(
            node = root,
            x = x,
            y = y,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            ownWidgetContentDescription = ownWidgetContentDescription,
            depth = 0
        )
    }

    private fun checkSnapshotRecursive(
        node: NodeSnapshot,
        x: Int,
        y: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        ownWidgetContentDescription: String?,
        depth: Int
    ): Boolean {
        if (depth > 40 || !node.isVisibleToUser) return false
        if (isRectEmpty(node.boundsInScreen) || !rectContains(node.boundsInScreen, x, y)) {
            return false
        }

        if (!ownWidgetContentDescription.isNullOrEmpty() &&
            node.contentDescription == ownWidgetContentDescription
        ) {
            return false
        }

        for (child in node.children) {
            if (checkSnapshotRecursive(
                    node = child,
                    x = x,
                    y = y,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    ownWidgetContentDescription = ownWidgetContentDescription,
                    depth = depth + 1
                )
            ) {
                return true
            }
        }

        val isInteractiveFlag = node.isClickable || node.isLongClickable || node.isFocusable
        if (!isInteractiveFlag) return false

        if (isExcludedWorkspaceContainer(
                isRoot = node.isRoot,
                bounds = node.boundsInScreen,
                className = node.className,
                viewIdResourceName = node.viewIdResourceName,
                childCount = maxOf(node.childCount, node.children.size),
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                packageName = node.packageName
            )
        ) {
            return false
        }

        return true
    }

    /**
     * Determines if a node is the root workspace/pager/drag-layer/lockscreen container rather than
     * an interactive child element (such as an icon, folder, widget, QS tile, notification card,
     * lockscreen shortcut, bouncer, or camera/photo viewer).
     */
    @Suppress("UNUSED_PARAMETER")
    fun isExcludedWorkspaceContainer(
        isRoot: Boolean,
        bounds: Rect,
        className: String?,
        viewIdResourceName: String?,
        childCount: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        packageName: String? = null
    ): Boolean {
        val cls = className.orEmpty()
        val clsLower = cls.lowercase()
        val viewId = viewIdResourceName.orEmpty().lowercase()
        val pkgLower = packageName.orEmpty().lowercase()

        // Never treat bouncer, camera, photo, or filmstrip nodes (even root or null-id ViewPagers)
        // as blank workspace containers
        if (NON_BLANK_CONTAINER_ID_PATTERNS.any {
                viewId.contains(it) || clsLower.contains(it) || pkgLower.contains(it)
            }
        ) {
            return false
        }

        if (isRoot) return true

        // Any AppWidgetHostView is an interactive widget container on the workspace (not empty wallpaper)
        if (INTERACTIVE_WIDGET_HOST_PATTERNS.any { cls.contains(it, ignoreCase = true) }) {
            return false
        }

        // Known interactive leaf/card classes (notification row, QS tile, icon, folder) are never containers
        if (INTERACTIVE_LEAF_CLASS_PATTERNS.any { cls.contains(it, ignoreCase = true) }) {
            return false
        }

        // Explicit notification card or quick settings tile or shortcut IDs are interactive children
        if (viewId.contains("notification") &&
            !viewId.contains("notification_panel") &&
            !viewId.contains("notification_stack") &&
            !viewId.contains("shared_notification_container")
        ) {
            return false
        }
        if (viewId.contains("qs_tile") ||
            viewId.contains("shortcut") ||
            viewId.contains("icon") ||
            viewId.contains("folder") ||
            viewId.contains("start_button") ||
            viewId.contains("end_button") ||
            viewId.contains("lock_icon")
        ) {
            return false
        }

        val widthRatio = if (screenWidthPx > 0) {
            rectWidth(bounds).toFloat() / screenWidthPx.toFloat()
        } else {
            0f
        }
        val heightRatio = if (screenHeightPx > 0) {
            rectHeight(bounds).toFloat() / screenHeightPx.toFloat()
        } else {
            0f
        }

        val matchesWorkspaceId = WORKSPACE_ID_PATTERNS.any { viewId.contains(it) }
        val matchesDedicatedWorkspaceClass = DEDICATED_WORKSPACE_CLASSES.any {
            cls.contains(it, ignoreCase = true)
        }

        // Dedicated launcher workspace / CellLayout / lockscreen stack containers (even when empty with childCount == 0,
        // or when NotificationStackScrollLayout sits below a large double-line Pixel clock at ~35%+ height)
        if ((matchesWorkspaceId || matchesDedicatedWorkspaceClass) &&
            widthRatio >= 0.70f && heightRatio >= 0.35f
        ) {
            return true
        }

        // Generic full-screen layout containers (e.g. FrameLayout, ConstraintLayout, ViewPager, ScrollView)
        // covering >= 75% width and >= 45% height on Home Screen or Lock Screen (even if childCount == 0)
        val matchesGenericContainerClass = GENERIC_CONTAINER_PATTERNS.any {
            cls.contains(it, ignoreCase = true)
        }
        if (matchesGenericContainerClass && widthRatio >= 0.75f && heightRatio >= 0.45f) {
            return true
        }

        return false
    }

    /**
     * Check C (Lock Screen Filter):
     * When KeyguardManager reports the device is locked, verifies that the user is actually on
     * the SystemUI Lock Screen ("com.android.systemui") and NOT inside a showWhenLocked
     * foreground activity occluding the lock screen (such as Pixel Camera, Google Photos
     * locked recent-photo viewer, Emergency Dialer, Alarm, or In-Call UI), nor typing on an IME.
     */
    fun isLockScreenUnobscured(
        windows: List<AccessibilityWindowInfo>?,
        activeRootPackageName: String?,
        allowedLauncherPackages: Set<String> = emptySet(),
        screenHeightPx: Int
    ): Boolean {
        if (activeRootPackageName != SYSTEM_UI_PACKAGE) {
            return false
        }

        if (windows.isNullOrEmpty()) {
            return true
        }

        var hasActiveSystemUiWindow = false
        for (window in windows) {
            when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val pkg = window.root?.packageName?.toString()
                    if (pkg == SYSTEM_UI_PACKAGE) {
                        if (window.isActive || window.isFocused) {
                            hasActiveSystemUiWindow = true
                        }
                    } else {
                        // Any active/focused non-SystemUI application window while locked means a
                        // showWhenLocked activity (e.g., Camera, Photos filmstrip, Dialer) is in the foreground.
                        if (window.isActive || window.isFocused) {
                            return false
                        }
                        // Any visible non-launcher application window (or an application window whose
                        // root is null while busy/transitioning, such as Camera/Photos processing a photo)
                        // while locked means Keyguard is occluded by a showWhenLocked app.
                        if (pkg.isNullOrEmpty() || pkg !in allowedLauncherPackages) {
                            return false
                        }
                        // Windows in getWindows() are ordered descending in Z-order (top-most first).
                        // If a launcher application window sits above the SystemUI lock screen window, reject.
                        if (!hasActiveSystemUiWindow) {
                            return false
                        }
                    }
                }
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                    if (window.isActive || window.isFocused) {
                        return false
                    }
                    if (screenHeightPx > 0) {
                        val rect = Rect()
                        window.getBoundsInScreen(rect)
                        if (rectHeight(rect) > 0) {
                            return false
                        }
                    }
                }
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    val pkg = window.root?.packageName?.toString()
                    val isSystemUiPkg = pkg == null || pkg == SYSTEM_UI_PACKAGE
                    if ((window.isActive || window.isFocused) && isSystemUiPkg) {
                        if (screenHeightPx > 0) {
                            val rect = Rect()
                            window.getBoundsInScreen(rect)
                            val h = rectHeight(rect)
                            if (h >= (screenHeightPx * 0.50f).toInt()) {
                                hasActiveSystemUiWindow = true
                            }
                        } else {
                            hasActiveSystemUiWindow = true
                        }
                    }
                }
            }
        }

        return hasActiveSystemUiWindow
    }

    /**
     * Returns true if the Lock Screen PIN / Pattern / Password Bouncer is currently visible in
     * the live [AccessibilityNodeInfo] tree so rapid taps while unlocking never lock the screen.
     */
    fun isLockScreenBouncerShowing(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return checkLiveBouncerRecursive(rootNode, depth = 0)
    }

    private fun checkLiveBouncerRecursive(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 35 || !node.isVisibleToUser) return false

        val cls = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty().lowercase()

        if (BOUNCER_CLASS_PATTERNS.any { cls.contains(it, ignoreCase = true) } ||
            BOUNCER_ID_PATTERNS.any { viewId.contains(it) }
        ) {
            return true
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            if (checkLiveBouncerRecursive(child, depth + 1)) {
                return true
            }
        }
        return false
    }

    /**
     * Same bouncer detection over [NodeSnapshot] for deterministic unit testing.
     */
    fun isLockScreenBouncerSnapshotShowing(root: NodeSnapshot?): Boolean {
        if (root == null) return false
        return checkSnapshotBouncerRecursive(root, depth = 0)
    }

    private fun checkSnapshotBouncerRecursive(node: NodeSnapshot, depth: Int): Boolean {
        if (depth > 35 || !node.isVisibleToUser) return false

        val cls = node.className.orEmpty()
        val viewId = node.viewIdResourceName.orEmpty().lowercase()

        if (BOUNCER_CLASS_PATTERNS.any { cls.contains(it, ignoreCase = true) } ||
            BOUNCER_ID_PATTERNS.any { viewId.contains(it) }
        ) {
            return true
        }

        for (child in node.children) {
            if (checkSnapshotBouncerRecursive(child, depth + 1)) {
                return true
            }
        }
        return false
    }

    /**
     * Check C (Active Workspace Filter):
     * When on the Home Screen (not Lock Screen), verifies that:
     * 1. An application window (TYPE_APPLICATION) belonging to the launcher is focused/active, AND
     * 2. The workspace is not obscured by an expanded TYPE_SYSTEM notification shade / Quick Settings panel.
     */
    fun isHomeWorkspaceUnobscured(
        windows: List<AccessibilityWindowInfo>?,
        activeRootPackageName: String?,
        allowedLauncherPackages: Set<String>,
        screenHeightPx: Int
    ): Boolean {
        // If activeRootPackageName is present and does not belong to an allowed launcher
        // (e.g. "com.android.systemui" when notification shade is expanded, or another foreground app), reject immediately.
        if (!activeRootPackageName.isNullOrEmpty() && activeRootPackageName !in allowedLauncherPackages) {
            return false
        }

        if (windows.isNullOrEmpty()) {
            // If window list isn't accessible, require activeRootPackageName to explicitly match the launcher.
            return !activeRootPackageName.isNullOrEmpty() && activeRootPackageName in allowedLauncherPackages
        }

        var hasActiveLauncherApplicationWindow = false
        for (window in windows) {
            when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val pkg = window.root?.packageName?.toString()
                    val isLauncherPkg = if (pkg != null) {
                        pkg in allowedLauncherPackages
                    } else {
                        !activeRootPackageName.isNullOrEmpty() && activeRootPackageName in allowedLauncherPackages
                    }
                    if ((window.isActive || window.isFocused) && isLauncherPkg) {
                        hasActiveLauncherApplicationWindow = true
                    } else if (window.isActive || window.isFocused) {
                        return false
                    }
                }
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    // Normal status bar and nav bar are TYPE_SYSTEM windows with small heights and not focused.
                    // If a TYPE_SYSTEM window is focused/active OR expands over > 35% of screen height,
                    // the notification shade / Quick Settings / volume / power dialog is obscuring the Home Screen.
                    if (window.isFocused || window.isActive) {
                        return false
                    }
                    if (screenHeightPx > 0) {
                        val rect = Rect()
                        window.getBoundsInScreen(rect)
                        if (rectHeight(rect) > (screenHeightPx * 0.35f).toInt()) {
                            return false
                        }
                    }
                }
            }
        }

        return hasActiveLauncherApplicationWindow
    }

    fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    private fun rectWidth(rect: Rect): Int = rect.right - rect.left

    private fun rectHeight(rect: Rect): Int = rect.bottom - rect.top

    private fun isRectEmpty(rect: Rect): Boolean = rect.left >= rect.right || rect.top >= rect.bottom

    private fun rectContains(rect: Rect, x: Int, y: Int): Boolean {
        return !isRectEmpty(rect) &&
            x >= rect.left && x < rect.right &&
            y >= rect.top && y < rect.bottom
    }
}


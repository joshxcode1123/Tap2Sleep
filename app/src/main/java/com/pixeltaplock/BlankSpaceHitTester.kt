package com.pixeltaplock

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
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

/**
 * Abstract representation of an AccessibilityWindowInfo window for deterministic unit testing.
 */
data class WindowSnapshot(
    val type: Int,
    val isActive: Boolean = false,
    val isFocused: Boolean = false,
    val packageName: String? = null,
    val boundsInScreen: Rect = Rect(),
    val title: String? = null,
    val root: NodeSnapshot? = null
)

object BlankSpaceHitTester {

    const val EVENT_CORRELATION_DEBOUNCE_MS = 60L
    const val SCREEN_WAKE_TRANSITION_GRACE_MS = 600L
    const val MIN_DOUBLE_TAP_INTERVAL_MS = 16L
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    private val ALL_APPS_CLASS_PATTERNS = listOf(
        "AllAppsContainerView",
        "ActivityAllAppsContainerView",
        "LauncherAllAppsContainerView",
        "NexusLauncherAllAppsContainerView",
        "AllAppsRecyclerView",
        "SearchRecyclerView",
        "AppsContainerView",
        "AlphabeticalAppsList",
        "FloatingHeaderView",
        "AllAppsPagedView"
    )

    private val ALL_APPS_ID_PATTERNS = listOf(
        "all_apps",
        "apps_list_view",
        "all_apps_view",
        "all_apps_container",
        "all_apps_tabs_view_pager",
        "all_apps_header",
        "search_container_all_apps",
        "all_apps_content",
        "all_apps_rv_layout",
        "search_results_rv_layout",
        "apps_view",
        "all_apps_personal_work_tabs"
    )

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
        "shade_window"
    )

    private val OVERVIEW_CLASS_PATTERNS = listOf(
        "RecentsView",
        "OverviewPanel",
        "TaskView",
        "TaskThumbnailView",
        "OverviewActionsView",
        "ClearAllButton",
        "LauncherRecentsView",
        "FallbackRecentsView",
        "SplitPlaceholderView",
        "SplitInstructionsView",
        "DigitalWellBeingToast"
    )

    private val OVERVIEW_ID_PATTERNS = listOf(
        "overview_panel",
        "recents_view",
        "task_view",
        "task_thumbnail",
        "snapshot",
        "overview_actions_view",
        "clear_all",
        "clear_all_button",
        "action_screenshot",
        "action_split",
        "action_select"
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

    private val SYSTEM_UI_INTERRUPT_DIALOG_PATTERNS = listOf(
        "VolumeDialog",
        "GlobalActions",
        "Bouncer",
        "KeyguardBouncer",
        "BiometricPrompt",
        "AuthCredential",
        "QSPanel",
        "QuickQSPanel",
        "QSContainer"
    )

    private val LAUNCHER_TITLE_PATTERNS = listOf("launcher")
    private val OVERVIEW_TITLE_PATTERNS = listOf("launcher", "recents", "overview")

    private interface NodeInspector<N> {
        fun isVisibleToUser(node: N): Boolean
        fun boundsInScreen(node: N): Rect
        fun className(node: N): String?
        fun viewIdResourceName(node: N): String?
        fun contentDescription(node: N): String?
        fun packageName(node: N): String?
        fun isClickable(node: N): Boolean
        fun isLongClickable(node: N): Boolean
        fun isFocusable(node: N): Boolean
        fun isRoot(node: N, depth: Int): Boolean
        fun childCount(node: N): Int
        fun anyChild(node: N, predicate: (N) -> Boolean): Boolean
    }

    private object LiveNodeInspector : NodeInspector<AccessibilityNodeInfo> {
        override fun isVisibleToUser(node: AccessibilityNodeInfo): Boolean = node.isVisibleToUser

        override fun boundsInScreen(node: AccessibilityNodeInfo): Rect {
            return Rect().also { node.getBoundsInScreen(it) }
        }

        override fun className(node: AccessibilityNodeInfo): String? = node.className?.toString()

        override fun viewIdResourceName(node: AccessibilityNodeInfo): String? = node.viewIdResourceName

        override fun contentDescription(node: AccessibilityNodeInfo): String? =
            node.contentDescription?.toString()

        override fun packageName(node: AccessibilityNodeInfo): String? = node.packageName?.toString()

        override fun isClickable(node: AccessibilityNodeInfo): Boolean = node.isClickable

        override fun isLongClickable(node: AccessibilityNodeInfo): Boolean = node.isLongClickable

        override fun isFocusable(node: AccessibilityNodeInfo): Boolean = node.isFocusable

        override fun isRoot(node: AccessibilityNodeInfo, depth: Int): Boolean = depth == 0

        override fun childCount(node: AccessibilityNodeInfo): Int = node.childCount

        override fun anyChild(
            node: AccessibilityNodeInfo,
            predicate: (AccessibilityNodeInfo) -> Boolean
        ): Boolean {
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                if (predicate(child)) return true
            }
            return false
        }
    }

    private object SnapshotNodeInspector : NodeInspector<NodeSnapshot> {
        override fun isVisibleToUser(node: NodeSnapshot): Boolean = node.isVisibleToUser

        override fun boundsInScreen(node: NodeSnapshot): Rect = node.boundsInScreen

        override fun className(node: NodeSnapshot): String? = node.className

        override fun viewIdResourceName(node: NodeSnapshot): String? = node.viewIdResourceName

        override fun contentDescription(node: NodeSnapshot): String? = node.contentDescription

        override fun packageName(node: NodeSnapshot): String? = node.packageName

        override fun isClickable(node: NodeSnapshot): Boolean = node.isClickable

        override fun isLongClickable(node: NodeSnapshot): Boolean = node.isLongClickable

        override fun isFocusable(node: NodeSnapshot): Boolean = node.isFocusable

        override fun isRoot(node: NodeSnapshot, depth: Int): Boolean = node.isRoot

        override fun childCount(node: NodeSnapshot): Int = maxOf(node.childCount, node.children.size)

        override fun anyChild(
            node: NodeSnapshot,
            predicate: (NodeSnapshot) -> Boolean
        ): Boolean {
            for (child in node.children) {
                if (predicate(child)) return true
            }
            return false
        }
    }

    private interface WindowInspector<W, N> {
        fun type(window: W): Int
        fun root(window: W): N?
        fun packageName(window: W): String?
        fun title(window: W): String?
    }

    private object LiveWindowInspector : WindowInspector<AccessibilityWindowInfo, AccessibilityNodeInfo> {
        override fun type(window: AccessibilityWindowInfo): Int = window.type

        override fun root(window: AccessibilityWindowInfo): AccessibilityNodeInfo? =
            try {
                window.root
            } catch (_: Exception) {
                null
            }

        override fun packageName(window: AccessibilityWindowInfo): String? = null

        override fun title(window: AccessibilityWindowInfo): String? =
            try {
                window.title?.toString()
            } catch (_: Exception) {
                null
            }
    }

    private object SnapshotWindowInspector : WindowInspector<WindowSnapshot, NodeSnapshot> {
        override fun type(window: WindowSnapshot): Int = window.type

        override fun root(window: WindowSnapshot): NodeSnapshot? = window.root

        override fun packageName(window: WindowSnapshot): String? = window.packageName

        override fun title(window: WindowSnapshot): String? = window.title
    }

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
        if (deltaMs < MIN_DOUBLE_TAP_INTERVAL_MS || deltaMs > doubleTapTimeoutMs.toLong()) {
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
     * Evaluates whether an accessibility event represents an interactive user action
     * (click, long click, scroll, app launch, or notification shade transition) that
     * should invalidate a pending double-tap lock sequence.
     *
     * Window state changes for allowed launcher packages (e.g. launcher regaining focus
     * or settling upon returning Home) or PixelTapLock's own overlay windows are NOT
     * treated as interrupting interactive events.
     */
    fun isInterruptingAccessibilityEvent(
        eventType: Int,
        packageName: String?,
        className: String?,
        contentDescription: String?,
        ownPackageName: String,
        ownWidgetContentDescription: String?,
        allowedLauncherPackages: Set<String>
    ): Boolean {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val eventDesc = contentDescription
                ownWidgetContentDescription.isNullOrEmpty() || eventDesc != ownWidgetContentDescription
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = packageName
                val cls = className.orEmpty()
                if (pkg == ownPackageName || cls.contains("PixelTapLock")) {
                    false
                } else if (isOverviewClassOrId(cls, cls)) {
                    true
                } else if (!pkg.isNullOrEmpty()) {
                    pkg !in allowedLauncherPackages
                } else {
                    !cls.contains("launcher", ignoreCase = true)
                }
            }
            else -> false
        }
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
        if (rootNode == null || rawX <= 0f || rawY <= 0f) return false
        return checkInteractiveNodeRecursive(
            inspector = LiveNodeInspector,
            node = rootNode,
            x = rawX.toInt(),
            y = rawY.toInt(),
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            ownWidgetContentDescription = ownWidgetContentDescription,
            depth = 0
        )
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
        return checkInteractiveNodeRecursive(
            inspector = SnapshotNodeInspector,
            node = root,
            x = rawX.toInt(),
            y = rawY.toInt(),
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            ownWidgetContentDescription = ownWidgetContentDescription,
            depth = 0
        )
    }

    private fun <N> checkInteractiveNodeRecursive(
        inspector: NodeInspector<N>,
        node: N,
        x: Int,
        y: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        ownWidgetContentDescription: String?,
        depth: Int
    ): Boolean {
        if (depth > 40 || !inspector.isVisibleToUser(node)) return false

        val bounds = inspector.boundsInScreen(node)
        if (isRectEmpty(bounds) || !rectContains(bounds, x, y)) {
            return false
        }

        val contentDesc = inspector.contentDescription(node)
        if (!ownWidgetContentDescription.isNullOrEmpty() && contentDesc == ownWidgetContentDescription) {
            // Tapping the user's intentional BlankSpaceWidget is allowed.
            return false
        }

        // First inspect children (depth-first) to see if a specific leaf/child hits (x, y)
        if (inspector.anyChild(node) { child ->
                checkInteractiveNodeRecursive(
                    inspector = inspector,
                    node = child,
                    x = x,
                    y = y,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    ownWidgetContentDescription = ownWidgetContentDescription,
                    depth = depth + 1
                )
            }
        ) {
            return true
        }

        val rawCls = inspector.className(node)
        val rawViewId = inspector.viewIdResourceName(node)
        val cls = rawCls.orEmpty()
        val viewId = rawViewId.orEmpty().lowercase()
        // Any All Apps drawer container or Overview/Recents container or element is an interactive app browser area, never blank space
        if (isAllAppsClassOrId(cls, viewId) || isOverviewClassOrId(cls, viewId)) {
            return true
        }

        // Evaluate whether this node itself is an interactive element vs a root/workspace container
        val isInteractiveFlag = inspector.isClickable(node) ||
            inspector.isLongClickable(node) ||
            inspector.isFocusable(node)
        if (!isInteractiveFlag) {
            return false
        }

        return !isExcludedWorkspaceContainer(
            isRoot = inspector.isRoot(node, depth),
            bounds = bounds,
            className = rawCls,
            viewIdResourceName = rawViewId,
            childCount = inspector.childCount(node),
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            packageName = inspector.packageName(node)
        )
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
        // or All Apps app drawer containers or Overview/Recents containers as blank workspace containers
        if (NON_BLANK_CONTAINER_ID_PATTERNS.any {
                viewId.contains(it) || clsLower.contains(it) || pkgLower.contains(it)
            } ||
            isAllAppsClassOrId(cls, viewId) ||
            isOverviewClassOrId(cls, viewId)
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
        return matchesGenericContainerClass && widthRatio >= 0.75f && heightRatio >= 0.45f
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
        return isLockScreenSnapshotUnobscured(
            windows = toWindowSnapshots(windows, includeTitle = false),
            activeRootPackageName = activeRootPackageName,
            allowedLauncherPackages = allowedLauncherPackages,
            screenHeightPx = screenHeightPx
        )
    }

    /**
     * Same Lock Screen validation over [WindowSnapshot] for deterministic unit testing.
     */
    fun isLockScreenSnapshotUnobscured(
        windows: List<WindowSnapshot>?,
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
                    val pkg = window.root?.packageName ?: window.packageName
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
                    if (isImeWindowObscuring(window, screenHeightPx)) {
                        return false
                    }
                }
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    val pkg = window.root?.packageName ?: window.packageName
                    val isSystemUiPkg = pkg == null || pkg == SYSTEM_UI_PACKAGE
                    if ((window.isActive || window.isFocused) && isSystemUiPkg) {
                        if (screenHeightPx <= 0 ||
                            rectHeight(window.boundsInScreen) >= (screenHeightPx * 0.50f).toInt()
                        ) {
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
        return checkBouncerRecursive(LiveNodeInspector, rootNode, depth = 0)
    }

    /**
     * Same bouncer detection over [NodeSnapshot] for deterministic unit testing.
     */
    fun isLockScreenBouncerSnapshotShowing(root: NodeSnapshot?): Boolean {
        return checkBouncerRecursive(SnapshotNodeInspector, root, depth = 0)
    }

    private fun <N> checkBouncerRecursive(
        inspector: NodeInspector<N>,
        node: N?,
        depth: Int
    ): Boolean {
        if (node == null || depth > 35 || !inspector.isVisibleToUser(node)) return false

        val cls = inspector.className(node).orEmpty()
        val viewId = inspector.viewIdResourceName(node).orEmpty().lowercase()

        if (BOUNCER_CLASS_PATTERNS.any { cls.contains(it, ignoreCase = true) } ||
            BOUNCER_ID_PATTERNS.any { viewId.contains(it) }
        ) {
            return true
        }

        return inspector.anyChild(node) { child ->
            checkBouncerRecursive(inspector, child, depth + 1)
        }
    }

    /**
     * Returns true when a SystemUI TYPE_WINDOW_STATE_CHANGED event represents an active dialog,
     * volume slider, power menu, biometric prompt, bouncer, or expanded Quick Settings panel
     * (while preserving benign status bar / unlock window transitions).
     */
    fun isSystemUiDialogOrQsEvent(
        className: String?,
        contentDescription: String?
    ): Boolean {
        val cls = className.orEmpty()
        val desc = contentDescription.orEmpty()
        if (SYSTEM_UI_INTERRUPT_DIALOG_PATTERNS.any { cls.contains(it, ignoreCase = true) }) {
            return true
        }
        if (BOUNCER_CLASS_PATTERNS.any { cls.contains(it, ignoreCase = true) }) {
            return true
        }
        return desc.contains("Quick settings", ignoreCase = true)
    }

    /**
     * Returns true if the Quick Settings panel is visibly expanded over the Lock Screen.
     */
    fun isLockScreenQuickSettingsExpanded(rootNode: AccessibilityNodeInfo?): Boolean {
        return checkQuickSettingsExpanded(LiveNodeInspector, rootNode)
    }

    /**
     * Same Quick Settings expansion detection over [NodeSnapshot] for deterministic unit testing.
     */
    fun isLockScreenQuickSettingsSnapshotExpanded(root: NodeSnapshot?): Boolean {
        return checkQuickSettingsExpanded(SnapshotNodeInspector, root)
    }

    private fun <N> checkQuickSettingsExpanded(inspector: NodeInspector<N>, root: N?): Boolean {
        if (root == null) return false
        var visibleQsTileCount = 0
        fun traverse(node: N, depth: Int): Boolean {
            if (depth > 30 || !inspector.isVisibleToUser(node)) return false
            val cls = inspector.className(node).orEmpty()
            val viewId = inspector.viewIdResourceName(node).orEmpty().lowercase()
            if (cls.contains("QSTileView", ignoreCase = true) || viewId.contains("qs_tile")) {
                if (!isRectEmpty(inspector.boundsInScreen(node))) {
                    visibleQsTileCount++
                    if (visibleQsTileCount >= 4) return true
                }
            }
            if (cls.contains("QSPanel", ignoreCase = true) ||
                cls.contains("QuickQSPanel", ignoreCase = true) ||
                viewId.contains("quick_settings_panel") ||
                viewId.contains("quick_qs_panel") ||
                viewId.contains("qs_customize")
            ) {
                if (!isRectEmpty(inspector.boundsInScreen(node))) return true
            }
            return inspector.anyChild(node) { child -> traverse(child, depth + 1) }
        }
        return traverse(root, 0)
    }

    /**
     * Returns true if the Launcher's All Apps drawer (app tray) is currently open and visible
     * to the user, ensuring rapid flicks or browsing in the app drawer are never registered
     * as Home Screen blank-space double taps.
     */
    fun isAllAppsDrawerShowing(rootNode: AccessibilityNodeInfo?, screenHeightPx: Int = 0): Boolean {
        return anyVisibleNodeMatches(LiveNodeInspector, rootNode) { cls, viewId, bounds ->
            isAllAppsNodeMatch(cls, viewId, bounds, screenHeightPx)
        }
    }

    /**
     * Same All Apps drawer detection over [NodeSnapshot] for deterministic unit testing.
     */
    fun isAllAppsDrawerSnapshotShowing(root: NodeSnapshot?, screenHeightPx: Int = 0): Boolean {
        return anyVisibleNodeMatches(SnapshotNodeInspector, root) { cls, viewId, bounds ->
            isAllAppsNodeMatch(cls, viewId, bounds, screenHeightPx)
        }
    }

    private fun isAllAppsNodeMatch(
        className: String?,
        viewIdResourceName: String?,
        bounds: Rect,
        screenHeightPx: Int
    ): Boolean {
        val cls = className.orEmpty()
        val viewId = viewIdResourceName.orEmpty().lowercase()
        if (!isAllAppsClassOrId(cls, viewId)) return false
        if (isRectEmpty(bounds)) return false
        return hasMinimumVisibleSpan(bounds.top, bounds.bottom, screenHeightPx)
    }

    /**
     * Check C (Launcher App Drawer Filter):
     * Verifies across active and application windows whether the Launcher's All Apps drawer is showing.
     * Even if [activeRootNode] currently belongs to SystemUI (e.g. transparent status bar or overlay window
     * with focus), this inspects the launcher application window in [windows] to prevent false locks.
     */
    fun isAllAppsDrawerShowingInWindows(
        windows: List<AccessibilityWindowInfo>?,
        activeRootNode: AccessibilityNodeInfo?,
        allowedLauncherPackages: Set<String>,
        screenHeightPx: Int
    ): Boolean {
        return isMatchingOverlayInWindows(
            windowInspector = LiveWindowInspector,
            nodeInspector = LiveNodeInspector,
            windows = windows,
            activeRootNode = activeRootNode,
            allowedLauncherPackages = allowedLauncherPackages,
            includeSystemUiPackage = false,
            extraTitlePatterns = LAUNCHER_TITLE_PATTERNS
        ) { root -> isAllAppsDrawerShowing(root, screenHeightPx) }
    }

    /**
     * Same window-aware All Apps drawer detection over snapshots for deterministic unit testing.
     */
    fun isAllAppsDrawerShowingInSnapshotWindows(
        windows: List<WindowSnapshot>?,
        activeRootNode: NodeSnapshot?,
        allowedLauncherPackages: Set<String>,
        screenHeightPx: Int
    ): Boolean {
        return isMatchingOverlayInWindows(
            windowInspector = SnapshotWindowInspector,
            nodeInspector = SnapshotNodeInspector,
            windows = windows,
            activeRootNode = activeRootNode,
            allowedLauncherPackages = allowedLauncherPackages,
            includeSystemUiPackage = false,
            extraTitlePatterns = LAUNCHER_TITLE_PATTERNS
        ) { root -> isAllAppsDrawerSnapshotShowing(root, screenHeightPx) }
    }

    /**
     * Helper to determine whether a given class name or viewId matches Overview / Recents.
     */
    fun isOverviewClassOrId(className: String?, viewIdResourceName: String? = null): Boolean {
        val cls = className.orEmpty()
        val viewId = viewIdResourceName.orEmpty().lowercase()
        return OVERVIEW_CLASS_PATTERNS.any { cls.contains(it, ignoreCase = true) } ||
            (viewId.isNotEmpty() && OVERVIEW_ID_PATTERNS.any { viewId.contains(it) })
    }

    /**
     * Returns true if the Overview / Recents task switcher screen (suspended apps page) is currently open and visible
     * to the user, ensuring taps while viewing suspended apps or dismissing the view are never registered
     * as Home Screen blank-space double taps.
     */
    fun isOverviewShowing(
        rootNode: AccessibilityNodeInfo?,
        screenWidthPx: Int = 0,
        screenHeightPx: Int = 0
    ): Boolean {
        return anyVisibleNodeMatches(LiveNodeInspector, rootNode) { cls, viewId, bounds ->
            isOverviewNodeMatch(cls, viewId, bounds, screenWidthPx, screenHeightPx)
        }
    }

    /**
     * Same Overview / Recents detection over [NodeSnapshot] for deterministic unit testing.
     */
    fun isOverviewSnapshotShowing(
        root: NodeSnapshot?,
        screenWidthPx: Int = 0,
        screenHeightPx: Int = 0
    ): Boolean {
        return anyVisibleNodeMatches(SnapshotNodeInspector, root) { cls, viewId, bounds ->
            isOverviewNodeMatch(cls, viewId, bounds, screenWidthPx, screenHeightPx)
        }
    }

    private fun isOverviewNodeMatch(
        className: String?,
        viewIdResourceName: String?,
        bounds: Rect,
        screenWidthPx: Int = 0,
        screenHeightPx: Int = 0
    ): Boolean {
        if (!isOverviewClassOrId(className, viewIdResourceName)) return false
        if (isRectEmpty(bounds)) return false
        return hasMinimumVisibleSpan(bounds.left, bounds.right, screenWidthPx) &&
            hasMinimumVisibleSpan(bounds.top, bounds.bottom, screenHeightPx)
    }

    /**
     * Check C (Overview / Recents Task Switcher Filter):
     * Verifies across active and application windows whether the Overview / Recents screen is showing.
     * Even if [activeRootNode] currently belongs to SystemUI (e.g. transparent status bar or overlay window
     * with focus), this inspects the launcher and system windows to prevent false locks.
     */
    fun isOverviewShowingInWindows(
        windows: List<AccessibilityWindowInfo>?,
        activeRootNode: AccessibilityNodeInfo?,
        allowedLauncherPackages: Set<String>,
        screenWidthPx: Int = 0,
        screenHeightPx: Int = 0
    ): Boolean {
        return isMatchingOverlayInWindows(
            windowInspector = LiveWindowInspector,
            nodeInspector = LiveNodeInspector,
            windows = windows,
            activeRootNode = activeRootNode,
            allowedLauncherPackages = allowedLauncherPackages,
            includeSystemUiPackage = true,
            extraTitlePatterns = OVERVIEW_TITLE_PATTERNS
        ) { root -> isOverviewShowing(root, screenWidthPx, screenHeightPx) }
    }

    /**
     * Same window-aware Overview / Recents detection over snapshots for deterministic unit testing.
     */
    fun isOverviewShowingInSnapshotWindows(
        windows: List<WindowSnapshot>?,
        activeRootNode: NodeSnapshot?,
        allowedLauncherPackages: Set<String>,
        screenWidthPx: Int = 0,
        screenHeightPx: Int = 0
    ): Boolean {
        return isMatchingOverlayInWindows(
            windowInspector = SnapshotWindowInspector,
            nodeInspector = SnapshotNodeInspector,
            windows = windows,
            activeRootNode = activeRootNode,
            allowedLauncherPackages = allowedLauncherPackages,
            includeSystemUiPackage = true,
            extraTitlePatterns = OVERVIEW_TITLE_PATTERNS
        ) { root -> isOverviewSnapshotShowing(root, screenWidthPx, screenHeightPx) }
    }

    private fun <N> anyVisibleNodeMatches(
        inspector: NodeInspector<N>,
        node: N?,
        depth: Int = 0,
        match: (className: String?, viewId: String?, bounds: Rect) -> Boolean
    ): Boolean {
        if (node == null || depth > 35 || !inspector.isVisibleToUser(node)) return false
        if (match(inspector.className(node), inspector.viewIdResourceName(node), inspector.boundsInScreen(node))) {
            return true
        }
        return inspector.anyChild(node) { child ->
            anyVisibleNodeMatches(inspector, child, depth + 1, match)
        }
    }

    private fun <W, N> isMatchingOverlayInWindows(
        windowInspector: WindowInspector<W, N>,
        nodeInspector: NodeInspector<N>,
        windows: List<W>?,
        activeRootNode: N?,
        allowedLauncherPackages: Set<String>,
        includeSystemUiPackage: Boolean,
        extraTitlePatterns: List<String>,
        treeMatches: (N) -> Boolean
    ): Boolean {
        fun isAllowedPackage(pkg: String?): Boolean {
            return !pkg.isNullOrEmpty() &&
                (pkg in allowedLauncherPackages || (includeSystemUiPackage && pkg == SYSTEM_UI_PACKAGE))
        }

        val activePkg = activeRootNode?.let { nodeInspector.packageName(it) }
        if (activeRootNode != null && isAllowedPackage(activePkg)) {
            if (treeMatches(activeRootNode)) {
                return true
            }
        }

        if (!windows.isNullOrEmpty()) {
            for (window in windows) {
                val type = windowInspector.type(window)
                val typeAllowed = type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (includeSystemUiPackage && type == AccessibilityWindowInfo.TYPE_SYSTEM)
                if (!typeAllowed) continue

                val root = windowInspector.root(window) ?: continue
                val pkg = nodeInspector.packageName(root) ?: windowInspector.packageName(window)
                val isCandidate = isAllowedPackage(pkg) ||
                    matchesWindowTitle(windowInspector.title(window), allowedLauncherPackages, extraTitlePatterns) ||
                    isAllowedPackage(activePkg)
                if (isCandidate && treeMatches(root)) {
                    return true
                }
            }
        }

        if (activeRootNode != null && activePkg == null) {
            if (treeMatches(activeRootNode)) {
                return true
            }
        }

        return false
    }

    /**
     * Normalizes SystemUI focus during Home Screen transitions: when [activeRootPackageName] is
     * [SYSTEM_UI_PACKAGE] and no obscuring `TYPE_SYSTEM` window (focused/active or >35% screen height)
     * is present, returns `null` so a benign status/nav bar root does not mask the Home workspace.
     */
    fun resolveEffectiveHomeActiveRootPackage(
        activeRootPackageName: String?,
        windows: List<WindowSnapshot>?,
        screenHeightPx: Int
    ): String? {
        if (activeRootPackageName != SYSTEM_UI_PACKAGE) return activeRootPackageName
        val hasObscuringShade = screenHeightPx > 0 &&
            (windows?.any { isObscuringSystemWindow(it, screenHeightPx) } ?: false)
        return if (hasObscuringShade) activeRootPackageName else null
    }

    /**
     * Check C (Active Workspace Filter):
     * When on the Home Screen (not Lock Screen), verifies that:
     * 1. An application window (TYPE_APPLICATION) belonging to the launcher is focused/active, OR
     *    when [allowTransitionCandidate] is true, the launcher window is present and visible while
     *    no non-launcher application window is active/focused and no shade is obscuring the screen.
     * 2. The workspace is not obscured by an expanded TYPE_SYSTEM notification shade / Quick Settings panel.
     */
    fun isHomeWorkspaceUnobscured(
        windows: List<AccessibilityWindowInfo>?,
        activeRootPackageName: String?,
        allowedLauncherPackages: Set<String>,
        screenHeightPx: Int,
        allowTransitionCandidate: Boolean = false
    ): Boolean {
        if (!activeRootPackageName.isNullOrEmpty() && activeRootPackageName !in allowedLauncherPackages) {
            return false
        }
        return isHomeWorkspaceSnapshotUnobscured(
            windows = toWindowSnapshots(windows, includeTitle = true),
            activeRootPackageName = activeRootPackageName,
            allowedLauncherPackages = allowedLauncherPackages,
            screenHeightPx = screenHeightPx,
            allowTransitionCandidate = allowTransitionCandidate
        )
    }

    /**
     * Same active workspace validation over [WindowSnapshot] for deterministic unit testing.
     */
    fun isHomeWorkspaceSnapshotUnobscured(
        windows: List<WindowSnapshot>?,
        activeRootPackageName: String?,
        allowedLauncherPackages: Set<String>,
        screenHeightPx: Int,
        allowTransitionCandidate: Boolean = false
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
        var hasVisibleLauncherWindow = false
        var hasActiveNonLauncherApp = false
        var hasObscuringSystemWindow = false

        for (window in windows) {
            when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val pkg = window.packageName
                    val isLauncherPkg = if (!pkg.isNullOrEmpty()) {
                        pkg in allowedLauncherPackages
                    } else if (matchesWindowTitle(window.title, allowedLauncherPackages, LAUNCHER_TITLE_PATTERNS)) {
                        true
                    } else {
                        !activeRootPackageName.isNullOrEmpty() && activeRootPackageName in allowedLauncherPackages
                    }

                    if (isLauncherPkg) {
                        val isVisible = if (screenHeightPx > 0) {
                            rectHeight(window.boundsInScreen) >= (screenHeightPx * 0.50f).toInt() &&
                                rectWidth(window.boundsInScreen) > 0
                        } else {
                            rectHeight(window.boundsInScreen) > 0 && rectWidth(window.boundsInScreen) > 0
                        }
                        if (isVisible) {
                            hasVisibleLauncherWindow = true
                        }
                        if (window.isActive || window.isFocused) {
                            hasActiveLauncherApplicationWindow = true
                        }
                    } else if (window.isActive || window.isFocused) {
                        hasActiveNonLauncherApp = true
                    }
                }
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                    // An active or visible on-screen keyboard (IME) obscures the workspace.
                    if (isImeWindowObscuring(window, screenHeightPx)) {
                        return false
                    }
                }
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    // Normal status bar and nav bar are TYPE_SYSTEM windows with small heights and not focused.
                    // If a TYPE_SYSTEM window is focused/active OR expands over > 35% of screen height,
                    // the notification shade / Quick Settings / volume / power dialog is obscuring the Home Screen.
                    if (isObscuringSystemWindow(window, screenHeightPx)) {
                        hasObscuringSystemWindow = true
                    }
                }
            }
        }

        if (hasObscuringSystemWindow || hasActiveNonLauncherApp) {
            return false
        }

        return if (allowTransitionCandidate) {
            hasActiveLauncherApplicationWindow || hasVisibleLauncherWindow
        } else {
            hasActiveLauncherApplicationWindow
        }
    }

    internal fun toWindowSnapshots(
        windows: List<AccessibilityWindowInfo>?,
        includeTitle: Boolean = true
    ): List<WindowSnapshot>? {
        if (windows == null) return null
        val snapshots = ArrayList<WindowSnapshot>(windows.size)
        for (window in windows) {
            val bounds = Rect()
            try {
                window.getBoundsInScreen(bounds)
            } catch (_: Exception) {
            }
            val titleStr = if (includeTitle) {
                try {
                    window.title?.toString()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val pkg = try {
                window.root?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
            snapshots.add(
                WindowSnapshot(
                    type = window.type,
                    isActive = window.isActive,
                    isFocused = window.isFocused,
                    packageName = pkg,
                    boundsInScreen = bounds,
                    title = titleStr
                )
            )
        }
        return snapshots
    }

    fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    private fun isAllAppsClassOrId(cls: String, viewIdLower: String): Boolean {
        return ALL_APPS_CLASS_PATTERNS.any { cls.contains(it, ignoreCase = true) } ||
            ALL_APPS_ID_PATTERNS.any { viewIdLower.contains(it) }
    }

    private fun matchesWindowTitle(
        titleStr: String?,
        allowedLauncherPackages: Set<String>,
        extraPatterns: List<String>
    ): Boolean {
        if (titleStr.isNullOrEmpty()) return false
        return allowedLauncherPackages.any { titleStr.contains(it, ignoreCase = true) } ||
            extraPatterns.any { titleStr.contains(it, ignoreCase = true) }
    }

    private fun hasMinimumVisibleSpan(start: Int, end: Int, screenSizePx: Int): Boolean {
        if (screenSizePx <= 0) return true
        if (start >= screenSizePx || end <= 0) return false
        val visibleSpan = maxOf(0, minOf(end, screenSizePx) - maxOf(start, 0))
        return visibleSpan >= (screenSizePx * 0.20f).toInt()
    }

    private fun isImeWindowObscuring(window: WindowSnapshot, screenHeightPx: Int): Boolean {
        return window.isActive ||
            window.isFocused ||
            (screenHeightPx > 0 && rectHeight(window.boundsInScreen) > 0)
    }

    private fun isObscuringSystemWindow(window: WindowSnapshot, screenHeightPx: Int): Boolean {
        if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return false
        return window.isFocused ||
            window.isActive ||
            (screenHeightPx > 0 && rectHeight(window.boundsInScreen) > (screenHeightPx * 0.35f).toInt())
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


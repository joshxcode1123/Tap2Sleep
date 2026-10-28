package com.pixeltaplock

import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelTapLockUnitTest {

    private val qhdScreenConfig = ScreenBoundsConfig(
        screenWidthPx = 1344,
        screenHeightPx = 2992,
        topIgnoreInsetPx = 144,
        bottomIgnoreInsetPx = 126,
        doubleTapSlopPx = 100
    )

    @Test
    fun `flicker overlay geometry oversizes Full QHD+ 1344x2992 and 1440x3120 by 200px with negative 100px offsets`() {
        val qhdPixel8Pro = FlickerProtectionOverlayController.calculateOversizedOverlayGeometry(
            maxBoundsWidth = 1344,
            maxBoundsHeight = 2992,
            safetyMarginPx = 200
        )
        assertEquals(1544, qhdPixel8Pro.widthPx)
        assertEquals(3192, qhdPixel8Pro.heightPx)
        assertEquals(-100, qhdPixel8Pro.offsetXPx)
        assertEquals(-100, qhdPixel8Pro.offsetYPx)

        val qhdPixel7Pro = FlickerProtectionOverlayController.calculateOversizedOverlayGeometry(
            maxBoundsWidth = 1440,
            maxBoundsHeight = 3120,
            safetyMarginPx = 200
        )
        assertEquals(1640, qhdPixel7Pro.widthPx)
        assertEquals(3320, qhdPixel7Pro.heightPx)
        assertEquals(-100, qhdPixel7Pro.offsetXPx)
        assertEquals(-100, qhdPixel7Pro.offsetYPx)
    }

    @Test
    fun `display state helper detects STATE_OFF, STATE_DOZE, and STATE_DOZE_SUSPEND for AOD compatibility`() {
        assertTrue(FlickerProtectionOverlayController.isDisplayOffOrDozing(Display.STATE_OFF))
        assertTrue(FlickerProtectionOverlayController.isDisplayOffOrDozing(Display.STATE_DOZE))
        assertTrue(FlickerProtectionOverlayController.isDisplayOffOrDozing(Display.STATE_DOZE_SUSPEND))
        assertFalse(FlickerProtectionOverlayController.isDisplayOffOrDozing(Display.STATE_ON))
        assertFalse(FlickerProtectionOverlayController.isDisplayOffOrDozing(Display.STATE_VR))
    }

    @Test
    fun `edge ignore zone rejects top status bar and bottom gesture navigation bar taps`() {
        // Inside top status bar (< 144px)
        assertTrue(BlankSpaceHitTester.isWithinEdgeIgnoreZone(600f, 50f, qhdScreenConfig))
        assertTrue(BlankSpaceHitTester.isWithinEdgeIgnoreZone(600f, 143.9f, qhdScreenConfig))

        // Safe middle workspace area
        assertFalse(BlankSpaceHitTester.isWithinEdgeIgnoreZone(600f, 144f, qhdScreenConfig))
        assertFalse(BlankSpaceHitTester.isWithinEdgeIgnoreZone(600f, 1500f, qhdScreenConfig))
        assertFalse(BlankSpaceHitTester.isWithinEdgeIgnoreZone(600f, 2866f, qhdScreenConfig))

        // Inside bottom gesture navigation bar (> 2992 - 126 = 2866)
        assertTrue(BlankSpaceHitTester.isWithinEdgeIgnoreZone(600f, 2867f, qhdScreenConfig))
        assertTrue(BlankSpaceHitTester.isWithinEdgeIgnoreZone(600f, 2980f, qhdScreenConfig))

        // Masked (0, 0) coordinates on Android 15-17 ACTION_OUTSIDE must NOT be rejected by edge check
        assertFalse(BlankSpaceHitTester.isWithinEdgeIgnoreZone(0f, 0f, qhdScreenConfig))
    }

    @Test
    fun `double tap pair checks timeout bounds and spatial slop for valid and masked coordinates`() {
        // Valid coordinates within 300ms and within 100px slop
        val tap1 = TapSample(rawX = 500f, rawY = 1200f, uptimeMillis = 1000L)
        val tap2 = TapSample(rawX = 540f, rawY = 1230f, uptimeMillis = 1220L)
        assertTrue(BlankSpaceHitTester.isDoubleTapPair(tap1, tap2, 300, qhdScreenConfig))

        // Too slow (> 300ms)
        val slowTap2 = TapSample(rawX = 510f, rawY = 1210f, uptimeMillis = 1350L)
        assertFalse(BlankSpaceHitTester.isDoubleTapPair(tap1, slowTap2, 300, qhdScreenConfig))

        // Too fast / hardware bounce (<= 15ms)
        val bounceTap2 = TapSample(rawX = 500f, rawY = 1200f, uptimeMillis = 1010L)
        assertFalse(BlankSpaceHitTester.isDoubleTapPair(tap1, bounceTap2, 300, qhdScreenConfig))

        // Too far apart (> 100px slop)
        val farTap2 = TapSample(rawX = 750f, rawY = 1500f, uptimeMillis = 1200L)
        assertFalse(BlankSpaceHitTester.isDoubleTapPair(tap1, farTap2, 300, qhdScreenConfig))

        // Masked (0, 0) coordinates on Android 15-17 ACTION_OUTSIDE pass when within timeout
        val maskedTap1 = TapSample(rawX = 0f, rawY = 0f, uptimeMillis = 2000L)
        val maskedTap2 = TapSample(rawX = 0f, rawY = 0f, uptimeMillis = 2180L)
        assertFalse(maskedTap1.hasValidCoordinates)
        assertTrue(BlankSpaceHitTester.isDoubleTapPair(maskedTap1, maskedTap2, 300, qhdScreenConfig))
    }

    @Test
    fun `Check A interactive node hit-test excludes full-screen workspace container but rejects icons, widgets, and notifications`() {
        val appIconNode = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(100, 400, 300, 600),
            className = "com.android.launcher3.BubbleTextView",
            viewIdResourceName = "com.google.android.apps.nexuslauncher:id/icon",
            contentDescription = "Messages",
            isVisibleToUser = true,
            isClickable = true,
            isLongClickable = true,
            isFocusable = true,
            isRoot = false,
            childCount = 0
        )

        val weatherWidgetNode = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(100, 700, 1200, 1100),
            className = "com.android.launcher3.widget.LauncherAppWidgetHostView",
            viewIdResourceName = "com.google.android.apps.nexuslauncher:id/widget_container",
            contentDescription = "Weather",
            isVisibleToUser = true,
            isClickable = true,
            isLongClickable = true,
            isFocusable = false,
            isRoot = false,
            childCount = 1
        )

        val ownBlankWidgetNode = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(100, 1800, 600, 2200),
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.pixeltaplock:id/widget_root",
            contentDescription = "Double-tap empty widget area to lock screen",
            isVisibleToUser = true,
            isClickable = true,
            isLongClickable = false,
            isFocusable = true,
            isRoot = false,
            childCount = 0
        )

        val workspaceNode = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992),
            className = "com.android.launcher3.Workspace",
            viewIdResourceName = "com.google.android.apps.nexuslauncher:id/workspace",
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = false,
            isLongClickable = true, // Pixel Launcher workspace is long-clickable for Wallpaper settings
            isFocusable = true,
            isRoot = false,
            childCount = 3,
            children = listOf(appIconNode, weatherWidgetNode, ownBlankWidgetNode)
        )

        val rootDragLayer = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992),
            className = "com.android.launcher3.dragndrop.DragLayer",
            viewIdResourceName = "com.google.android.apps.nexuslauncher:id/drag_layer",
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = false,
            isLongClickable = false,
            isFocusable = true,
            isRoot = true,
            childCount = 1,
            children = listOf(workspaceNode)
        )

        // 1. Tapping empty wallpaper at (700, 1500) -> does NOT hit any interactive child
        assertFalse(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = rootDragLayer,
                rawX = 700f,
                rawY = 1500f,
                screenWidthPx = 1344,
                screenHeightPx = 2992,
                ownWidgetContentDescription = "Double-tap empty widget area to lock screen"
            )
        )

        // 2. Tapping inside the Messages app icon at (200, 500) -> REJECTS (hits interactive child)
        assertTrue(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = rootDragLayer,
                rawX = 200f,
                rawY = 500f,
                screenWidthPx = 1344,
                screenHeightPx = 2992,
                ownWidgetContentDescription = "Double-tap empty widget area to lock screen"
            )
        )

        // 3. Tapping inside the Weather widget at (600, 900) -> REJECTS (hits LauncherAppWidgetHostView)
        assertTrue(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = rootDragLayer,
                rawX = 600f,
                rawY = 900f,
                screenWidthPx = 1344,
                screenHeightPx = 2992,
                ownWidgetContentDescription = "Double-tap empty widget area to lock screen"
            )
        )

        // 4. Tapping inside our own BlankSpaceWidget at (300, 2000) -> ALLOWED
        assertFalse(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = rootDragLayer,
                rawX = 300f,
                rawY = 2000f,
                screenWidthPx = 1344,
                screenHeightPx = 2992,
                ownWidgetContentDescription = "Double-tap empty widget area to lock screen"
            )
        )
    }

    @Test
    fun `Check B event-correlation debounce cancels lock if click, scroll, or window change occurs between Tap 1 and Tap 2 + 60ms`() {
        val tap1Time = 5000L
        val tap2Plus60ms = 5260L

        // Interactive event occurred well before Tap 1 -> NOT interrupted
        assertFalse(
            BlankSpaceHitTester.wasInterruptedByInteractiveEvent(
                tap1UptimeMillis = tap1Time,
                debounceEndUptimeMillis = tap2Plus60ms,
                lastInteractiveEventUptimeMillis = 4800L
            )
        )

        // Interactive event (e.g. pressing Home button) occurred 5ms BEFORE Tap 1 -> NOT interrupted
        assertFalse(
            BlankSpaceHitTester.wasInterruptedByInteractiveEvent(
                tap1UptimeMillis = tap1Time,
                debounceEndUptimeMillis = tap2Plus60ms,
                lastInteractiveEventUptimeMillis = 4995L
            )
        )

        // Interactive event occurred between Tap 1 and Tap 2 -> INTERRUPTED
        assertTrue(
            BlankSpaceHitTester.wasInterruptedByInteractiveEvent(
                tap1UptimeMillis = tap1Time,
                debounceEndUptimeMillis = tap2Plus60ms,
                lastInteractiveEventUptimeMillis = 5120L
            )
        )

        // Interactive event occurred during the 60ms post-Tap-2 debounce window -> INTERRUPTED
        assertTrue(
            BlankSpaceHitTester.wasInterruptedByInteractiveEvent(
                tap1UptimeMillis = tap1Time,
                debounceEndUptimeMillis = tap2Plus60ms,
                lastInteractiveEventUptimeMillis = 5245L
            )
        )
    }

    @Test
    fun `Check A allows blank space on empty Home Screen CellLayout page with zero children`() {
        val emptyCellLayout = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 144, 1344, 2500),
            className = "com.android.launcher3.CellLayout",
            viewIdResourceName = null, // Launcher3 dynamic CellLayout pages have null resource ID
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = false,
            isLongClickable = true,
            isFocusable = true,
            isRoot = false,
            childCount = 0,
            children = emptyList()
        )

        val workspaceNode = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992),
            className = "com.android.launcher3.Workspace",
            viewIdResourceName = "com.google.android.apps.nexuslauncher:id/workspace",
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = false,
            isLongClickable = true,
            isFocusable = true,
            isRoot = true,
            childCount = 1,
            children = listOf(emptyCellLayout)
        )

        assertFalse(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = workspaceNode,
                rawX = 672f,
                rawY = 1400f,
                screenWidthPx = 1344,
                screenHeightPx = 2992
            )
        )
    }

    @Test
    fun `Check A Lock Screen allows blank space inside NotificationStackScrollLayout but rejects notification cards, QS tiles, and shortcuts`() {
        val notificationCard = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(48, 1250, 1296, 1600),
            className = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
            viewIdResourceName = "com.android.systemui:id/expandableNotificationRow",
            contentDescription = "New message",
            isVisibleToUser = true,
            isClickable = true,
            isLongClickable = true,
            isFocusable = true,
            isRoot = false,
            childCount = 2
        )

        val qsTileNode = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(64, 300, 640, 480),
            className = "com.android.systemui.qs.tileimpl.QSTileViewImpl",
            viewIdResourceName = "com.android.systemui:id/qs_tile",
            contentDescription = "Internet",
            isVisibleToUser = true,
            isClickable = true,
            isLongClickable = true,
            isFocusable = true,
            isRoot = false,
            childCount = 1
        )

        val bottomLeftShortcut = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(64, 2650, 224, 2810),
            className = "android.widget.ImageView",
            viewIdResourceName = "com.android.systemui:id/start_button",
            contentDescription = "Flashlight",
            isVisibleToUser = true,
            isClickable = true,
            isLongClickable = true,
            isFocusable = true,
            isRoot = false,
            childCount = 0
        )

        // NotificationStackScrollLayout positioned below a large Pixel double-line clock (height ~46% of screen)
        val notificationStackScroller = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 1200, 1344, 2600),
            className = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
            viewIdResourceName = "com.android.systemui:id/notification_stack_scroller",
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = false,
            isLongClickable = true,
            isFocusable = true,
            isRoot = false,
            childCount = 1,
            children = listOf(notificationCard)
        )

        val keyguardRoot = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992),
            className = "com.android.systemui.shade.NotificationPanelView",
            viewIdResourceName = "com.android.systemui:id/notification_panel",
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = false,
            isLongClickable = true,
            isFocusable = true,
            isRoot = true,
            childCount = 3,
            children = listOf(qsTileNode, notificationStackScroller, bottomLeftShortcut)
        )

        // 1. Blank space inside NotificationStackScrollLayout below notificationCard (700, 2000) -> ALLOWED
        assertFalse(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = keyguardRoot,
                rawX = 700f,
                rawY = 2000f,
                screenWidthPx = 1344,
                screenHeightPx = 2992
            )
        )

        // 2. Inside Notification Card (700, 1400) -> REJECTED
        assertTrue(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = keyguardRoot,
                rawX = 700f,
                rawY = 1400f,
                screenWidthPx = 1344,
                screenHeightPx = 2992
            )
        )

        // 3. Inside Quick Settings Tile (300, 400) -> REJECTED
        assertTrue(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = keyguardRoot,
                rawX = 300f,
                rawY = 400f,
                screenWidthPx = 1344,
                screenHeightPx = 2992
            )
        )

        // 4. Inside Lock Screen Bottom Shortcut (140, 2730) -> REJECTED
        assertTrue(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = keyguardRoot,
                rawX = 140f,
                rawY = 2730f,
                screenWidthPx = 1344,
                screenHeightPx = 2992
            )
        )
    }

    @Test
    fun `Check C active workspace filter verifies active package matches launcher`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        assertTrue(
            BlankSpaceHitTester.isHomeWorkspaceUnobscured(
                windows = null,
                activeRootPackageName = "com.google.android.apps.nexuslauncher",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992
            )
        )

        // When notification shade is pulled down on Home Screen, active root package is com.android.systemui
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceUnobscured(
                windows = null,
                activeRootPackageName = "com.android.systemui",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992
            )
        )

        // Regular foreground app is rejected
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceUnobscured(
                windows = null,
                activeRootPackageName = "com.google.android.youtube",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992
            )
        )
    }

    @Test
    fun `settings model clamps out-of-range values to safe bounds`() {
        val defaultSettings = TapLockSettings()
        assertEquals(600, defaultSettings.clampedLockScreenWakeGraceMs)

        val extremeLow = TapLockSettings(
            doubleTapTimeoutMs = 20,
            lockScreenWakeGraceMs = -100,
            preLockBlackoutDelayMs = -50,
            postLockHoldDurationMs = 10
        )
        assertEquals(150, extremeLow.clampedDoubleTapTimeoutMs)
        assertEquals(0, extremeLow.clampedLockScreenWakeGraceMs)
        assertEquals(0, extremeLow.clampedPreLockBlackoutDelayMs)
        assertEquals(100, extremeLow.clampedPostLockHoldDurationMs)

        val extremeHigh = TapLockSettings(
            doubleTapTimeoutMs = 9999,
            lockScreenWakeGraceMs = 5000,
            preLockBlackoutDelayMs = 500,
            postLockHoldDurationMs = 5000
        )
        assertEquals(500, extremeHigh.clampedDoubleTapTimeoutMs)
        assertEquals(1500, extremeHigh.clampedLockScreenWakeGraceMs)
        assertEquals(100, extremeHigh.clampedPreLockBlackoutDelayMs)
        assertEquals(1000, extremeHigh.clampedPostLockHoldDurationMs)
    }

    @Test
    fun `widget double tap helper enforces bounce minimum and timeout window`() {
        assertFalse(BlankSpaceWidgetReceiver.isWidgetDoubleTap(0L, 1000L, 300))
        assertFalse(BlankSpaceWidgetReceiver.isWidgetDoubleTap(1000L, 1015L, 300))
        assertTrue(BlankSpaceWidgetReceiver.isWidgetDoubleTap(1000L, 1016L, 300))
        assertTrue(BlankSpaceWidgetReceiver.isWidgetDoubleTap(1000L, 1200L, 300))
        assertFalse(BlankSpaceWidgetReceiver.isWidgetDoubleTap(1000L, 1350L, 300))
    }

    @Test
    fun `initial hard safety timeout accommodates maximum post-lock hold duration plus screen-off transition`() {
        assertEquals(
            1200L,
            FlickerProtectionOverlayController.computeInitialHardSafetyTimeoutMs(
                clampedPreDelayMs = 24L,
                clampedPostHoldMs = 400L
            )
        )
        assertEquals(
            1524L,
            FlickerProtectionOverlayController.computeInitialHardSafetyTimeoutMs(
                clampedPreDelayMs = 24L,
                clampedPostHoldMs = 1000L
            )
        )
    }

    @Test
    fun `AOD wake detection rejects taps while in STATE_DOZE, STATE_DOZE_SUSPEND, STATE_ON_SUSPEND, or non-interactive`() {
        // Only interactive + Display.STATE_ON is considered fully awake
        assertTrue(BlankSpaceHitTester.isDisplayFullyAwake(Display.STATE_ON, isInteractive = true))

        // AOD / Doze states or non-interactive states are NOT fully awake
        assertFalse(BlankSpaceHitTester.isDisplayFullyAwake(Display.STATE_ON, isInteractive = false))
        assertFalse(BlankSpaceHitTester.isDisplayFullyAwake(Display.STATE_DOZE, isInteractive = false))
        assertFalse(BlankSpaceHitTester.isDisplayFullyAwake(Display.STATE_DOZE, isInteractive = true))
        assertFalse(BlankSpaceHitTester.isDisplayFullyAwake(Display.STATE_DOZE_SUSPEND, isInteractive = false))
        assertFalse(BlankSpaceHitTester.isDisplayFullyAwake(Display.STATE_ON_SUSPEND, isInteractive = false))
        assertFalse(BlankSpaceHitTester.isDisplayFullyAwake(Display.STATE_OFF, isInteractive = false))
    }

    @Test
    fun `AOD wake transition grace period blocks double-tap to wake from turning screen back off during transition`() {
        assertEquals(600L, BlankSpaceHitTester.SCREEN_WAKE_TRANSITION_GRACE_MS)

        // No wake recorded yet -> not in grace period
        assertFalse(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 1000L,
                lastScreenWakeUptimeMillis = 0L
            )
        )

        val wakeTime = 5000L

        // Tap 1 stamped slightly before or right at wakeTime (waking from AOD) -> blocked
        assertTrue(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 4985L,
                lastScreenWakeUptimeMillis = wakeTime
            )
        )
        assertTrue(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5000L,
                lastScreenWakeUptimeMillis = wakeTime
            )
        )

        // Tap 2 of double-tap to wake from AOD (220ms after wake, while transitioning to normal Lock Screen) -> blocked
        assertTrue(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5220L,
                lastScreenWakeUptimeMillis = wakeTime
            )
        )

        // Tap at end of default 600ms AOD-to-Lock-Screen transition window -> blocked
        assertTrue(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5600L,
                lastScreenWakeUptimeMillis = wakeTime
            )
        )

        // Tap after Lock Screen has finished default 600ms transition (> 600ms after wake) -> allowed
        assertFalse(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5601L,
                lastScreenWakeUptimeMillis = wakeTime
            )
        )
        assertFalse(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 6000L,
                lastScreenWakeUptimeMillis = wakeTime
            )
        )

        // Custom slider values: 0ms still blocks pre-wake/at-wake taps (<= wakeTime) but allows post-wake taps immediately
        assertTrue(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 4985L,
                lastScreenWakeUptimeMillis = wakeTime,
                gracePeriodMs = 0L
            )
        )
        assertTrue(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5000L,
                lastScreenWakeUptimeMillis = wakeTime,
                gracePeriodMs = 0L
            )
        )
        assertFalse(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5001L,
                lastScreenWakeUptimeMillis = wakeTime,
                gracePeriodMs = 0L
            )
        )
        assertFalse(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5050L,
                lastScreenWakeUptimeMillis = wakeTime,
                gracePeriodMs = 0L
            )
        )

        // Custom slider value: 350ms blocks at 5350L and allows at 5351L
        assertTrue(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5350L,
                lastScreenWakeUptimeMillis = wakeTime,
                gracePeriodMs = 350L
            )
        )
        assertFalse(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5351L,
                lastScreenWakeUptimeMillis = wakeTime,
                gracePeriodMs = 350L
            )
        )

        // Custom slider value: 1200ms blocks at 6200L and allows at 6201L
        assertTrue(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 6200L,
                lastScreenWakeUptimeMillis = wakeTime,
                gracePeriodMs = 1200L
            )
        )
        assertFalse(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 6201L,
                lastScreenWakeUptimeMillis = wakeTime,
                gracePeriodMs = 1200L
            )
        )

        // effectiveWakeGraceMs applies slider value on Lock Screen and 0L on Home Screen
        val customSettings = TapLockSettings(lockScreenWakeGraceMs = 900)
        assertEquals(900L, customSettings.effectiveWakeGraceMs(isKeyguardLocked = true))
        assertEquals(0L, customSettings.effectiveWakeGraceMs(isKeyguardLocked = false))
    }

    @Test
    fun `Check C Lock Screen filter allows SystemUI lock screen but rejects Camera and recent photo view when phone is locked`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        // 1. Genuine Lock Screen (com.android.systemui) -> ALLOWED
        assertTrue(
            BlankSpaceHitTester.isLockScreenUnobscured(
                windows = null,
                activeRootPackageName = "com.android.systemui",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992
            )
        )

        // 2. Pixel Camera launched over lock screen (showWhenLocked) -> REJECTED
        assertFalse(
            BlankSpaceHitTester.isLockScreenUnobscured(
                windows = null,
                activeRootPackageName = "com.google.android.GoogleCamera",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992
            )
        )

        // 3. Google Photos locked recent-photo viewer (when viewing unprocessed/processed photo from locked camera) -> REJECTED
        assertFalse(
            BlankSpaceHitTester.isLockScreenUnobscured(
                windows = null,
                activeRootPackageName = "com.google.android.apps.photos",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992
            )
        )

        // 4. Null activeRootPackageName during Camera -> recent photo transition while locked -> REJECTED
        assertFalse(
            BlankSpaceHitTester.isLockScreenUnobscured(
                windows = null,
                activeRootPackageName = null,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992
            )
        )

        // 5. Emergency Dialer / DeskClock over lock screen -> REJECTED
        assertFalse(
            BlankSpaceHitTester.isLockScreenUnobscured(
                windows = null,
                activeRootPackageName = "com.google.android.dialer",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992
            )
        )
    }

    @Test
    fun `Check A and bouncer detector reject photo filmstrip pager, null-id Camera or Photos containers, and PIN or Pattern bouncer on Lock Screen`() {
        // 1. Full-screen photo filmstrip ViewPager with photo/filmstrip ID is NOT treated as blank workspace container
        val unprocessedPhotoPager = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992),
            className = "androidx.viewpager.widget.ViewPager",
            viewIdResourceName = "com.google.android.GoogleCamera:id/filmstrip_photo_pager",
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = true,
            isLongClickable = false,
            isFocusable = true,
            isRoot = false,
            childCount = 1
        )
        val cameraRoot = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992),
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.google.android.GoogleCamera:id/camera_root",
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = false,
            isLongClickable = false,
            isFocusable = true,
            isRoot = true,
            childCount = 1,
            children = listOf(unprocessedPhotoPager)
        )
        assertTrue(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = cameraRoot,
                rawX = 672f,
                rawY = 1500f,
                screenWidthPx = 1344,
                screenHeightPx = 2992
            )
        )

        // 2. Obfuscated / null-resource-ID ViewPager and root FrameLayout in Google Photos / Google Camera
        // (where viewIdResourceName is null and only packageName identifies the app) -> REJECTED
        val nullIdPhotosRoot = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992),
            className = "android.widget.FrameLayout",
            viewIdResourceName = null,
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = false,
            isLongClickable = false,
            isFocusable = true,
            isRoot = true,
            childCount = 0,
            packageName = "com.google.android.apps.photos"
        )
        assertTrue(
            BlankSpaceHitTester.hitsInteractiveSnapshot(
                root = nullIdPhotosRoot,
                rawX = 672f,
                rawY = 1500f,
                screenWidthPx = 1344,
                screenHeightPx = 2992
            )
        )

        // 3. Lock Screen PIN bouncer detection
        val pinEntryNode = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(100, 800, 1244, 2600),
            className = "com.android.keyguard.PinBouncer",
            viewIdResourceName = "com.android.systemui:id/pinEntry",
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = true,
            isLongClickable = false,
            isFocusable = true,
            isRoot = false,
            childCount = 10
        )
        val bouncerRoot = NodeSnapshot(
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992),
            className = "com.android.systemui.shade.NotificationPanelView",
            viewIdResourceName = "com.android.systemui:id/notification_panel",
            contentDescription = null,
            isVisibleToUser = true,
            isClickable = false,
            isLongClickable = false,
            isFocusable = true,
            isRoot = true,
            childCount = 1,
            children = listOf(pinEntryNode)
        )
        assertTrue(BlankSpaceHitTester.isLockScreenBouncerSnapshotShowing(bouncerRoot))

        // 4. Hidden bouncer (isVisibleToUser = false) on normal Lock Screen -> returns false
        val hiddenPinNode = pinEntryNode.copy(isVisibleToUser = false)
        val normalLockScreenRoot = bouncerRoot.copy(children = listOf(hiddenPinNode))
        assertFalse(BlankSpaceHitTester.isLockScreenBouncerSnapshotShowing(normalLockScreenRoot))
    }

    @Test
    fun `Home Screen uses snapshot overlay by default to simulate power button while black flicker mask is disabled by default`() {
        val defaultSettings = TapLockSettings()
        assertTrue(defaultSettings.simulatePowerButton)
        assertFalse(defaultSettings.enableFlickerProtection)
        assertFalse(defaultSettings.enableLockScreenFlickerProtection)

        // Home Screen (isKeyguardLocked = false) uses snapshot overlay by default to replicate power button
        assertTrue(defaultSettings.shouldUseSnapshotOverlay(isKeyguardLocked = false))
        assertFalse(defaultSettings.shouldUseFlickerProtection(isKeyguardLocked = false))

        // Lock Screen (isKeyguardLocked = true) bypasses both snapshot and black overlay by default so SystemUI transitions straight to AOD
        assertFalse(defaultSettings.shouldUseSnapshotOverlay(isKeyguardLocked = true))
        assertFalse(defaultSettings.shouldUseFlickerProtection(isKeyguardLocked = true))

        // Disabling snapshot simulation on Home Screen allows fallback to pure black or direct lock
        val noSnapshot = defaultSettings.copy(simulatePowerButton = false)
        assertFalse(noSnapshot.shouldUseSnapshotOverlay(isKeyguardLocked = false))
        assertFalse(noSnapshot.shouldUseFlickerProtection(isKeyguardLocked = false))

        // Enabling pure black flicker protection explicitly activates it on Home Screen
        val blackFlickerEnabled = defaultSettings.copy(enableFlickerProtection = true)
        assertTrue(blackFlickerEnabled.shouldUseFlickerProtection(isKeyguardLocked = false))
        assertFalse(blackFlickerEnabled.shouldUseFlickerProtection(isKeyguardLocked = true))

        // Enabling Lock Screen flicker mask explicitly activates it on Lock Screen too
        val lockMaskEnabled = defaultSettings.copy(enableLockScreenFlickerProtection = true)
        assertFalse(lockMaskEnabled.shouldUseFlickerProtection(isKeyguardLocked = false))
        assertTrue(lockMaskEnabled.shouldUseFlickerProtection(isKeyguardLocked = true))

        // Lock Screen flicker mask can be configured independently of Home Screen flicker protection
        val onlyLockMaskEnabled = TapLockSettings(
            simulatePowerButton = false,
            enableFlickerProtection = false,
            enableLockScreenFlickerProtection = true
        )
        assertFalse(onlyLockMaskEnabled.shouldUseSnapshotOverlay(isKeyguardLocked = false))
        assertFalse(onlyLockMaskEnabled.shouldUseFlickerProtection(isKeyguardLocked = false))
        assertTrue(onlyLockMaskEnabled.shouldUseFlickerProtection(isKeyguardLocked = true))

        // Both flicker masks and snapshot disabled -> direct lock screen call
        val allDisabled = TapLockSettings(
            simulatePowerButton = false,
            enableFlickerProtection = false,
            enableLockScreenFlickerProtection = false
        )
        assertFalse(allDisabled.shouldUseSnapshotOverlay(isKeyguardLocked = false))
        assertFalse(allDisabled.shouldUseFlickerProtection(isKeyguardLocked = false))
        assertFalse(allDisabled.shouldUseFlickerProtection(isKeyguardLocked = true))
    }

    @Test
    fun `hard safety timeout ensures overlay is never left attached even under long post-lock hold durations`() {
        // Standard defaults: 24ms pre-delay + 400ms post-hold + 500ms slack = 924ms, bounded by HARD_SAFETY_TIMEOUT_MS (1200ms)
        assertEquals(
            1200L,
            FlickerProtectionOverlayController.computeInitialHardSafetyTimeoutMs(24L, 400L)
        )

        // Maximum values: 100ms pre-delay + 1000ms post-hold + 500ms slack = 1600ms
        assertEquals(
            1600L,
            FlickerProtectionOverlayController.computeInitialHardSafetyTimeoutMs(100L, 1000L)
        )

        // Minimum values: 0ms pre-delay + 100ms post-hold + 500ms slack = 600ms -> bounded by 1200ms
        assertEquals(
            1200L,
            FlickerProtectionOverlayController.computeInitialHardSafetyTimeoutMs(0L, 100L)
        )

        // With fade-to-black duration: 24ms pre-delay + 400ms post-hold + 250ms fade + 500ms slack = 1174ms, bounded by 1200ms
        assertEquals(
            1200L,
            FlickerProtectionOverlayController.computeInitialHardSafetyTimeoutMs(24L, 400L, 250L)
        )

        // Long fade duration: 100ms pre-delay + 1000ms post-hold + 800ms fade + 500ms slack = 2400ms
        assertEquals(
            2400L,
            FlickerProtectionOverlayController.computeInitialHardSafetyTimeoutMs(100L, 1000L, 800L)
        )
    }

    @Test
    fun `preferences clamp all parameters within safe operational bounds`() {
        val outOfBoundsSettings = TapLockSettings(
            doubleTapTimeoutMs = 1000,
            lockScreenWakeGraceMs = 5000,
            fadeToBlackDurationMs = 2500,
            preLockBlackoutDelayMs = 500,
            postLockHoldDurationMs = 2000
        )

        assertEquals(TapLockSettings.MAX_DOUBLE_TAP_TIMEOUT_MS, outOfBoundsSettings.clampedDoubleTapTimeoutMs)
        assertEquals(TapLockSettings.MAX_LOCK_SCREEN_WAKE_GRACE_MS, outOfBoundsSettings.clampedLockScreenWakeGraceMs)
        assertEquals(TapLockSettings.MAX_FADE_TO_BLACK_DURATION_MS, outOfBoundsSettings.clampedFadeToBlackDurationMs)
        assertEquals(TapLockSettings.MAX_PRE_LOCK_DELAY_MS, outOfBoundsSettings.clampedPreLockBlackoutDelayMs)
        assertEquals(TapLockSettings.MAX_POST_LOCK_HOLD_MS, outOfBoundsSettings.clampedPostLockHoldDurationMs)

        val negativeSettings = TapLockSettings(
            doubleTapTimeoutMs = -10,
            lockScreenWakeGraceMs = -50,
            fadeToBlackDurationMs = -200,
            preLockBlackoutDelayMs = -100,
            postLockHoldDurationMs = -500
        )

        assertEquals(TapLockSettings.MIN_DOUBLE_TAP_TIMEOUT_MS, negativeSettings.clampedDoubleTapTimeoutMs)
        assertEquals(TapLockSettings.MIN_LOCK_SCREEN_WAKE_GRACE_MS, negativeSettings.clampedLockScreenWakeGraceMs)
        assertEquals(TapLockSettings.MIN_FADE_TO_BLACK_DURATION_MS, negativeSettings.clampedFadeToBlackDurationMs)
        assertEquals(TapLockSettings.MIN_PRE_LOCK_DELAY_MS, negativeSettings.clampedPreLockBlackoutDelayMs)
        assertEquals(TapLockSettings.MIN_POST_LOCK_HOLD_MS, negativeSettings.clampedPostLockHoldDurationMs)
    }

    @Test
    fun `effective wake grace delay applies only on locked Keyguard and never on unlocked Home Screen`() {
        val settings = TapLockSettings(
            enableHomeScreen = true,
            enableLockScreen = true,
            lockScreenWakeGraceMs = 600
        )

        // On Lock Screen (isKeyguardLocked = true), imposes the configured 600ms wake grace period
        assertEquals(600L, settings.effectiveWakeGraceMs(isKeyguardLocked = true))

        // On Home Screen (isKeyguardLocked = false), returns 0L so normal taps are not blocked
        assertEquals(0L, settings.effectiveWakeGraceMs(isKeyguardLocked = false))

        // When Lock Screen is disabled, returns 0L even if keyguard is locked
        val disabledLockScreen = settings.copy(enableLockScreen = false)
        assertEquals(0L, disabledLockScreen.effectiveWakeGraceMs(isKeyguardLocked = true))
    }

    @Test
    fun `BuildConfig reflects version 1_0_8 and versionCode 9`() {
        assertEquals(9, BuildConfig.VERSION_CODE)
        assertEquals("1.0.8", BuildConfig.VERSION_NAME)
        assertEquals("com.pixeltaplock", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `isInterruptingAccessibilityEvent ignores launcher focus and overlay transitions while interrupting on app launch, clicks, and scrolls`() {
        val allowedLaunchers = setOf(
            TapAccessibilityService.PIXEL_LAUNCHER_PACKAGE,
            TapAccessibilityService.AOSP_LAUNCHER_PACKAGE
        )
        val ownPkg = "com.pixeltaplock"
        val ownWidgetDesc = "Double-tap empty widget area to lock screen"

        // 1. Launcher regaining focus (TYPE_WINDOW_STATE_CHANGED) -> does NOT interrupt
        assertFalse(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.google.android.apps.nexuslauncher",
                className = "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
                contentDescription = null,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 2. AOSP Launcher regaining focus -> does NOT interrupt
        assertFalse(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.android.launcher3",
                className = "com.android.launcher3.uioverrides.QuickstepLauncher",
                contentDescription = null,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 3. PixelTapLock's own overlay window state changed -> does NOT interrupt
        assertFalse(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = ownPkg,
                className = "com.pixeltaplock.FlickerProtectionOverlay",
                contentDescription = null,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 4. Null package with launcher class name -> does NOT interrupt
        assertFalse(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = null,
                className = "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
                contentDescription = null,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 5. REGRESSION: Real app launch (e.g. YouTube) -> INTERRUPTS
        assertTrue(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "com.google.android.youtube",
                className = "com.google.android.youtube.HomeActivity",
                contentDescription = null,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 6. REGRESSION: Notification shade / SystemUI window state change -> INTERRUPTS
        assertTrue(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = BlankSpaceHitTester.SYSTEM_UI_PACKAGE,
                className = "com.android.systemui.shade.NotificationShadeWindowView",
                contentDescription = null,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 7. REGRESSION: Clicking an app icon on Home Screen -> INTERRUPTS
        assertTrue(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                packageName = "com.google.android.apps.nexuslauncher",
                className = "android.widget.TextView",
                contentDescription = "Chrome",
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 8. REGRESSION: Long-clicking an app icon or widget -> INTERRUPTS
        assertTrue(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                packageName = "com.google.android.apps.nexuslauncher",
                className = "android.widget.TextView",
                contentDescription = "Messages",
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 9. REGRESSION: Scrolling the workspace / app drawer -> INTERRUPTS
        assertTrue(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                packageName = "com.google.android.apps.nexuslauncher",
                className = "com.android.launcher3.PagedView",
                contentDescription = null,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 10. Tapping inside own BlankSpaceWidget -> does NOT interrupt
        assertFalse(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                packageName = ownPkg,
                className = "android.widget.FrameLayout",
                contentDescription = ownWidgetDesc,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )

        // 11. Other non-interactive event (TYPE_WINDOWS_CHANGED) -> does NOT interrupt
        assertFalse(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                packageName = "com.google.android.apps.nexuslauncher",
                className = null,
                contentDescription = null,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )
    }

    @Test
    fun `Check B correlation debounce distinguishes launcher focus events from interrupting real interactions`() {
        val tap1Uptime = 5000L
        val tap2Plus60ms = 5260L

        // Scenario 1: Returning Home fires launcher TYPE_WINDOW_STATE_CHANGED between tap1 and debounce end.
        // Because launcher focus is not an interrupting interactive event, lastInteractiveEventUptimeMillis remains old (4200L).
        assertFalse(
            BlankSpaceHitTester.wasInterruptedByInteractiveEvent(
                tap1UptimeMillis = tap1Uptime,
                debounceEndUptimeMillis = tap2Plus60ms,
                lastInteractiveEventUptimeMillis = 4200L
            )
        )

        // Scenario 2: Real app launch occurred between tap1 and debounce end -> INTERRUPTS
        assertTrue(
            BlankSpaceHitTester.wasInterruptedByInteractiveEvent(
                tap1UptimeMillis = tap1Uptime,
                debounceEndUptimeMillis = tap2Plus60ms,
                lastInteractiveEventUptimeMillis = 5120L
            )
        )

        // Scenario 3: Real app icon click occurred during 60ms debounce window -> INTERRUPTS
        assertTrue(
            BlankSpaceHitTester.wasInterruptedByInteractiveEvent(
                tap1UptimeMillis = tap1Uptime,
                debounceEndUptimeMillis = tap2Plus60ms,
                lastInteractiveEventUptimeMillis = 5240L
            )
        )

        // Scenario 4: Real workspace scroll occurred between tap1 and tap2 -> INTERRUPTS
        assertTrue(
            BlankSpaceHitTester.wasInterruptedByInteractiveEvent(
                tap1UptimeMillis = tap1Uptime,
                debounceEndUptimeMillis = tap2Plus60ms,
                lastInteractiveEventUptimeMillis = 5080L
            )
        )
    }

    @Test
    fun `app-close transition allows Tap 1 candidate when launcher window is visible but not yet active or focused`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        // Closing Chrome window: no longer active/focused, animating out
        val closingChromeWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = false,
            isFocused = false,
            packageName = "com.android.chrome",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992)
        )

        // Launcher window: visible full-screen, but WindowManager has not yet marked it active/focused
        val transitioningLauncherWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = false,
            isFocused = false,
            packageName = "com.google.android.apps.nexuslauncher",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992)
        )

        // System gesture navigation bar: normal small height, not focused
        val navBarWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_SYSTEM,
            isActive = false,
            isFocused = false,
            packageName = BlankSpaceHitTester.SYSTEM_UI_PACKAGE,
            boundsInScreen = BlankSpaceHitTester.createRect(0, 2866, 1344, 2992)
        )

        val transitionWindows = listOf(closingChromeWindow, transitioningLauncherWindow, navBarWindow)

        // During transition, activeRootPackageName is null because no window is currently active/focused
        val activeRootPkgDuringTransition: String? = null

        // 1. Relaxed eligibility for Tap 1 candidate (allowTransitionCandidate = true) -> ALLOWED
        assertTrue(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = transitionWindows,
                activeRootPackageName = activeRootPkgDuringTransition,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = true
            )
        )

        // 2. Strict eligibility at lock time in 60ms debounce (allowTransitionCandidate = false) -> REJECTED
        // Ensures nothing locks until the launcher actually becomes the active, focused window.
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = transitionWindows,
                activeRootPackageName = activeRootPkgDuringTransition,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = false
            )
        )
    }

    @Test
    fun `lock time re-validation requires launcher to be focused or active before executing lock`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        // Case A: Transition completed before 60ms debounce expires: launcher is now active & focused
        val settledLauncherWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = true,
            isFocused = true,
            packageName = "com.google.android.apps.nexuslauncher",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992)
        )
        val settledWindows = listOf(settledLauncherWindow)

        // Strict lock re-validation passes when settled
        assertTrue(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = settledWindows,
                activeRootPackageName = "com.google.android.apps.nexuslauncher",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = false
            )
        )

        // Case B: Transition aborted or user switched to another app (e.g. Chrome re-gained active focus)
        val chromeActiveWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = true,
            isFocused = true,
            packageName = "com.android.chrome",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992)
        )
        val abortedWindows = listOf(chromeActiveWindow, settledLauncherWindow.copy(isActive = false, isFocused = false))

        // Strict lock re-validation rejects locking over Chrome
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = abortedWindows,
                activeRootPackageName = "com.android.chrome",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = false
            )
        )
    }

    @Test
    fun `regression test active foreground app blocks locking in both transition and strict modes`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        val activeYouTubeWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = true,
            isFocused = true,
            packageName = "com.google.android.youtube",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992)
        )
        val inactiveLauncherWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = false,
            isFocused = false,
            packageName = "com.google.android.apps.nexuslauncher",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992)
        )

        val windowsWithActiveApp = listOf(activeYouTubeWindow, inactiveLauncherWindow)

        // 1. When a non-launcher app is active, transition candidate is REJECTED
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = windowsWithActiveApp,
                activeRootPackageName = "com.google.android.youtube",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = true
            )
        )

        // 2. Strict check is also REJECTED
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = windowsWithActiveApp,
                activeRootPackageName = "com.google.android.youtube",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = false
            )
        )

        // 3. If activeRootPackageName belongs to YouTube even with null windows -> REJECTED
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = null,
                activeRootPackageName = "com.google.android.youtube",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = true
            )
        )
    }

    @Test
    fun `regression test expanded notification shade blocks locking in both transition and strict modes`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        val activeLauncherWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = true,
            isFocused = false,
            packageName = "com.google.android.apps.nexuslauncher",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992)
        )

        // Expanded shade: height > 35% of 2992 (1047px) -> 1800px
        val expandedShadeWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_SYSTEM,
            isActive = true,
            isFocused = true,
            packageName = BlankSpaceHitTester.SYSTEM_UI_PACKAGE,
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 1800)
        )

        val windowsWithExpandedShade = listOf(expandedShadeWindow, activeLauncherWindow)

        // 1. Transition candidate mode is BLOCKED by expanded shade
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = windowsWithExpandedShade,
                activeRootPackageName = BlankSpaceHitTester.SYSTEM_UI_PACKAGE,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = true
            )
        )

        // 2. Strict mode is BLOCKED by expanded shade
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = windowsWithExpandedShade,
                activeRootPackageName = BlankSpaceHitTester.SYSTEM_UI_PACKAGE,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = false
            )
        )

        // 3. Non-focused but large system window (> 35% height) is BLOCKED in both modes
        val unfocusedExpandedShade = expandedShadeWindow.copy(isActive = false, isFocused = false)
        val windowsWithUnfocusedShade = listOf(unfocusedExpandedShade, activeLauncherWindow)
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = windowsWithUnfocusedShade,
                activeRootPackageName = "com.google.android.apps.nexuslauncher",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = true
            )
        )
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = windowsWithUnfocusedShade,
                activeRootPackageName = "com.google.android.apps.nexuslauncher",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = false
            )
        )
    }

    @Test
    fun `launcher window missing or collapsed rejects transition candidate`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        // Collapsed launcher window (< 50% screen height)
        val collapsedLauncher = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = false,
            isFocused = false,
            packageName = "com.google.android.apps.nexuslauncher",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 500)
        )

        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = listOf(collapsedLauncher),
                activeRootPackageName = null,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = true
            )
        )

        // Empty window list with null activeRootPackageName rejects
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = emptyList(),
                activeRootPackageName = null,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = true
            )
        )
    }

    @Test
    fun `regression test active or visible IME keyboard blocks Home Screen workspace in both transition and strict modes`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        val activeLauncherWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = true,
            isFocused = true,
            packageName = "com.google.android.apps.nexuslauncher",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992)
        )

        // Case 1: Visible IME window (e.g. keyboard showing over workspace search)
        val visibleImeWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            isActive = true,
            isFocused = true,
            packageName = "com.google.android.inputmethod.latin",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 1800, 1344, 2992)
        )

        val windowsWithIme = listOf(visibleImeWindow, activeLauncherWindow)

        // IME blocks transition candidate
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = windowsWithIme,
                activeRootPackageName = "com.google.android.apps.nexuslauncher",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = true
            )
        )

        // IME blocks strict mode
        assertFalse(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = windowsWithIme,
                activeRootPackageName = "com.google.android.apps.nexuslauncher",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = false
            )
        )

        // Case 2: Dismissed IME window (height = 0, inactive) does NOT block
        val dismissedImeWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            isActive = false,
            isFocused = false,
            packageName = "com.google.android.inputmethod.latin",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 2992, 1344, 2992)
        )
        val windowsWithDismissedIme = listOf(dismissedImeWindow, activeLauncherWindow)
        assertTrue(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = windowsWithDismissedIme,
                activeRootPackageName = "com.google.android.apps.nexuslauncher",
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = false
            )
        )
    }

    @Test
    fun `empty string package name correctly falls back to launcher title matching in isHomeWorkspaceSnapshotUnobscured`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        // Window with empty package name ("") but valid launcher title
        val emptyPkgLauncherWindow = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = true,
            isFocused = true,
            packageName = "",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992),
            title = "Pixel Launcher"
        )

        assertTrue(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = listOf(emptyPkgLauncherWindow),
                activeRootPackageName = null,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = false
            )
        )
    }

    @Test
    fun `isInterruptingAccessibilityEvent treats system android framework package as interrupting event`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )
        val ownPkg = "com.pixeltaplock"
        val ownWidgetDesc = "Double-tap empty widget area to lock screen"

        // System resolver or framework dialog (package "android") -> INTERRUPTS
        assertTrue(
            BlankSpaceHitTester.isInterruptingAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                packageName = "android",
                className = "com.android.internal.app.ResolverActivity",
                contentDescription = null,
                ownPackageName = ownPkg,
                ownWidgetContentDescription = ownWidgetDesc,
                allowedLauncherPackages = allowedLaunchers
            )
        )
    }

    @Test
    fun `fade to black duration defaults to 250ms and clamps between 100ms and 800ms`() {
        val defaultSettings = TapLockSettings()
        assertEquals(250, defaultSettings.fadeToBlackDurationMs)
        assertEquals(250, defaultSettings.clampedFadeToBlackDurationMs)

        val fastFade = defaultSettings.copy(fadeToBlackDurationMs = 120)
        assertEquals(120, fastFade.clampedFadeToBlackDurationMs)

        val clampedUnder = defaultSettings.copy(fadeToBlackDurationMs = 50)
        assertEquals(TapLockSettings.MIN_FADE_TO_BLACK_DURATION_MS, clampedUnder.clampedFadeToBlackDurationMs)

        val clampedOver = defaultSettings.copy(fadeToBlackDurationMs = 1200)
        assertEquals(TapLockSettings.MAX_FADE_TO_BLACK_DURATION_MS, clampedOver.clampedFadeToBlackDurationMs)
    }

    @Test
    fun `rapid wake from AOD to fingerprint unlock flow does not impose lock screen grace period on Home Screen`() {
        val settings = TapLockSettings(
            enableHomeScreen = true,
            enableLockScreen = true,
            lockScreenWakeGraceMs = 600
        )

        // Wake occurs at 5000L. Device wakes from AOD.
        val wakeUptime = 5000L

        // On Lock Screen (isKeyguardLocked = true), 600ms grace period applies
        assertEquals(600L, settings.effectiveWakeGraceMs(isKeyguardLocked = true))
        assertTrue(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5300L,
                lastScreenWakeUptimeMillis = wakeUptime,
                gracePeriodMs = 600L
            )
        )

        // User performs fingerprint unlock: isKeyguardLocked becomes false.
        // On Home Screen, effective grace delay is 0L
        assertEquals(0L, settings.effectiveWakeGraceMs(isKeyguardLocked = false))

        // When screen wake timestamp is cleared on unlock (0L), grace period check returns false immediately
        assertFalse(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5300L,
                lastScreenWakeUptimeMillis = 0L,
                gracePeriodMs = 0L
            )
        )

        // Even with wake timestamp retained, any post-wake tap (> wakeUptime) is NOT within grace period when grace is 0L
        assertFalse(
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = 5300L,
                lastScreenWakeUptimeMillis = wakeUptime,
                gracePeriodMs = 0L
            )
        )
    }

    @Test
    fun `Home Screen workspace with dismissing SystemUI window allows candidate when launcher is visible and no shade is open`() {
        val allowedLaunchers = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"
        )

        // Transitioning window state right after fingerprint unlock:
        // SystemUI lock screen is fading out, launcher window is visible, no shade expanded (> 35%), no other app
        val visibleLauncher = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            isActive = false,
            isFocused = false,
            packageName = "com.google.android.apps.nexuslauncher",
            boundsInScreen = BlankSpaceHitTester.createRect(0, 0, 1344, 2992)
        )
        val navBar = WindowSnapshot(
            type = AccessibilityWindowInfo.TYPE_SYSTEM,
            isActive = false,
            isFocused = false,
            packageName = BlankSpaceHitTester.SYSTEM_UI_PACKAGE,
            boundsInScreen = BlankSpaceHitTester.createRect(0, 2866, 1344, 2992)
        )

        val unlockWindows = listOf(visibleLauncher, navBar)

        // When allowTransitionCandidate is true (post-unlock transition), candidate is accepted
        assertTrue(
            BlankSpaceHitTester.isHomeWorkspaceSnapshotUnobscured(
                windows = unlockWindows,
                activeRootPackageName = null,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = 2992,
                allowTransitionCandidate = true
            )
        )
    }
}

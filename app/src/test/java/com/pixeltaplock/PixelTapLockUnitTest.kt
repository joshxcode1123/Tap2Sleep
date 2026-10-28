package com.pixeltaplock

import android.view.Display
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
    fun `BuildConfig reflects version 1_0_4 and versionCode 5`() {
        assertEquals(5, BuildConfig.VERSION_CODE)
        assertEquals("1.0.4", BuildConfig.VERSION_NAME)
        assertEquals("com.pixeltaplock", BuildConfig.APPLICATION_ID)
    }
}

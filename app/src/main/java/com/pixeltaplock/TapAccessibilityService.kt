package com.pixeltaplock

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Core AccessibilityService for PixelTapLock.
 *
 * 1. Attaches a 1x1 transparent non-blocking touch watcher overlay with FLAG_WATCH_OUTSIDE_TOUCH.
 * 2. Performs Hybrid Blank-Space Verification (Check A: Coordinate & Edge Inset Hit-Test,
 *    Check B: 60ms Event-Correlation Debounce, Check C: Active Workspace & Lock Screen Filter,
 *    plus Always-On Display wake-transition protection).
 * 3. Executes the QHD+ Full-Bleed Flicker Protection Overlay before calling GLOBAL_ACTION_LOCK_SCREEN.
 */
class TapAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private lateinit var preferencesRepository: TapLockPreferencesRepository
    private var flickerController: FlickerProtectionOverlayController? = null

    private var touchWatcherView: View? = null

    @Volatile
    private var currentSettings: TapLockSettings = TapLockSettings()

    private var firstOutsideTap: TapSample? = null
    private var pendingDebounceRunnable: Runnable? = null
    private var pendingScreenshotTimeoutRunnable: Runnable? = null
    @Volatile
    private var isScreenshotInProgress = false

    @Volatile
    private var lastInteractiveEventUptimeMillis: Long = 0L

    @Volatile
    private var lastScreenWakeUptimeMillis: Long = 0L

    @Volatile
    private var lastUserPresentUptimeMillis: Long = 0L

    @Volatile
    private var lastObservedDisplayState: Int = Display.STATE_ON

    @Volatile
    private var lastObservedInteractive: Boolean = true

    private var isWakeTrackingRegistered = false

    private fun isDeviceKeyguardLocked(nowUptime: Long): Boolean {
        if (lastUserPresentUptimeMillis > 0L && (nowUptime - lastUserPresentUptimeMillis) < UNLOCK_TRANSITION_WINDOW_MS) {
            return false
        }
        return try {
            keyguardManager.isKeyguardLocked
        } catch (_: Exception) {
            false
        }
    }

    private val serviceDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            syncDisplayWakeState(SystemClock.uptimeMillis())
        }
    }

    private val serviceScreenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val nowUptime = SystemClock.uptimeMillis()
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    lastUserPresentUptimeMillis = nowUptime
                    lastScreenWakeUptimeMillis = 0L
                }
                Intent.ACTION_SCREEN_ON -> {
                    lastUserPresentUptimeMillis = 0L
                    val wasAwakeBefore = BlankSpaceHitTester.isDisplayFullyAwake(
                        displayState = lastObservedDisplayState,
                        isInteractive = lastObservedInteractive
                    )
                    if (!wasAwakeBefore) {
                        lastScreenWakeUptimeMillis = maxOf(lastScreenWakeUptimeMillis, nowUptime)
                        firstOutsideTap = null
                        cancelPendingDoubleTapLock()
                    }
                    syncDisplayWakeState(nowUptime)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    lastObservedInteractive = false
                    lastUserPresentUptimeMillis = 0L
                    firstOutsideTap = null
                    cancelPendingDoubleTapLock()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        preferencesRepository = TapLockPreferencesRepository(applicationContext.tapLockDataStore)

        serviceScope.launch {
            preferencesRepository.settingsFlow.collectLatest { settings ->
                currentSettings = settings
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceRunningFlow.value = true

        startWakeTransitionTracking()

        val controller = FlickerProtectionOverlayController(
            context = this,
            windowManager = windowManager,
            displayManager = displayManager
        )
        controller.startListening()
        flickerController = controller

        attachTouchWatcherOverlay()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-query maximumWindowMetrics and update full-bleed overlay if currently visible
        flickerController?.refreshOverlayBoundsIfAttached()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val now = SystemClock.uptimeMillis()
        val pkgStr = event.packageName?.toString()

        // If the launcher window state changed, mark device as unlocked immediately
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkgStr != null && pkgStr in resolveAllowedLauncherPackages()
        ) {
            lastUserPresentUptimeMillis = now
            lastScreenWakeUptimeMillis = 0L
        }

        val isInteractiveEvent = BlankSpaceHitTester.isInterruptingAccessibilityEvent(
            eventType = event.eventType,
            packageName = pkgStr,
            className = event.className?.toString(),
            contentDescription = event.contentDescription?.toString(),
            ownPackageName = packageName,
            ownWidgetContentDescription = getString(R.string.widget_content_description),
            allowedLauncherPackages = resolveAllowedLauncherPackages()
        )

        if (isInteractiveEvent) {
            val isSystemUiUnlockTransition = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkgStr == BlankSpaceHitTester.SYSTEM_UI_PACKAGE

            if (!isSystemUiUnlockTransition) {
                lastInteractiveEventUptimeMillis = now
                firstOutsideTap = null
                cancelPendingDoubleTapLock()
            }
        }
    }

    override fun onInterrupt() {
        cancelPendingDoubleTapLock()
        flickerController?.removeOverlayImmediately()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupServiceResources()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupServiceResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun cleanupServiceResources() {
        cancelPendingDoubleTapLock()
        detachTouchWatcherOverlay()
        stopWakeTransitionTracking()
        flickerController?.stopListening()
        flickerController = null
        cachedAllowedLauncherPackages = null
        lastLauncherResolveUptimeMs = 0L
        lastUserPresentUptimeMillis = 0L
        if (instance === this) {
            instance = null
        }
        _serviceRunningFlow.value = false
    }

    private fun startWakeTransitionTracking() {
        val currentDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        lastObservedDisplayState = currentDisplay?.state ?: Display.STATE_ON
        lastObservedInteractive = powerManager.isInteractive

        if (!isWakeTrackingRegistered) {
            displayManager.registerDisplayListener(serviceDisplayListener, mainHandler)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(serviceScreenWakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            isWakeTrackingRegistered = true
        }
    }

    private fun stopWakeTransitionTracking() {
        if (isWakeTrackingRegistered) {
            displayManager.unregisterDisplayListener(serviceDisplayListener)
            try {
                unregisterReceiver(serviceScreenWakeReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered
            }
            isWakeTrackingRegistered = false
        }
    }

    /**
     * Synchronizes live Display and PowerManager state.
     * - Returns false if the display is currently off or in Always-On Display (AOD / Doze),
     *   clearing any stored first tap or pending lock.
     * - If the display just transitioned from AOD/Off into [Display.STATE_ON] + interactive,
     *   records [lastScreenWakeUptimeMillis] so double-tapping to wake from AOD (or tapping
     *   a second time while transitioning out of AOD to the normal Lock Screen) will not
     *   turn the screen back off.
     */
    private fun syncDisplayWakeState(nowUptime: Long): Boolean {
        val displayState = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state
            ?: Display.STATE_ON
        val isInteractive = powerManager.isInteractive

        val isAwakeNow = BlankSpaceHitTester.isDisplayFullyAwake(
            displayState = displayState,
            isInteractive = isInteractive
        )
        val wasAwakeBefore = BlankSpaceHitTester.isDisplayFullyAwake(
            displayState = lastObservedDisplayState,
            isInteractive = lastObservedInteractive
        )

        lastObservedDisplayState = displayState
        lastObservedInteractive = isInteractive

        if (!isAwakeNow) {
            firstOutsideTap = null
            cancelPendingDoubleTapLock()
            return false
        }

        if (!wasAwakeBefore) {
            lastScreenWakeUptimeMillis = maxOf(lastScreenWakeUptimeMillis, nowUptime)
            firstOutsideTap = null
            cancelPendingDoubleTapLock()
        }

        return true
    }

    /**
     * Attaches a 1x1 pixel transparent view with TYPE_ACCESSIBILITY_OVERLAY and
     * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH.
     * Kept strictly 1x1 with alpha = 0f so Android's touch-obscuring security rules
     * are respected and no system dialogs are ever blocked.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchWatcherOverlay() {
        if (touchWatcherView != null) return

        val watcher = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, motionEvent ->
                if (motionEvent.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    val nowUptime = SystemClock.uptimeMillis()
                    val tapUptime = if (motionEvent.eventTime in 1L..nowUptime) {
                        motionEvent.eventTime
                    } else {
                        nowUptime
                    }
                    onOutsideTouchDetected(
                        rawX = motionEvent.rawX,
                        rawY = motionEvent.rawY,
                        uptimeMillis = tapUptime
                    )
                }
                false
            }
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        val params = WindowManager.LayoutParams(
            1, // 1px width
            1, // 1px height
            0,
            0,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
            title = "PixelTapLockTouchWatcher"
        }

        try {
            windowManager.addView(watcher, params)
            touchWatcherView = watcher
        } catch (_: Exception) {
            touchWatcherView = null
        }
    }

    private fun detachTouchWatcherOverlay() {
        val view = touchWatcherView ?: return
        touchWatcherView = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
            // Already removed
        }
    }

    private fun onOutsideTouchDetected(rawX: Float, rawY: Float, uptimeMillis: Long) {
        // Any subsequent touch occurring during a pending 60ms debounce window cancels the pending lock
        cancelPendingDoubleTapLock()

        val nowUptime = SystemClock.uptimeMillis()
        if (!syncDisplayWakeState(nowUptime)) {
            // Touch occurred while screen is in AOD (Doze) or Off.
            // Because tapping once on AOD wakes the screen on Pixel, record the wake timestamp
            // now and discard this tap so a second tap during the AOD -> Lock Screen transition
            // never pairs with it or locks the screen back off.
            lastScreenWakeUptimeMillis = maxOf(lastScreenWakeUptimeMillis, uptimeMillis, nowUptime)
            firstOutsideTap = null
            return
        }

        val settings = currentSettings
        if (!isEligibleScreenContext(settings, allowTransitionCandidate = true)) {
            firstOutsideTap = null
            return
        }

        val isLocked = isDeviceKeyguardLocked(nowUptime)
        val wakeGraceMs = settings.effectiveWakeGraceMs(isKeyguardLocked = isLocked)

        // Ignore taps occurring during the AOD / screen-wake transition to the normal Lock Screen
        if (BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = uptimeMillis,
                lastScreenWakeUptimeMillis = lastScreenWakeUptimeMillis,
                gracePeriodMs = wakeGraceMs
            ) || BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = nowUptime,
                lastScreenWakeUptimeMillis = lastScreenWakeUptimeMillis,
                gracePeriodMs = wakeGraceMs
            )
        ) {
            firstOutsideTap = null
            return
        }

        val screenConfig = buildRealtimeScreenBoundsConfig()

        // Check A (Part 1): Reject immediately if valid coordinates fall in top status bar or bottom nav bar
        if (BlankSpaceHitTester.isWithinEdgeIgnoreZone(rawX, rawY, screenConfig)) {
            firstOutsideTap = null
            return
        }

        val currentTap = TapSample(
            rawX = rawX,
            rawY = rawY,
            uptimeMillis = uptimeMillis
        )

        val previousTap = firstOutsideTap?.takeUnless {
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = it.uptimeMillis,
                lastScreenWakeUptimeMillis = lastScreenWakeUptimeMillis,
                gracePeriodMs = wakeGraceMs
            )
        }
        if (previousTap == null) {
            // Check A on Tap 1 when coordinates are present
            if (currentTap.hasValidCoordinates && hitsInteractiveNodeAt(rawX, rawY, screenConfig)) {
                firstOutsideTap = null
                return
            }
            firstOutsideTap = currentTap
            return
        }

        // Evaluate if previousTap and currentTap form a valid double-tap within timeout and slop
        val isPair = BlankSpaceHitTester.isDoubleTapPair(
            firstTap = previousTap,
            secondTap = currentTap,
            doubleTapTimeoutMs = settings.clampedDoubleTapTimeoutMs,
            config = screenConfig
        )

        if (!isPair) {
            // Current tap becomes the new First Tap candidate (if it passes Check A)
            if (currentTap.hasValidCoordinates && hitsInteractiveNodeAt(rawX, rawY, screenConfig)) {
                firstOutsideTap = null
            } else {
                firstOutsideTap = currentTap
            }
            return
        }

        // Check A (Part 2): Coordinate Hit-Test on Tap 2 (if rawX > 0 && rawY > 0)
        if (currentTap.hasValidCoordinates && hitsInteractiveNodeAt(rawX, rawY, screenConfig)) {
            firstOutsideTap = null
            return
        }

        // Valid double-tap candidate! Consume Tap 1 so a third tap won't re-trigger
        firstOutsideTap = null

        // Check B: Wait 60ms Event-Correlation Debounce before locking.
        // If any click, long-click, scroll, or window-state change occurs between Tap 1 and Tap 2 + 60ms,
        // cancel the lock immediately.
        val tap1Time = previousTap.uptimeMillis

        val debounceRunnable = Runnable {
            pendingDebounceRunnable = null
            val debounceNowUptime = SystemClock.uptimeMillis()
            if (!syncDisplayWakeState(debounceNowUptime)) {
                return@Runnable
            }
            val debounceSettings = currentSettings
            val isRecentUnlock = lastUserPresentUptimeMillis > 0L &&
                (debounceNowUptime - lastUserPresentUptimeMillis) < UNLOCK_TRANSITION_WINDOW_MS
            if (!isEligibleScreenContext(debounceSettings, allowTransitionCandidate = isRecentUnlock)) {
                return@Runnable
            }
            val debounceIsLocked = isDeviceKeyguardLocked(debounceNowUptime)
            val debounceWakeGraceMs = debounceSettings.effectiveWakeGraceMs(
                isKeyguardLocked = debounceIsLocked
            )
            if (BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                    tapUptimeMillis = tap1Time,
                    lastScreenWakeUptimeMillis = lastScreenWakeUptimeMillis,
                    gracePeriodMs = debounceWakeGraceMs
                ) || BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                    tapUptimeMillis = debounceNowUptime,
                    lastScreenWakeUptimeMillis = lastScreenWakeUptimeMillis,
                    gracePeriodMs = debounceWakeGraceMs
                )
            ) {
                return@Runnable
            }
            val interrupted = BlankSpaceHitTester.wasInterruptedByInteractiveEvent(
                tap1UptimeMillis = tap1Time,
                debounceEndUptimeMillis = debounceNowUptime,
                lastInteractiveEventUptimeMillis = lastInteractiveEventUptimeMillis
            )
            if (interrupted) {
                return@Runnable
            }
            executeFlickerProtectedLock()
        }

        pendingDebounceRunnable = debounceRunnable
        mainHandler.postDelayed(
            debounceRunnable,
            BlankSpaceHitTester.EVENT_CORRELATION_DEBOUNCE_MS
        )
    }

    private fun cancelPendingDoubleTapLock() {
        pendingDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDebounceRunnable = null
        pendingScreenshotTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingScreenshotTimeoutRunnable = null
        isScreenshotInProgress = false
    }

    private fun hitsInteractiveNodeAt(
        rawX: Float,
        rawY: Float,
        screenConfig: ScreenBoundsConfig
    ): Boolean {
        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return false
        return BlankSpaceHitTester.hitsInteractiveElement(
            rootNode = rootNode,
            rawX = rawX,
            rawY = rawY,
            screenWidthPx = screenConfig.screenWidthPx,
            screenHeightPx = screenConfig.screenHeightPx,
            ownWidgetContentDescription = getString(R.string.widget_content_description)
        )
    }

    /**
     * Verifies whether the user is currently on an enabled, unobscured SystemUI Lock Screen
     * (excluding showWhenLocked apps like Camera or recent-photo review, and excluding the PIN/Pattern bouncer)
     * or an enabled, unobscured Home Screen workspace (Check C).
     */
    private fun isEligibleScreenContext(
        settings: TapLockSettings,
        allowTransitionCandidate: Boolean = false
    ): Boolean {
        val windowsList = try {
            windows
        } catch (_: Exception) {
            null
        }
        val activeRootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        val activeRootPkg = activeRootNode?.packageName?.toString()
            ?: try {
                windowsList?.firstOrNull { it.isActive || it.isFocused }?.root?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
        val allowedLaunchers = resolveAllowedLauncherPackages()
        val maxBounds = windowManager.maximumWindowMetrics.bounds

        val now = SystemClock.uptimeMillis()
        val isRecentUnlock = lastUserPresentUptimeMillis > 0L &&
            (now - lastUserPresentUptimeMillis) < UNLOCK_TRANSITION_WINDOW_MS

        val effectiveActiveRootPkg = if (activeRootPkg == BlankSpaceHitTester.SYSTEM_UI_PACKAGE) {
            val hasObscuringShade = windowsList?.any { win ->
                win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                    maxBounds.height() > 0 &&
                    (win.isFocused || win.isActive || {
                        val r = Rect()
                        win.getBoundsInScreen(r)
                        (r.bottom - r.top) > (maxBounds.height() * 0.35f).toInt()
                    }())
            } ?: false
            if (!hasObscuringShade) null else activeRootPkg
        } else {
            activeRootPkg
        }

        if (settings.enableHomeScreen) {
            val isHome = BlankSpaceHitTester.isHomeWorkspaceUnobscured(
                windows = windowsList,
                activeRootPackageName = effectiveActiveRootPkg,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = maxBounds.height(),
                allowTransitionCandidate = allowTransitionCandidate || isRecentUnlock
            )
            if (isHome) {
                if (BlankSpaceHitTester.isAllAppsDrawerShowingInWindows(
                        windows = windowsList,
                        activeRootNode = activeRootNode,
                        allowedLauncherPackages = allowedLaunchers,
                        screenHeightPx = maxBounds.height()
                    )
                ) {
                    return false
                }
                lastUserPresentUptimeMillis = now
                lastScreenWakeUptimeMillis = 0L
                return true
            }
        }

        val isLocked = isDeviceKeyguardLocked(now)
        if (isLocked) {
            if (!settings.enableLockScreen) {
                return false
            }
            if (activeRootNode != null && maxBounds.height() > 0) {
                val rootBounds = Rect()
                activeRootNode.getBoundsInScreen(rootBounds)
                val rootHeight = rootBounds.bottom - rootBounds.top
                // Reject if the active SystemUI root node is just a status/nav bar or collapsed shade (< 50% height)
                if (rootHeight in 1 until (maxBounds.height() * 0.50f).toInt()) {
                    return false
                }
            }
            if (BlankSpaceHitTester.isLockScreenBouncerShowing(activeRootNode)) {
                return false
            }
            return BlankSpaceHitTester.isLockScreenUnobscured(
                windows = windowsList,
                activeRootPackageName = activeRootPkg,
                allowedLauncherPackages = allowedLaunchers,
                screenHeightPx = maxBounds.height()
            )
        }

        return false
    }

    private var lastLauncherResolveUptimeMs: Long = 0L
    private var cachedAllowedLauncherPackages: Set<String>? = null

    /**
     * Dynamically resolves the default Home launcher package via PackageManager.resolveActivity
     * for Intent.CATEGORY_HOME while explicitly supporting Pixel Launcher
     * (com.google.android.apps.nexuslauncher).
     */
    private fun resolveAllowedLauncherPackages(): Set<String> {
        val now = SystemClock.uptimeMillis()
        val cached = cachedAllowedLauncherPackages
        if (cached != null && (now - lastLauncherResolveUptimeMs) < LAUNCHER_CACHE_TTL_MS) {
            return cached
        }
        val packages = mutableSetOf(
            PIXEL_LAUNCHER_PACKAGE,
            AOSP_LAUNCHER_PACKAGE
        )
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        try {
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            val resolvedPkg = resolveInfo?.activityInfo?.packageName
            if (!resolvedPkg.isNullOrEmpty() && resolvedPkg != "android") {
                packages.add(resolvedPkg)
            }
        } catch (_: Exception) {
            // Fallback to known Pixel Launcher packages
        }
        cachedAllowedLauncherPackages = packages
        lastLauncherResolveUptimeMs = now
        return packages
    }

    /**
     * Queries real-time hardware metrics and system bar insets (never cached) to construct
     * [ScreenBoundsConfig] for Check A coordinate & edge-inset filtering.
     */
    private fun buildRealtimeScreenBoundsConfig(): ScreenBoundsConfig {
        val maxMetrics = windowManager.maximumWindowMetrics
        val bounds = maxMetrics.bounds
        val insets = maxMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.statusBars() or
                WindowInsets.Type.navigationBars() or
                WindowInsets.Type.displayCutout()
        )
        val minEdgeIgnorePx = (48f * resources.displayMetrics.density).toInt()
        val topIgnorePx = maxOf(insets.top, minEdgeIgnorePx)
        val bottomIgnorePx = maxOf(insets.bottom, minEdgeIgnorePx)
        val slopPx = ViewConfiguration.get(this).scaledDoubleTapSlop

        return ScreenBoundsConfig(
            screenWidthPx = bounds.width(),
            screenHeightPx = bounds.height(),
            topIgnoreInsetPx = topIgnorePx,
            bottomIgnoreInsetPx = bottomIgnorePx,
            doubleTapSlopPx = slopPx
        )
    }

    /**
     * Captures a real-time hardware buffer screenshot using [AccessibilityService.takeScreenshot].
     * Times out and falls back to null after [timeoutMs] (default 150ms) to ensure screen-locking
     * is never stalled or blocked.
     */
    fun captureDisplayScreenshot(
        timeoutMs: Long = SCREENSHOT_TIMEOUT_MS,
        onResult: (Bitmap?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }

        isScreenshotInProgress = true
        var hasDelivered = false
        val timeoutRunnable = Runnable {
            if (!hasDelivered && isScreenshotInProgress) {
                hasDelivered = true
                isScreenshotInProgress = false
                pendingScreenshotTimeoutRunnable = null
                onResult(null)
            }
        }
        pendingScreenshotTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        if (hasDelivered || !isScreenshotInProgress) {
                            try {
                                screenshotResult.hardwareBuffer.close()
                            } catch (_: Exception) {}
                            return
                        }
                        hasDelivered = true
                        isScreenshotInProgress = false
                        pendingScreenshotTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingScreenshotTimeoutRunnable = null

                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        } catch (_: Exception) {
                            null
                        }
                        try {
                            hardwareBuffer.close()
                        } catch (_: Exception) {}

                        onResult(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (hasDelivered || !isScreenshotInProgress) return
                        hasDelivered = true
                        isScreenshotInProgress = false
                        pendingScreenshotTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingScreenshotTimeoutRunnable = null
                        onResult(null)
                    }
                }
            )
        } catch (_: Exception) {
            if (!hasDelivered && isScreenshotInProgress) {
                hasDelivered = true
                isScreenshotInProgress = false
                pendingScreenshotTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingScreenshotTimeoutRunnable = null
                onResult(null)
            }
        }
    }

    /**
     * Executes GLOBAL_ACTION_LOCK_SCREEN (preserving biometric fingerprint & face unlock).
     * - When locking from the Home Screen with [TapLockSettings.simulatePowerButton] on (default),
     *   captures a pixel-aligned hardware screenshot of the Home Screen and displays it in the
     *   accessibility overlay during the turn-off animation so the Lock Screen never flashes.
     * - When locking from the Home Screen with [TapLockSettings.enableFlickerProtection] on (legacy),
     *   attaches the pure #000000 black overlay.
     * - When already on the Lock Screen with [TapLockSettings.enableLockScreenFlickerProtection] off
     *   (default), bypasses the overlay so SystemUI transitions directly into Always-On Display (AOD).
     */
    fun executeFlickerProtectedLock() {
        val controller = flickerController
        if (isScreenshotInProgress || controller?.isLockInProgress() == true) {
            return
        }
        cancelPendingDoubleTapLock()
        firstOutsideTap = null
        val settings = currentSettings
        val isLocked = isDeviceKeyguardLocked(SystemClock.uptimeMillis())

        val useSnapshot = settings.shouldUseSnapshotOverlay(isKeyguardLocked = isLocked)
        val useFlicker = settings.shouldUseFlickerProtection(isKeyguardLocked = isLocked)

        if (controller == null) {
            if (performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) {
                lastObservedInteractive = false
            }
            return
        }

        val performLock: () -> Boolean = {
            val locked = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            if (locked) {
                lastObservedInteractive = false
            }
            locked
        }

        if (useSnapshot) {
            captureDisplayScreenshot(SCREENSHOT_TIMEOUT_MS) { snapshotBitmap ->
                val showOverlay = snapshotBitmap != null || useFlicker
                controller.executeLockWithOverlay(
                    showOverlay = showOverlay,
                    snapshotBitmap = snapshotBitmap,
                    fadeDurationMs = settings.clampedFadeToBlackDurationMs,
                    preLockDelayMs = settings.clampedPreLockBlackoutDelayMs,
                    postLockHoldMs = settings.clampedPostLockHoldDurationMs,
                    performLockAction = performLock
                )
            }
        } else {
            controller.executeLockWithOverlay(
                showOverlay = useFlicker,
                snapshotBitmap = null,
                fadeDurationMs = settings.clampedFadeToBlackDurationMs,
                preLockDelayMs = settings.clampedPreLockBlackoutDelayMs,
                postLockHoldMs = settings.clampedPostLockHoldDurationMs,
                performLockAction = performLock
            )
        }
    }

    companion object {
        const val UNLOCK_TRANSITION_WINDOW_MS = 2000L
        const val SCREENSHOT_TIMEOUT_MS = 150L
        const val LAUNCHER_CACHE_TTL_MS = 5000L
        const val PIXEL_LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher"
        const val AOSP_LAUNCHER_PACKAGE = "com.android.launcher3"

        @Volatile
        private var instance: TapAccessibilityService? = null

        private val _serviceRunningFlow = MutableStateFlow(false)
        val serviceRunningFlow: StateFlow<Boolean> = _serviceRunningFlow.asStateFlow()

        /**
         * Returns current double-tap timeout in milliseconds for components like BlankSpaceWidgetReceiver.
         */
        fun getCurrentDoubleTapTimeoutMs(): Int {
            return instance?.currentSettings?.clampedDoubleTapTimeoutMs
                ?: TapLockSettings.DEFAULT_DOUBLE_TAP_TIMEOUT_MS
        }

        /**
         * Triggers the Flicker-Protected Lock immediately (used by MainActivity's
         * "Test Lock & Flicker Mask Now" button and by BlankSpaceWidgetReceiver).
         */
        fun triggerLockNow(): Boolean {
            val activeService = instance ?: return false
            activeService.executeFlickerProtectedLock()
            return true
        }

        /**
         * Checks whether TapAccessibilityService is enabled in system Accessibility settings
         * or actively bound in memory.
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expectedComponentName = ComponentName(context, TapAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            return enabledServicesSetting
                .split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it == expectedComponentName }
        }
    }
}

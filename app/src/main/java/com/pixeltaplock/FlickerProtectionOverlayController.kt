package com.pixeltaplock

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.doOnPreDraw

data class OverlayGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val offsetXPx: Int,
    val offsetYPx: Int
)

/**
 * Manages the True QHD+ Edge-to-Edge Flicker Protection Overlay.
 *
 * Queries real-time hardware display bounds via [WindowManager.getMaximumWindowMetrics] on every
 * lock invocation and display/configuration change (never caching dimensions), oversizes the pure
 * #000000 black surface by +200px with -100px offsets, bypasses cutouts and system bars, waits
 * for the physical first frame to render before invoking GLOBAL_ACTION_LOCK_SCREEN, and cleanly
 * removes the view via [WindowManager.removeViewImmediate] once Display enters STATE_OFF /
 * STATE_DOZE / STATE_DOZE_SUSPEND (or upon the hard 1200ms safety timeout).
 */
class FlickerProtectionOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val displayManager: DisplayManager
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeOverlayView: FrameLayout? = null
    private var activeSnapshotBitmap: Bitmap? = null
    private var activeSnapshotImageView: ImageView? = null
    private var activeFadeAnimator: ValueAnimator? = null
    private var isLockSequenceInFlight = false
    private var hasTriggeredPostOffRemoval = false
    private var lastAttachedBounds: Rect? = null

    fun isLockInProgress(): Boolean = isLockSequenceInFlight

    private var pendingPreLockDelayRunnable: Runnable? = null
    private var pendingPreDrawFallbackRunnable: Runnable? = null

    private val hardSafetyRemoveRunnable = Runnable {
        removeOverlayImmediately()
    }

    private val postOffHoldRemoveRunnable = Runnable {
        removeOverlayImmediately()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            // Refresh live geometry if display resolution/orientation changed while attached
            refreshOverlayBoundsIfAttached()

            if (activeOverlayView != null) {
                val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    if (isDisplayOffOrDozing(display.state)) {
                        if (activeSnapshotBitmap != null) {
                            // Turn-off transition is finished and panel has reached STATE_OFF or STATE_DOZE (AOD).
                            // Remove snapshot immediately so AOD clock is not delayed or obscured by a frozen Home Screen.
                            removeOverlayImmediately()
                        } else {
                            schedulePostOffHoldRemoval(currentHoldDurationMs)
                        }
                    } else if (hasTriggeredPostOffRemoval && display.state == Display.STATE_ON) {
                        removeOverlayImmediately()
                    }
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (activeOverlayView != null) {
                        schedulePostOffHoldRemoval(currentHoldDurationMs)
                    }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    // If the user immediately wakes the screen, never obscure AOD or Lock Screen
                    removeOverlayImmediately()
                }
            }
        }
    }

    private var isReceiverRegistered = false
    private var currentHoldDurationMs: Long = TapLockSettings.DEFAULT_POST_LOCK_HOLD_MS.toLong()

    fun startListening() {
        displayManager.registerDisplayListener(displayListener, mainHandler)
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
        }
    }

    fun stopListening() {
        removeOverlayImmediately()
        displayManager.unregisterDisplayListener(displayListener)
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered
            }
            isReceiverRegistered = false
        }
    }

    /**
     * Called from TapAccessibilityService.onConfigurationChanged and DisplayListener.onDisplayChanged
     * so runtime resolution switches (e.g., FHD+ 1080x2400 <-> QHD+ 1344x2992 / 1440x3120) or
     * orientation changes immediately resize any active overlay using fresh maximumWindowMetrics.
     */
    fun refreshOverlayBoundsIfAttached() {
        val view = activeOverlayView ?: return
        if (!view.isAttachedToWindow) return
        val maxBounds = queryRealtimeMaximumBounds()
        if (maxBounds == lastAttachedBounds) return
        lastAttachedBounds = Rect(maxBounds)
        val updatedParams = buildOverlayLayoutParams(maxBounds)
        try {
            windowManager.updateViewLayout(view, updatedParams)
            if (view.childCount > 0) {
                val child = view.getChildAt(0)
                val halfMargin = SAFETY_OVERSIZE_MARGIN_PX / 2
                val lp = FrameLayout.LayoutParams(
                    maxBounds.width(),
                    maxBounds.height()
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    setMargins(halfMargin, halfMargin, 0, 0)
                }
                child.layoutParams = lp
            }
        } catch (_: IllegalArgumentException) {
            // View was detached concurrently
        }
    }

    /**
     * Executes the lock sequence:
     * - If [enableFlickerProtection] is false, calls [performLockAction] immediately with no overlay.
     * - If [enableFlickerProtection] is true: attaches the solid black overlay before locking.
     */
    fun executeLockWithFlickerProtection(
        enableFlickerProtection: Boolean,
        preLockDelayMs: Int,
        postLockHoldMs: Int,
        performLockAction: () -> Boolean
    ) {
        executeLockWithOverlay(
            showOverlay = enableFlickerProtection,
            snapshotBitmap = null,
            fadeDurationMs = TapLockSettings.DEFAULT_FADE_TO_BLACK_DURATION_MS,
            preLockDelayMs = preLockDelayMs,
            postLockHoldMs = postLockHoldMs,
            performLockAction = performLockAction
        )
    }

    /**
     * Executes the lock sequence with an optional snapshot overlay or pure black overlay:
     * - If [showOverlay] is false, calls [performLockAction] immediately with no overlay.
     * - If [showOverlay] is true:
     *   Step 1: Queries fresh [WindowManager.getMaximumWindowMetrics] bounds and attaches the
     *           full-bleed overlay view. If [snapshotBitmap] is provided, displays the snapshot
     *           pixel-aligned with the physical display so the home screen remains visible without
     *           showing the Lock Screen during turn-off (replicating the power button).
     *   Step 2: Starts a smooth [LinearInterpolator] fade-to-black animation on the snapshot
     *           overlay over [fadeDurationMs] milliseconds as soon as the first frame renders.
     *   Step 3: Waits for the view's first frame to render (doOnPreDraw + Choreographer frame
     *           callback + [preLockDelayMs]) so the frame is physically visible before locking.
     *   Step 4: Invokes [performLockAction] (GLOBAL_ACTION_LOCK_SCREEN).
     *   Step 5: Removes the overlay via [WindowManager.removeViewImmediate] and recycles any
     *           snapshot bitmap once Display reaches STATE_OFF / STATE_DOZE / STATE_DOZE_SUSPEND
     *           + [postLockHoldMs], or at the hard safety timeout.
     */
    fun executeLockWithOverlay(
        showOverlay: Boolean,
        snapshotBitmap: Bitmap? = null,
        fadeDurationMs: Int = TapLockSettings.DEFAULT_FADE_TO_BLACK_DURATION_MS,
        preLockDelayMs: Int,
        postLockHoldMs: Int,
        performLockAction: () -> Boolean
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                executeLockWithOverlay(
                    showOverlay = showOverlay,
                    snapshotBitmap = snapshotBitmap,
                    fadeDurationMs = fadeDurationMs,
                    preLockDelayMs = preLockDelayMs,
                    postLockHoldMs = postLockHoldMs,
                    performLockAction = performLockAction
                )
            }
            return
        }

        if (!showOverlay) {
            removeOverlayImmediately()
            if (snapshotBitmap != null && !snapshotBitmap.isRecycled) {
                try {
                    snapshotBitmap.recycle()
                } catch (_: Exception) {}
            }
            performLockAction()
            return
        }

        if (isLockSequenceInFlight) {
            if (snapshotBitmap != null && !snapshotBitmap.isRecycled) {
                try {
                    snapshotBitmap.recycle()
                } catch (_: Exception) {}
            }
            return
        }
        isLockSequenceInFlight = true
        hasTriggeredPostOffRemoval = false

        val clampedPreDelayMs = preLockDelayMs.coerceIn(
            TapLockSettings.MIN_PRE_LOCK_DELAY_MS,
            TapLockSettings.MAX_PRE_LOCK_DELAY_MS
        ).toLong()
        val clampedPostHoldMs = postLockHoldMs.coerceIn(
            TapLockSettings.MIN_POST_LOCK_HOLD_MS,
            TapLockSettings.MAX_POST_LOCK_HOLD_MS
        ).toLong()
        val clampedFadeMs = fadeDurationMs.coerceIn(
            TapLockSettings.MIN_FADE_TO_BLACK_DURATION_MS,
            TapLockSettings.MAX_FADE_TO_BLACK_DURATION_MS
        ).toLong()
        currentHoldDurationMs = clampedPostHoldMs

        // Ensure any stale view, bitmap, or callbacks are cleaned up before attaching a fresh one
        cancelPreLockCallbacks()
        removeOverlayViewOnly()

        // Step 1: Query real-time hardware maximumWindowMetrics (never cached) and attach overlay
        val realtimeBounds = queryRealtimeMaximumBounds()
        lastAttachedBounds = Rect(realtimeBounds)
        val layoutParams = buildOverlayLayoutParams(realtimeBounds)
        val overlayView = createOverlayView(snapshotBitmap, realtimeBounds)
        activeSnapshotBitmap = snapshotBitmap

        val attached = try {
            windowManager.addView(overlayView, layoutParams)
            activeOverlayView = overlayView
            true
        } catch (_: Exception) {
            activeOverlayView = null
            false
        }

        if (!attached) {
            isLockSequenceInFlight = false
            activeSnapshotBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    try {
                        bmp.recycle()
                    } catch (_: Exception) {}
                }
            }
            activeSnapshotBitmap = null
            performLockAction()
            return
        }

        // Schedule initial hard safety timeout (guaranteed removal even if display callbacks are missed)
        val hardSafetyTimeoutMs = computeInitialHardSafetyTimeoutMs(
            clampedPreDelayMs = clampedPreDelayMs,
            clampedPostHoldMs = clampedPostHoldMs,
            clampedFadeDurationMs = clampedFadeMs
        )
        mainHandler.removeCallbacks(hardSafetyRemoveRunnable)
        mainHandler.postDelayed(hardSafetyRemoveRunnable, hardSafetyTimeoutMs)

        val startFadeAnimationIfPresent = {
            if (snapshotBitmap != null && clampedFadeMs > 0L && activeFadeAnimator == null) {
                val animator = ValueAnimator.ofFloat(1.0f, 0.0f).apply {
                    duration = clampedFadeMs
                    interpolator = LinearInterpolator()
                    addUpdateListener { va ->
                        val alphaVal = va.animatedValue as Float
                        activeSnapshotImageView?.alpha = alphaVal
                    }
                }
                activeFadeAnimator = animator
                animator.start()
            }
        }

        // Step 2: Wait for first frame draw + preLockDelayMs before firing GLOBAL_ACTION_LOCK_SCREEN
        var lockActionFired = false
        val fireLockOnce = {
            if (!lockActionFired && isLockSequenceInFlight && activeOverlayView != null) {
                lockActionFired = true
                cancelPreLockCallbacks()
                val lockSucceeded = performLockAction()
                if (!lockSucceeded) {
                    removeOverlayImmediately()
                } else {
                    // Check if display already transitioned to OFF/DOZE immediately
                    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    if (display != null && isDisplayOffOrDozing(display.state)) {
                        if (activeSnapshotBitmap != null) {
                            removeOverlayImmediately()
                        } else {
                            schedulePostOffHoldRemoval(clampedPostHoldMs)
                        }
                    }
                }
            }
        }

        overlayView.doOnPreDraw {
            // View traversal reached pre-draw; cancel the pre-draw fallback timer so it cannot cut short preLockDelayMs
            pendingPreDrawFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingPreDrawFallbackRunnable = null

            Choreographer.getInstance().postFrameCallback {
                if (!isLockSequenceInFlight || activeOverlayView == null) {
                    return@postFrameCallback
                }
                startFadeAnimationIfPresent()
                if (clampedPreDelayMs <= 0L) {
                    fireLockOnce()
                } else {
                    val delayRunnable = Runnable {
                        pendingPreLockDelayRunnable = null
                        fireLockOnce()
                    }
                    pendingPreLockDelayRunnable = delayRunnable
                    mainHandler.postDelayed(delayRunnable, clampedPreDelayMs)
                }
            }
        }

        // Fallback only in case doOnPreDraw never fires due to a stalled WindowManager transaction
        val fallbackRunnable = Runnable {
            pendingPreDrawFallbackRunnable = null
            startFadeAnimationIfPresent()
            fireLockOnce()
        }
        pendingPreDrawFallbackRunnable = fallbackRunnable
        mainHandler.postDelayed(
            fallbackRunnable,
            clampedPreDelayMs + PRE_DRAW_FALLBACK_SLACK_MS
        )
    }

    private fun schedulePostOffHoldRemoval(holdDurationMs: Long) {
        if (hasTriggeredPostOffRemoval) return
        hasTriggeredPostOffRemoval = true
        mainHandler.removeCallbacks(postOffHoldRemoveRunnable)
        mainHandler.postDelayed(postOffHoldRemoveRunnable, holdDurationMs)
        // Ensure hard safety timeout does not cut short the configured post-off hold buffer
        mainHandler.removeCallbacks(hardSafetyRemoveRunnable)
        mainHandler.postDelayed(hardSafetyRemoveRunnable, holdDurationMs + 250L)
    }

    private fun cancelPreLockCallbacks() {
        pendingPreLockDelayRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPreLockDelayRunnable = null
        pendingPreDrawFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPreDrawFallbackRunnable = null
    }

    fun removeOverlayImmediately() {
        cancelPreLockCallbacks()
        mainHandler.removeCallbacks(hardSafetyRemoveRunnable)
        mainHandler.removeCallbacks(postOffHoldRemoveRunnable)
        removeOverlayViewOnly()
        isLockSequenceInFlight = false
        hasTriggeredPostOffRemoval = false
    }

    private fun removeOverlayViewOnly() {
        val view = activeOverlayView
        activeOverlayView = null
        lastAttachedBounds = null
        activeFadeAnimator?.cancel()
        activeFadeAnimator = null
        activeSnapshotImageView = null
        activeSnapshotBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                try {
                    bmp.recycle()
                } catch (_: Exception) {}
            }
        }
        activeSnapshotBitmap = null
        if (view != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: IllegalArgumentException) {
                // View was already detached
            } catch (_: Exception) {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun queryRealtimeMaximumBounds(): Rect {
        return windowManager.maximumWindowMetrics.bounds
    }

    private fun createOverlayView(snapshotBitmap: Bitmap?, bounds: Rect): FrameLayout {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 1.0f
            fitsSystemWindows = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnApplyWindowInsetsListener { _, _ -> WindowInsets.CONSUMED }
            if (snapshotBitmap == null) {
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.windowInsetsController?.let { controller ->
                            controller.hide(
                                WindowInsets.Type.statusBars() or
                                    WindowInsets.Type.navigationBars() or
                                    WindowInsets.Type.systemBars() or
                                    WindowInsets.Type.displayCutout()
                            )
                            controller.systemBarsBehavior =
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            }
        }

        if (snapshotBitmap != null && !snapshotBitmap.isRecycled) {
            val imageView = ImageView(context).apply {
                setImageBitmap(snapshotBitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                fitsSystemWindows = false
                isClickable = false
                isFocusable = false
                alpha = 1.0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            activeSnapshotImageView = imageView
            val halfMargin = SAFETY_OVERSIZE_MARGIN_PX / 2
            val lp = FrameLayout.LayoutParams(
                bounds.width(),
                bounds.height()
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(halfMargin, halfMargin, 0, 0)
            }
            root.addView(imageView, lp)
        }

        return root
    }

    private fun createPureBlackOverlayView(): FrameLayout {
        return createOverlayView(null, queryRealtimeMaximumBounds())
    }

    @Suppress("DEPRECATION")
    private fun buildOverlayLayoutParams(maxBounds: Rect): WindowManager.LayoutParams {
        val geometry = calculateOversizedOverlayGeometry(
            maxBoundsWidth = maxBounds.width(),
            maxBoundsHeight = maxBounds.height(),
            safetyMarginPx = SAFETY_OVERSIZE_MARGIN_PX
        )

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            geometry.widthPx,
            geometry.heightPx,
            geometry.offsetXPx,
            geometry.offsetYPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Bypass all WindowManager inset fitting (statusBars, navigationBars, displayCutout)
            // even when FLAG_NOT_FOCUSABLE is set on Android 12-17.
            fitInsetsTypes = 0
            fitInsetsSides = 0
            isFitInsetsIgnoringVisibility = false
            title = "PixelTapLockFlickerGuard"
        }
    }

    companion object {
        const val SAFETY_OVERSIZE_MARGIN_PX = 200
        const val HARD_SAFETY_TIMEOUT_MS = 1200L
        const val PRE_DRAW_FALLBACK_SLACK_MS = 120L

        fun computeInitialHardSafetyTimeoutMs(
            clampedPreDelayMs: Long,
            clampedPostHoldMs: Long,
            clampedFadeDurationMs: Long = 0L
        ): Long {
            return maxOf(
                HARD_SAFETY_TIMEOUT_MS,
                clampedPreDelayMs + clampedPostHoldMs + clampedFadeDurationMs + 500L
            )
        }

        /**
         * Computes the oversized full-bleed overlay geometry from real-time hardware bounds.
         * Adds [safetyMarginPx] (default 200px) and shifts origin by -(safetyMarginPx / 2)
         * so there are zero sub-pixel gaps around rounded corners, camera cutouts, or system bars
         * at any resolution (including Full QHD+ 1344x2992 and 1440x3120).
         */
        fun calculateOversizedOverlayGeometry(
            maxBoundsWidth: Int,
            maxBoundsHeight: Int,
            safetyMarginPx: Int = SAFETY_OVERSIZE_MARGIN_PX
        ): OverlayGeometry {
            val safeMargin = safetyMarginPx.coerceAtLeast(0)
            val safeWidth = maxBoundsWidth.coerceAtLeast(1) + safeMargin
            val safeHeight = maxBoundsHeight.coerceAtLeast(1) + safeMargin
            val negativeOffset = -(safeMargin / 2)
            return OverlayGeometry(
                widthPx = safeWidth,
                heightPx = safeHeight,
                offsetXPx = negativeOffset,
                offsetYPx = negativeOffset
            )
        }

        /**
         * Returns true when the physical OLED panel has entered Off, Doze (AOD), or Doze/On Suspend.
         */
        fun isDisplayOffOrDozing(displayState: Int): Boolean {
            return displayState == Display.STATE_OFF ||
                displayState == Display.STATE_DOZE ||
                displayState == Display.STATE_DOZE_SUSPEND ||
                displayState == Display.STATE_ON_SUSPEND
        }
    }
}

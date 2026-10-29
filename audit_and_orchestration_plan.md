# PixelTapLock (Tap2Sleep for Pixel) — Principal Systems Audit & Antigravity Multi-Agent Orchestration Plan

**Target Hardware:** Google Pixel 11 Pro (LTPO OLED, FHD+ $1080 \times 2400$ / QHD+ $1344 \times 2992$ & $1440 \times 3120$)  
**Target OS:** Android 15–17 (API 35–36)  
**Primary Orchestrator:** Gemini 3.8 Flash Lead Agent  
**Execution Subagents:** 4 Specialized Gemini Pro Subagents (`Pro-Overlay-Engine`, `Pro-Touch-And-HitTest`, `Pro-State-And-UI`, `Pro-Build-And-ADB-QA`)

---

## Part 1: Deep-Dive Technical Audit (Codebase Flaw Analysis)

### 1. Memory, Lifecycle & Resource Leaks

#### 1.1 Premature Hardware `Bitmap.recycle()` Before `WindowManager.removeViewImmediate()` (`CRITICAL`)
- **Location:** `FlickerProtectionOverlayController.kt`, `removeOverlayViewOnly()`, lines 368–395.
- **Exact Code Flaw:**
  ```kotlin
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
      } ...
  ```
- **Root Cause:** `bmp.recycle()` is invoked **before** `windowManager.removeViewImmediate(view)` and without clearing `ImageView.setImageBitmap(null)`. When `activeSnapshotBitmap` wraps a `HardwareBuffer` (`Bitmap.wrapHardwareBuffer` in `TapAccessibilityService.kt` line 646) and `RenderThread` is mid-frame or `removeViewImmediate` triggers synchronous detachment traversal, recycling the backing memory while the attached `ImageView` still holds its native pointer triggers a fatal `RuntimeException: Canvas: trying to use a recycled bitmap` on `RenderThread`.

#### 1.2 Duplicate Listener Registration & Controller Leak on Repeated `onServiceConnected()` (`HIGH`)
- **Location:** `TapAccessibilityService.kt`, `onServiceConnected()`, lines 147–162.
- **Exact Code Flaw:**
  ```kotlin
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
  ```
- **Root Cause:** If the Android Accessibility framework re-invokes `onServiceConnected()` (e.g., on dynamic service info updates or configuration re-binds without `onUnbind()`), `flickerController` is overwritten without calling `flickerController?.stopListening()`. The previous `FlickerProtectionOverlayController` instance leaks its registered `DisplayManager.DisplayListener` and `BroadcastReceiver` (`screenReceiver`), holding an implicit reference to the `Service` context.

#### 1.3 Excessive Main-Thread IPC Binder Traversal & Un-recycled `AccessibilityWindowInfo` / `AccessibilityNodeInfo` Trees (`HIGH`)
- **Location:** `TapAccessibilityService.kt`, `onOutsideTouchDetected()`, lines 351–424 & `isEligibleScreenContext()`, lines 494–584; `BlankSpaceHitTester.kt`, lines 321–383, 616–646, 703–746, 800–831, 906–949.
- **Exact Code Flaw:**
  - On a single outside touch in `onOutsideTouchDetected()`:
    1. Lines 352–353 call `windows` and `rootInActiveWindow`, passing them to `isOverviewShowingInWindows()` (which calls `window.root` on every window and walks up to depth 35 via `node.getChild(i)`).
    2. Line 381 calls `isEligibleScreenContext()`, which **again** calls `windows` and `rootInActiveWindow` (lines 497–505), invokes `isHomeWorkspaceUnobscured()` (calling `window.root` on every window), `isAllAppsDrawerShowingInWindows()` (calling `window.root` and walking depth 35), and `isOverviewShowingInWindows()` **a second time in the same touch event**.
    3. Line 418 calls `hitsInteractiveNodeAt()`, which calls `rootInActiveWindow` a **third time** and walks depth 40.
    4. Line 452 (`debounceRunnable`) calls `isEligibleScreenContext()` a **fourth time** 60ms later.
- **Root Cause:** Each `windows`, `rootInActiveWindow`, `window.root`, and `node.getChild(i)` call crosses the IPC Binder boundary if not cached per touch pass, causing 3–4 redundant full-tree traversals on the Main UI Thread per tap and starving 120Hz frame pacing. Furthermore, with `minSdk = 31` (`build.gradle.kts` line 13), `AccessibilityWindowInfo` and `AccessibilityNodeInfo` instances allocated on API 31–32 are never recycled in `finally` blocks.

#### 1.4 Unstructured `Handler` / `Runnable` Callback Sprawl Instead of Lifecycle-Bound Coroutines (`MEDIUM`)
- **Location:** `FlickerProtectionOverlayController.kt`, lines 46, 57–66, 255–358; `TapAccessibilityService.kt`, lines 49, 64–66, 448–492.
- **Root Cause:** Both classes mix raw `Handler(Looper.getMainLooper())` delayed `Runnable` objects (`pendingPreLockDelayRunnable`, `pendingPreDrawFallbackRunnable`, `hardSafetyRemoveRunnable`, `postOffHoldRemoveRunnable`, `pendingDebounceRunnable`, `pendingScreenshotTimeoutRunnable`) with manual boolean flags (`isLockSequenceInFlight`, `hasTriggeredPostOffRemoval`, `lockActionFired`, `isScreenshotInProgress`). If an exception or rapid state change occurs between callbacks, state flags can desynchronize. Replacing raw `Handler` timers with cancellable `Job` coroutines in a single `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` eliminates dangling callbacks.

---

### 2. State Synchronization & Reactive Data Flow

#### 2.1 Cold DataStore `Flow` & Multi-Instance Repository Desynchronization (`HIGH`)
- **Location:** `TapLockPreferences.kt`, lines 113–183; `MainActivity.kt`, lines 72, 103; `TapAccessibilityService.kt`, lines 134–143; `BlankSpaceWidgetReceiver.kt`, lines 48–51.
- **Exact Code Flaw:**
  - `MainActivity` creates its own `TapLockPreferencesRepository(applicationContext.tapLockDataStore)` (line 72) and collects `repository.settingsFlow.collectAsState(initial = TapLockSettings())` (line 103).
  - `TapAccessibilityService` creates a second `TapLockPreferencesRepository` instance (line 134) and collects it into `@Volatile private var currentSettings`.
  - `BlankSpaceWidgetReceiver` reads `TapAccessibilityService.getCurrentDoubleTapTimeoutMs()` via a static `instance` reference (line 49) and ignores `enableHomeScreen`.
- **Root Cause:** Because `settingsFlow` is a cold `Flow` rather than a process-wide hot `StateFlow<TapLockSettings>` backed by a singleton `PreferencesRepository`, `MainActivity` briefly emits uncalibrated default values on first composition before disk I/O completes, causing Compose sliders (`remember(settings.clampedDoubleTapTimeoutMs)`) to visually snap. Moreover, live changes to `postLockHoldDurationMs` do not dynamically update an in-flight `FlickerProtectionOverlayController` (`currentHoldDurationMs` at line 112 of `FlickerProtectionOverlayController.kt`).

#### 2.2 Missing Play Store / Android 15–17 Prominent Disclosure Dialog (`HIGH`)
- **Location:** `MainActivity.kt`, `ServiceStatusCard()`, lines 242–347.
- **Root Cause:** Tapping `"Enable Service"` (line 295) immediately fires `openAccessibilitySettings(context)` without displaying a mandatory Prominent Disclosure dialog explaining why `AccessibilityService` is required (detecting double-taps on empty Home/Lock screen areas and rendering a full-bleed OLED black/snapshot overlay before `GLOBAL_ACTION_LOCK_SCREEN`) and confirming that no personal or screen data is stored or transmitted off-device.

---

### 3. Android 15–17 (API 35–36) OS Compliance & Touch Hardening

#### 3.1 Critical `SYSTEM_UI_PACKAGE` Exemption Bug in `onAccessibilityEvent()` (`CRITICAL`)
- **Location:** `TapAccessibilityService.kt`, `onAccessibilityEvent()`, lines 217–225.
- **Exact Code Flaw:**
  ```kotlin
  if (isInteractiveEvent) {
      val isSystemUiUnlockTransition = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
          pkgStr == BlankSpaceHitTester.SYSTEM_UI_PACKAGE

      if (!isSystemUiUnlockTransition) {
          lastInteractiveEventUptimeMillis = now
          firstOutsideTap = null
          cancelPendingDoubleTapLock()
      }
  }
  ```
- **Root Cause:** Every `TYPE_WINDOW_STATE_CHANGED` event emitted by `com.android.systemui` is unconditionally treated as `isSystemUiUnlockTransition = true` and **ignored**! When a user pulls down the Notification Shade, expands Quick Settings, opens the Volume slider, expands a Lock Screen notification, or triggers the Keyguard Bouncer, `com.android.systemui` dispatches `TYPE_WINDOW_STATE_CHANGED`. Because this block ignores all SystemUI window state changes, `lastInteractiveEventUptimeMillis` is never updated and `firstOutsideTap` is never cleared.

#### 3.2 Blind Spot Under `(0f, 0f)` `ACTION_OUTSIDE` Coordinate Masking (`CRITICAL`)
- **Location:** `TapAccessibilityService.kt`, `onOutsideTouchDetected()`, lines 401–444; `BlankSpaceHitTester.kt`, lines 23–25, 224–236, 321–331.
- **Exact Code Flaw:**
  - On Android 15–17 (API 35–36), `MotionEvent.ACTION_OUTSIDE` delivered to a $1 \times 1$ `TYPE_ACCESSIBILITY_OVERLAY` window frequently masks `rawX` and `rawY` to `(0f, 0f)`.
  - When `rawX == 0f && rawY == 0f`, `currentTap.hasValidCoordinates` is `false`. Consequently:
    1. `BlankSpaceHitTester.isWithinEdgeIgnoreZone(rawX, rawY, screenConfig)` returns `false` (line 229).
    2. `hitsInteractiveNodeAt(rawX, rawY, screenConfig)` on Tap 1 (line 418) and Tap 2 (line 440) is **completely bypassed**.
    3. Distance slop verification in `isDoubleTapPair()` (line 250) is **completely bypassed**.
- **Root Cause:** When coordinates are masked to `(0f, 0f)`, the service relies entirely on Check B (`60ms` `EVENT_CORRELATION_DEBOUNCE_MS`) and Check C (`isEligibleScreenContext`). However:
  1. `accessibility_service_config.xml` does not subscribe to `typeViewFocused` or `typeViewSelected`, and `BlankSpaceHitTester.isInterruptingAccessibilityEvent()` only checks `TYPE_VIEW_CLICKED`, `TYPE_VIEW_LONG_CLICKED`, `TYPE_VIEW_SCROLLED`, and `TYPE_WINDOW_STATE_CHANGED`.
  2. In `TapAccessibilityService.kt` (line 301), `tapUptime` uses `motionEvent.eventTime` (kernel touch-down time), whereas `lastInteractiveEventUptimeMillis` in `onAccessibilityEvent` uses `SystemClock.uptimeMillis()` (accessibility dispatch time). If Tap 1's `TYPE_VIEW_CLICKED` was processed slightly before `tap1Time` or if an app icon/widget dispatches its click/window transition at $65\text{ms}$–$85\text{ms}$ on a busy frame, `wasInterruptedByInteractiveEvent` misses it or `60ms` expires early.

#### 3.3 Notification Shade & Quick Settings Panel False-Positive Locks on Home & Lock Screen (`CRITICAL`)
- **Location:** `TapAccessibilityService.kt`, `isEligibleScreenContext()`, lines 513–583; `BlankSpaceHitTester.kt`, `isLockScreenUnobscured()`, lines 505–568.
- **Exact Code Flaw:**
  - On the **Lock Screen** (`isLocked == true`), `activeRootPkg` is always `"com.android.systemui"` and the SystemUI window occupies $100\%$ of the display height. `isLockScreenUnobscured()` returns `true` even when the user has pulled down the **Quick Settings panel** or expanded a notification shade over the Lock Screen!
  - On the **Home Screen**, `effectiveActiveRootPkg` (lines 513–526) checks if a `TYPE_SYSTEM` window is focused or $>35\%$ height. On Pixel Android 15–17, the Notification Shade and Quick Settings live inside the unified `NotificationShade` SystemUI window (`KeyguardRootView` / `NotificationPanelView` / `QuickSettingsContainer`), where Quick Settings tiles (`QSTileView`, `qs_frame`, `qs_panel`, `quick_settings_panel`, `shade_header_system_icons`) are visible in the node tree.
- **Root Cause:** Neither `isHomeWorkspaceUnobscured()` nor `isLockScreenUnobscured()` inspects the active SystemUI node tree for visible Quick Settings tiles (`QSTileView`, `qs_frame`, `qs_panel`, `qqs_Low`, `split_shade`) or an expanded notification shade when on the Home or Lock Screen. If coordinates are masked to `(0f, 0f)` (or if the user double-taps blank space inside the pulled-down Quick Settings shade), the device falsely locks!

---

### 4. QHD+ LTPO OLED Flicker-Protection Precision

#### 4.1 Premature `GLOBAL_ACTION_LOCK_SCREEN` Before SurfaceFlinger Frame Commit (`CRITICAL`)
- **Location:** `FlickerProtectionOverlayController.kt`, `executeLockWithOverlay()`, lines 300–332.
- **Exact Code Flaw:**
  ```kotlin
  overlayView.doOnPreDraw {
      ...
      Choreographer.getInstance().postFrameCallback {
          ...
          if (clampedPreDelayMs <= 0L) {
              fireLockOnce()
          } else {
              ...
              mainHandler.postDelayed(delayRunnable, clampedPreDelayMs)
          }
      }
  }
  ```
- **Root Cause:** `doOnPreDraw` executes during the UI thread's `performTraversals()` pass *before* the DisplayList is recorded and synced to `RenderThread`. Posting a single `Choreographer.postFrameCallback` inside `doOnPreDraw` fires on the very next VSYNC pulse on the UI thread—while `RenderThread` and `SurfaceFlinger` may not have latched and presented the buffer to the QHD+ LTPO panel yet. Furthermore, when `snapshotBitmap != null`, `ValueAnimator` animates alpha on the CPU/UI thread without hardware layer backing (`View.LAYER_TYPE_HARDWARE`), and `WindowInsetsController.hide()` (lines 405–415) is called on a `FLAG_NOT_FOCUSABLE` window, triggering unnecessary SystemUI inset transactions during blackout.

#### 4.2 Race Condition Between `DisplayListener.onDisplayChanged` and `ACTION_SCREEN_OFF` (`HIGH`)
- **Location:** `FlickerProtectionOverlayController.kt`, lines 77–108.
- **Exact Code Flaw:**
  - In `onDisplayChanged` (lines 83–90): if `activeSnapshotBitmap != null`, it calls `removeOverlayImmediately()` as soon as `display.state` reports `STATE_DOZE` or `STATE_OFF` (ignoring `postLockHoldMs`).
  - In `screenReceiver` (lines 99–103): on `Intent.ACTION_SCREEN_OFF`, it unconditionally calls `schedulePostOffHoldRemoval(currentHoldDurationMs)` even when `activeSnapshotBitmap != null`, setting `hasTriggeredPostOffRemoval = true`.
- **Root Cause:** Depending on whether `Intent.ACTION_SCREEN_OFF` or `DisplayListener.onDisplayChanged` arrives first on the main looper, the overlay either detaches instantaneously (causing a brief 1-frame flash as the hardware composer settles into AOD) or holds for the full `postLockHoldMs` without deterministic state coordination.

---

## Section 1: Orchestrator Directives (For Gemini 3.8 Flash Lead Agent)

As the **Gemini 3.8 Flash Primary Orchestrator** running inside Google Antigravity, you must enforce the following execution protocol when spawning and coordinating the 4 **Gemini Pro Subagents**:

1. **Strict Phase Sequencing & Zero File-Collision Policy:**
   - **Never** allow two Pro Subagents to edit the same file concurrently.
   - Execute the refactoring in **4 sequential waves** (or parallel waves with strictly disjoint file ownership as defined below):
     - **Wave 1 (Parallel Allowed — Disjoint Files):**
       - Spawn **`Pro-Overlay-Engine`** (Owns: `FlickerOverlayManager.kt` [new], `FlickerProtectionOverlayController.kt` [adapter/refactor]).
       - Spawn **`Pro-State-And-UI`** (Owns: `TapLockPreferences.kt`, `MainActivity.kt`, `strings.xml`).
     - **Wave 2 (Depends on Wave 1 APIs):**
       - Spawn **`Pro-Touch-And-HitTest`** (Owns: `TapGestureDetector.kt` [new], `BlankSpaceHitTester.kt`, `TapAccessibilityService.kt`, `BlankSpaceWidgetReceiver.kt`).
     - **Wave 3 (Build, Manifest & Hardware Verification):**
       - Spawn **`Pro-Build-And-ADB-QA`** (Owns: `AndroidManifest.xml`, `accessibility_service_config.xml`, `build.gradle.kts`, unit tests, Gradle build, and live Pixel 11 Pro ADB verification).

2. ** Strict File Ownership Matrix:**

| Subagent ID | Permitted Create / Modify Files | Forbidden Files |
| :--- | :--- | :--- |
| **`Pro-Overlay-Engine`** | `app/src/main/java/com/pixeltaplock/FlickerOverlayManager.kt`<br>`app/src/main/java/com/pixeltaplock/FlickerProtectionOverlayController.kt` | `MainActivity.kt`, `TapAccessibilityService.kt`, `TapLockPreferences.kt`, `BlankSpaceHitTester.kt` |
| **`Pro-Touch-And-HitTest`** | `app/src/main/java/com/pixeltaplock/TapGestureDetector.kt`<br>`app/src/main/java/com/pixeltaplock/BlankSpaceHitTester.kt`<br>`app/src/main/java/com/pixeltaplock/TapAccessibilityService.kt`<br>`app/src/main/java/com/pixeltaplock/BlankSpaceWidgetReceiver.kt` | `FlickerOverlayManager.kt`, `MainActivity.kt`, `TapLockPreferences.kt` |
| **`Pro-State-And-UI`** | `app/src/main/java/com/pixeltaplock/TapLockPreferences.kt`<br>`app/src/main/java/com/pixeltaplock/MainActivity.kt`<br>`app/src/main/res/values/strings.xml` | `TapAccessibilityService.kt`, `FlickerOverlayManager.kt`, `BlankSpaceHitTester.kt` |
| **`Pro-Build-And-ADB-QA`** | `app/src/main/AndroidManifest.xml`<br>`app/src/main/res/xml/accessibility_service_config.xml`<br>`app/build.gradle.kts`<br>`app/src/test/java/com/pixeltaplock/*` | All production `.kt` files unless executing a self-healing compilation fix |

3. **Compilation Gate Rules:**
   - Do **not** run `./gradlew assembleDebug` during Wave 1 while `TapAccessibilityService.kt` is still awaiting Wave 2 integration.
   - Run `./gradlew testDebugUnitTest assembleDebug` immediately upon completion of Wave 2.
   - If any compilation or unit test error occurs, route the exact compiler stack trace back to the owning Pro Subagent before advancing to Wave 3 ADB hardware verification.

---

## Section 2: Prioritized Findings Matrix

| ID | Severity | Target File(s) | Exact Location / Method | Root Cause | Assigned Subagent |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **F-01** | `CRITICAL` | `FlickerProtectionOverlayController.kt` | `removeOverlayViewOnly()` (lines 368–395) | `activeSnapshotBitmap.recycle()` is called *before* `windowManager.removeViewImmediate(view)` and before clearing `ImageView`, risking a `Canvas: trying to use a recycled bitmap` crash on `RenderThread`. | `Pro-Overlay-Engine` |
| **F-02** | `CRITICAL` | `TapAccessibilityService.kt` | `onAccessibilityEvent()` (lines 217–225) | `isSystemUiUnlockTransition` ignores **all** `TYPE_WINDOW_STATE_CHANGED` events from `com.android.systemui`, failing to cancel pending double-tap locks when Notification Shade, Quick Settings, Bouncer, or Volume dialogs open. | `Pro-Touch-And-HitTest` |
| **F-03** | `CRITICAL` | `TapAccessibilityService.kt`<br>`BlankSpaceHitTester.kt` | `onOutsideTouchDetected()` (lines 401–444)<br>`isLockScreenUnobscured()` (lines 505–568) | When Android 15–17 masks `ACTION_OUTSIDE` coordinates to `(0f, 0f)`, spatial hit-testing (`hitsInteractiveNodeAt`) and edge insets are bypassed. Additionally, Quick Settings / Notification Shade expansion on the Lock Screen or Home Screen is not detected via DOM node inspection. | `Pro-Touch-And-HitTest` |
| **F-04** | `CRITICAL` | `FlickerProtectionOverlayController.kt` | `executeLockWithOverlay()` (lines 300–332) | Single `Choreographer.postFrameCallback` inside `doOnPreDraw` fires on the UI thread before `SurfaceFlinger` commits the black overlay buffer to the physical QHD+ panel; `WindowInsetsController.hide()` is also unnecessarily invoked on a `FLAG_NOT_FOCUSABLE` window. | `Pro-Overlay-Engine` |
| **F-05** | `HIGH` | `TapAccessibilityService.kt` | `onServiceConnected()` (lines 147–162) | Re-invocations of `onServiceConnected()` overwrite `flickerController` without calling `stopListening()`, leaking `DisplayListener` and `BroadcastReceiver` registrations. | `Pro-Touch-And-HitTest` |
| **F-06** | `HIGH` | `TapAccessibilityService.kt`<br>`BlankSpaceHitTester.kt` | `onOutsideTouchDetected()` (lines 351–424)<br>`isEligibleScreenContext()` (lines 494–584) | Calls `windows` and `rootInActiveWindow` 3–4 times per tap on the main thread without caching the snapshot for the current touch pass, and never recycles `AccessibilityWindowInfo`/`AccessibilityNodeInfo` on API 31–32. | `Pro-Touch-And-HitTest` |
| **F-07** | `HIGH` | `FlickerProtectionOverlayController.kt` | `onDisplayChanged()` (lines 77–94)<br>`screenReceiver` (lines 98–108) | Race condition between `DisplayListener` and `ACTION_SCREEN_OFF` when `activeSnapshotBitmap != null`: `onDisplayChanged` detaches immediately (0ms hold) whereas `ACTION_SCREEN_OFF` schedules `currentHoldDurationMs` and sets `hasTriggeredPostOffRemoval = true`. | `Pro-Overlay-Engine` |
| **F-08** | `HIGH` | `TapLockPreferences.kt`<br>`MainActivity.kt` | `TapLockPreferencesRepository` (lines 113–183)<br>`PixelTapLockSettingsScreen` (line 103) | Multiple repository instances and cold `Flow` cause UI state flicker on initial composition and lack a singleton hot `StateFlow<TapLockSettings>` shared across Service, Activity, and Widget. | `Pro-State-And-UI` |
| **F-09** | `HIGH` | `MainActivity.kt` | `ServiceStatusCard()` (lines 242–347) | Missing mandatory Google Play / Android 15–17 Prominent Disclosure `AlertDialog` before redirecting the user to `Settings.ACTION_ACCESSIBILITY_SETTINGS`. | `Pro-State-And-UI` |
| **F-10** | `MEDIUM` | `FlickerProtectionOverlayController.kt`<br>`TapAccessibilityService.kt` | Raw `Handler` / `Runnable` callbacks | Uses 6 separate `Handler` `Runnable` timers across two classes instead of structured `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` jobs. | `Pro-Overlay-Engine` & `Pro-Touch-And-HitTest` |
| **F-11** | `MEDIUM` | `accessibility_service_config.xml` | `accessibilityEventTypes` | Missing `typeViewFocused|typeViewSelected|typeTouchInteractionStart|typeGestureDetectionStart` to strengthen `(0, 0)` coordinate correlation debounce. | `Pro-Build-And-ADB-QA` |

---

## Section 3: Subagent Blueprint & Task Specifications (For Gemini Pro Subagents)

---

### Subagent 1: `Pro-Overlay-Engine`

#### Role & Strict File Scope
- **Create:** `app/src/main/java/com/pixeltaplock/FlickerOverlayManager.kt`
- **Modify:** `app/src/main/java/com/pixeltaplock/FlickerProtectionOverlayController.kt` (convert into a clean delegate/facade over `FlickerOverlayManager` so existing callers and unit tests remain $100\%$ source-compatible).
- **Forbidden:** Do not edit `MainActivity.kt`, `TapAccessibilityService.kt`, `TapLockPreferences.kt`, or `BlankSpaceHitTester.kt`.

#### Exact Architectural Changes & Code Patterns

1. **Extract `FlickerOverlayManager.kt` with Coroutine State Machine & Safe Bitmap Teardown:**
   - Bind all timers (pre-draw fallback, post-commit pre-lock delay, post-off hold buffer, and hard $1200\text{ms}+$ failsafe watchdog) to an injected `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`.
   - Query `windowManager.maximumWindowMetrics.bounds` dynamically on every lock invocation and `onConfigurationChanged` / `onDisplayChanged` (never caching dimensions across invocations).
   - Oversize the overlay by $+200\text{px}$ (`width = bounds.width() + 200`, `height = bounds.height() + 200`, `x = -100`, `y = -100`) with `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, `FLAG_LAYOUT_IN_SCREEN`, `FLAG_LAYOUT_NO_LIMITS`, `FLAG_NOT_FOCUSABLE`, `FLAG_NOT_TOUCHABLE`, `FLAG_HARDWARE_ACCELERATED`, `fitInsetsTypes = 0`, and `fitInsetsSides = 0`.
   - **Remove** the `WindowInsetsController.hide()` listener from `createOverlayView()` so attaching the non-focusable overlay never triggers a SystemUI bar animation.
   - **Double-Stage Frame Commit Barrier (`registerFrameCommitCallback` + `Choreographer`):**
     Wait for `doOnPreDraw`, then use `viewTreeObserver.registerFrameCommitCallback` (with a `Choreographer.getInstance().postFrameCallback` fallback) so `GLOBAL_ACTION_LOCK_SCREEN` is guaranteed to fire only **after** `RenderThread` has submitted the black/snapshot frame to `SurfaceFlinger`.
   - **Safe Hardware Bitmap Teardown Order:**
     In `removeOverlayViewOnly()`, always:
     1. Cancel `activeFadeAnimator` and coroutine jobs.
     2. Clear `activeSnapshotImageView?.setImageDrawable(null)`.
     3. Detach `view` from `WindowManager` via `windowManager.removeViewImmediate(view)` (checking `view.isAttachedToWindow` first and falling back to `removeView`).
     4. Post bitmap recycling (`bmp.recycle()`) onto `mainHandler.post { ... }` *after* detachment completes so `RenderThread` never encounters a recycled `HardwareBuffer` bitmap.

2. **Concrete Implementation Blueprint for `FlickerOverlayManager.kt`:**

```kotlin
package com.pixeltaplock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.doOnPreDraw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FlickerOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val displayManager: DisplayManager,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayJob = SupervisorJob()
    private val overlayScope = CoroutineScope(Dispatchers.Main.immediate + overlayJob)

    private var activeOverlayView: FrameLayout? = null
    private var activeSnapshotBitmap: Bitmap? = null
    private var activeSnapshotImageView: ImageView? = null
    private var activeFadeAnimator: ValueAnimator? = null

    @Volatile
    private var isLockSequenceInFlight = false
    private var hasTriggeredPostOffRemoval = false
    private var hasUsedSnapshotInCurrentFlight = false
    private var lastAttachedBounds: Rect? = null

    private var preLockJob: Job? = null
    private var postOffHoldJob: Job? = null
    private var watchdogJob: Job? = null

    private var isListening = false
    private var currentHoldDurationMs: Long = TapLockSettings.DEFAULT_POST_LOCK_HOLD_MS.toLong()

    fun isLockInProgress(): Boolean = isLockSequenceInFlight

    fun updateHoldDurationMs(holdMs: Int) {
        currentHoldDurationMs = holdMs.coerceIn(
            TapLockSettings.MIN_POST_LOCK_HOLD_MS,
            TapLockSettings.MAX_POST_LOCK_HOLD_MS
        ).toLong()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            refreshOverlayBoundsIfAttached()

            if (activeOverlayView != null) {
                val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
                if (FlickerProtectionOverlayController.isDisplayOffOrDozing(display.state)) {
                    onDisplayEnteredOffOrDoze()
                } else if (hasTriggeredPostOffRemoval && display.state == Display.STATE_ON) {
                    removeOverlayImmediately()
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (activeOverlayView != null) {
                        onDisplayEnteredOffOrDoze()
                    }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    removeOverlayImmediately()
                }
            }
        }
    }

    private fun onDisplayEnteredOffOrDoze() {
        if (hasUsedSnapshotInCurrentFlight) {
            // Snapshot fade already reached pure black before GLOBAL_ACTION_LOCK_SCREEN;
            // hold for a tiny 32ms frame-settle buffer, then remove immediately so AOD is never obscured.
            schedulePostOffHoldRemoval(minOf(currentHoldDurationMs, 48L))
        } else {
            schedulePostOffHoldRemoval(currentHoldDurationMs)
        }
    }

    fun startListening() {
        if (isListening) return
        displayManager.registerDisplayListener(displayListener, mainHandler)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        isListening = true
    }

    fun stopListening() {
        removeOverlayImmediately()
        if (isListening) {
            displayManager.unregisterDisplayListener(displayListener)
            try {
                context.unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {}
            isListening = false
        }
    }

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
                val halfMargin = FlickerProtectionOverlayController.SAFETY_OVERSIZE_MARGIN_PX / 2
                child.layoutParams = FrameLayout.LayoutParams(
                    maxBounds.width(),
                    maxBounds.height()
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    setMargins(halfMargin, halfMargin, 0, 0)
                }
            }
        } catch (_: IllegalArgumentException) {}
    }

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
                    showOverlay, snapshotBitmap, fadeDurationMs,
                    preLockDelayMs, postLockHoldMs, performLockAction
                )
            }
            return
        }

        if (!showOverlay) {
            removeOverlayImmediately()
            recycleBitmapSafely(snapshotBitmap)
            performLockAction()
            return
        }

        if (isLockSequenceInFlight) {
            recycleBitmapSafely(snapshotBitmap)
            return
        }

        isLockSequenceInFlight = true
        hasTriggeredPostOffRemoval = false
        hasUsedSnapshotInCurrentFlight = snapshotBitmap != null && !snapshotBitmap.isRecycled

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

        overlayJob.cancelChildren()
        removeOverlayViewOnly()

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
            activeSnapshotBitmap = null
            recycleBitmapSafely(snapshotBitmap)
            performLockAction()
            return
        }

        val hardSafetyTimeoutMs = FlickerProtectionOverlayController.computeInitialHardSafetyTimeoutMs(
            clampedPreDelayMs = clampedPreDelayMs,
            clampedPostHoldMs = clampedPostHoldMs,
            clampedFadeDurationMs = if (hasUsedSnapshotInCurrentFlight) clampedFadeMs else 0L
        )
        watchdogJob = overlayScope.launch {
            delay(hardSafetyTimeoutMs)
            removeOverlayImmediately()
        }

        var lockActionFired = false
        val fireLockOnce = {
            if (!lockActionFired && isLockSequenceInFlight && activeOverlayView != null) {
                lockActionFired = true
                preLockJob?.cancel()
                preLockJob = null
                val lockSucceeded = performLockAction()
                if (!lockSucceeded) {
                    removeOverlayImmediately()
                } else {
                    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    if (display != null && FlickerProtectionOverlayController.isDisplayOffOrDozing(display.state)) {
                        onDisplayEnteredOffOrDoze()
                    }
                }
            }
        }

        val startFadeOrDelayAfterFrameCommit = {
            if (isLockSequenceInFlight && activeOverlayView != null) {
                if (hasUsedSnapshotInCurrentFlight && clampedFadeMs > 0L) {
                    startFadeAnimation(clampedFadeMs, fireLockOnce)
                    preLockJob = overlayScope.launch {
                        delay(clampedFadeMs + 100L)
                        fireLockOnce()
                    }
                } else if (clampedPreDelayMs <= 0L) {
                    fireLockOnce()
                } else {
                    preLockJob = overlayScope.launch {
                        delay(clampedPreDelayMs)
                        fireLockOnce()
                    }
                }
            }
        }

        overlayView.doOnPreDraw {
            var frameCommitted = false
            val onCommitted = Runnable {
                if (!frameCommitted) {
                    frameCommitted = true
                    startFadeOrDelayAfterFrameCommit()
                }
            }
            try {
                if (overlayView.viewTreeObserver.isAlive) {
                    overlayView.viewTreeObserver.registerFrameCommitCallback(onCommitted)
                }
            } catch (_: Exception) {}

            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback {
                    onCommitted.run()
                }
            }
        }

        val initialFallbackTimeout = if (hasUsedSnapshotInCurrentFlight && clampedFadeMs > 0L) {
            clampedFadeMs + FlickerProtectionOverlayController.PRE_DRAW_FALLBACK_SLACK_MS
        } else {
            clampedPreDelayMs + FlickerProtectionOverlayController.PRE_DRAW_FALLBACK_SLACK_MS
        }
        preLockJob = overlayScope.launch {
            delay(initialFallbackTimeout)
            fireLockOnce()
        }
    }

    private fun startFadeAnimation(clampedFadeMs: Long, onEnd: () -> Unit) {
        if (activeFadeAnimator != null) return
        activeSnapshotImageView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val animator = ValueAnimator.ofFloat(1.0f, 0.0f).apply {
            duration = clampedFadeMs
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { va ->
                activeSnapshotImageView?.alpha = va.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    activeSnapshotImageView?.alpha = 0.0f
                    onEnd()
                }
            })
        }
        activeFadeAnimator = animator
        animator.start()
    }

    private fun schedulePostOffHoldRemoval(holdDurationMs: Long) {
        if (hasTriggeredPostOffRemoval) return
        hasTriggeredPostOffRemoval = true
        postOffHoldJob?.cancel()
        postOffHoldJob = overlayScope.launch {
            delay(holdDurationMs)
            removeOverlayImmediately()
        }
        watchdogJob?.cancel()
        watchdogJob = overlayScope.launch {
            delay(holdDurationMs + 250L)
            removeOverlayImmediately()
        }
    }

    fun removeOverlayImmediately() {
        overlayJob.cancelChildren()
        preLockJob = null
        postOffHoldJob = null
        watchdogJob = null
        removeOverlayViewOnly()
        isLockSequenceInFlight = false
        hasTriggeredPostOffRemoval = false
        hasUsedSnapshotInCurrentFlight = false
    }

    private fun removeOverlayViewOnly() {
        val view = activeOverlayView
        activeOverlayView = null
        lastAttachedBounds = null

        activeFadeAnimator?.cancel()
        activeFadeAnimator = null

        val imageView = activeSnapshotImageView
        activeSnapshotImageView = null
        imageView?.setImageDrawable(null)
        imageView?.setLayerType(View.LAYER_TYPE_NONE, null)

        val bitmapToRecycle = activeSnapshotBitmap
        activeSnapshotBitmap = null

        if (view != null) {
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                } else {
                    windowManager.removeView(view)
                }
            } catch (_: IllegalArgumentException) {
            } catch (_: Exception) {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
        }

        if (bitmapToRecycle != null) {
            mainHandler.post {
                recycleBitmapSafely(bitmapToRecycle)
            }
        }
    }

    private fun recycleBitmapSafely(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            try {
                bitmap.recycle()
            } catch (_: Exception) {}
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
            val halfMargin = FlickerProtectionOverlayController.SAFETY_OVERSIZE_MARGIN_PX / 2
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

    @Suppress("DEPRECATION")
    private fun buildOverlayLayoutParams(maxBounds: Rect): WindowManager.LayoutParams {
        val geometry = FlickerProtectionOverlayController.calculateOversizedOverlayGeometry(
            maxBoundsWidth = maxBounds.width(),
            maxBoundsHeight = maxBounds.height(),
            safetyMarginPx = FlickerProtectionOverlayController.SAFETY_OVERSIZE_MARGIN_PX
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
            fitInsetsTypes = 0
            fitInsetsSides = 0
            isFitInsetsIgnoringVisibility = false
            title = "PixelTapLockFlickerGuard"
        }
    }
}
```

---

### Subagent 2: `Pro-Touch-And-HitTest`

#### Role & Strict File Scope
- **Create:** `app/src/main/java/com/pixeltaplock/TapGestureDetector.kt`
- **Modify:**
  - `app/src/main/java/com/pixeltaplock/BlankSpaceHitTester.kt`
  - `app/src/main/java/com/pixeltaplock/TapAccessibilityService.kt`
  - `app/src/main/java/com/pixeltaplock/BlankSpaceWidgetReceiver.kt`
- **Forbidden:** Do not edit `FlickerOverlayManager.kt`, `MainActivity.kt`, or `TapLockPreferences.kt`.

#### Exact Architectural Changes & Code Patterns

1. **Fix `SYSTEM_UI_PACKAGE` Window State Exemption in `TapAccessibilityService.onAccessibilityEvent()`:**
   - Replace the broken `isSystemUiUnlockTransition` check (lines 218–224 of `TapAccessibilityService.kt`) with an explicit check that only exempts SystemUI window state changes when an unlock (`ACTION_USER_PRESENT`) occurred within the last $350\text{ms}$, AND immediately cancels any pending double-tap if the className/node indicates Notification Shade, Quick Settings, Volume Dialog, or Bouncer:
   ```kotlin
   val isRecentUserPresent = lastUserPresentUptimeMillis > 0L &&
       (now - lastUserPresentUptimeMillis) < 350L
   val clsStr = event.className?.toString().orEmpty()
   val isShadeOrQsOrBouncer = BlankSpaceHitTester.isShadeOrQuickSettingsClassOrId(clsStr) ||
       clsStr.contains("Bouncer", ignoreCase = true) ||
       clsStr.contains("VolumeDialog", ignoreCase = true)

   val isBenignUnlockTransition = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
       pkgStr == BlankSpaceHitTester.SYSTEM_UI_PACKAGE &&
       isRecentUserPresent &&
       !isShadeOrQsOrBouncer

   if (!isBenignUnlockTransition) {
       lastInteractiveEventUptimeMillis = now
       tapGestureDetector?.reset()
       cancelPendingDoubleTapLock()
   }
   ```

2. **Add Notification Shade & Quick Settings DOM Detection in `BlankSpaceHitTester.kt`:**
   - Add `SHADE_AND_QS_CLASS_PATTERNS` and `SHADE_AND_QS_ID_PATTERNS` to `BlankSpaceHitTester` so expanded Quick Settings or Notification Shade panels on **both** the Home Screen and the Lock Screen are reliably rejected even when `ACTION_OUTSIDE` coordinates are `(0f, 0f)`:
   ```kotlin
   private val QS_AND_SHADE_CLASS_PATTERNS = listOf(
       "QSTileView",
       "QSPanel",
       "QuickQSPanel",
       "QSContainerImpl",
       "QSAnimator",
       "BrightnessSliderView",
       "QSFooterView"
   )

   private val QS_AND_SHADE_ID_PATTERNS = listOf(
       "qs_frame",
       "qs_panel",
       "quick_settings_panel",
       "quick_qs_panel",
       "qs_footer",
       "brightness_slider",
       "shade_header_system_icons",
       "notification_lights_out"
   )

   fun isShadeOrQuickSettingsClassOrId(className: String?, viewIdResourceName: String? = null): Boolean {
       val cls = className.orEmpty()
       val viewId = viewIdResourceName.orEmpty().lowercase()
       return QS_AND_SHADE_CLASS_PATTERNS.any { cls.contains(it, ignoreCase = true) } ||
           (viewId.isNotEmpty() && QS_AND_SHADE_ID_PATTERNS.any { viewId.contains(it) })
   }

   /**
    * Returns true if Quick Settings tiles or the expanded Quick Settings / Shade header
    * are actively visible in the SystemUI node tree (on either Home Screen or Lock Screen).
    */
   fun isQuickSettingsOrExpandedShadeShowing(
       rootNode: AccessibilityNodeInfo?,
       screenWidthPx: Int = 0,
       screenHeightPx: Int = 0
   ): Boolean {
       if (rootNode == null) return false
       return checkLiveQsRecursive(rootNode, screenWidthPx, screenHeightPx, depth = 0)
   }

   private fun checkLiveQsRecursive(
       node: AccessibilityNodeInfo,
       screenWidthPx: Int,
       screenHeightPx: Int,
       depth: Int
   ): Boolean {
       if (depth > 35 || !node.isVisibleToUser) return false
       val bounds = Rect()
       node.getBoundsInScreen(bounds)
       if (!isRectEmpty(bounds)) {
           val cls = node.className?.toString()
           val viewId = node.viewIdResourceName
           if (isShadeOrQuickSettingsClassOrId(cls, viewId)) {
               val inBounds = (screenWidthPx <= 0 || (bounds.left < screenWidthPx && bounds.right > 0)) &&
                   (screenHeightPx <= 0 || (bounds.top < screenHeightPx && bounds.bottom > 0))
               if (inBounds) return true
           }
       }
       val count = node.childCount
       for (i in 0 until count) {
           val child = node.getChild(i) ?: continue
           if (checkLiveQsRecursive(child, screenWidthPx, screenHeightPx, depth + 1)) {
               return true
           }
       }
       return false
   }
   ```
   - Call `BlankSpaceHitTester.isQuickSettingsOrExpandedShadeShowing(activeRootNode, maxBounds.width(), maxBounds.height())` inside `isEligibleScreenContext()` for both the Lock Screen and when any SystemUI window is present on the Home Screen.

3. **Single-Pass Window & Root Snapshot Acquisition (`TouchEvaluationContext`) in `TapAccessibilityService.kt`:**
   - Eliminate the 3–4 redundant `windows` and `rootInActiveWindow` IPC Binder calls per tap by capturing `val windowsList = try { windows } catch (_: Exception) { null }` and `val activeRootNode = try { rootInActiveWindow } catch (_: Exception) { null }` **once** at the start of `onOutsideTouchDetected()` and passing them into `isOverviewShowingInWindows()`, `isEligibleScreenContext()`, and `hitsInteractiveNodeAt()`.

4. **Extract `TapGestureDetector.kt` with `(0f, 0f)` Coordinate Fallback & $60\text{ms}$ Coroutine Debounce:**
   - Encapsulate the two-tap state machine and $60\text{ms}$ (`BlankSpaceHitTester.EVENT_CORRELATION_DEBOUNCE_MS`) event-correlation debounce inside `TapGestureDetector.kt`.
   - When `rawX == 0f && rawY == 0f` (Android 15–17 `ACTION_OUTSIDE` coordinate masking), enforce a slightly wider event-correlation window ($75\text{ms}$ instead of $60\text{ms}$) and reject the tap if any focused/pressed/selected interactive child leaf exists in the active window or if `lastInteractiveEventUptimeMillis` falls within `(tap1Time - 25L)..debounceEndUptimeMillis` (accounting for kernel `eventTime` vs `uptimeMillis` skew).

```kotlin
package com.pixeltaplock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TapGestureDetector(
    private val scope: CoroutineScope,
    private val onVerifiedDoubleTap: (tap1UptimeMillis: Long, hadValidCoordinates: Boolean) -> Unit
) {
    private var firstOutsideTap: TapSample? = null
    private var debounceJob: Job? = null

    fun reset() {
        firstOutsideTap = null
        debounceJob?.cancel()
        debounceJob = null
    }

    fun cancelPendingDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    fun onCandidateTap(
        tap: TapSample,
        settings: TapLockSettings,
        screenConfig: ScreenBoundsConfig,
        lastScreenWakeUptimeMillis: Long,
        wakeGraceMs: Long,
        hitsInteractiveNode: (TapSample) -> Boolean
    ) {
        cancelPendingDebounce()

        val previousTap = firstOutsideTap?.takeUnless {
            BlankSpaceHitTester.isWithinScreenWakeGracePeriod(
                tapUptimeMillis = it.uptimeMillis,
                lastScreenWakeUptimeMillis = lastScreenWakeUptimeMillis,
                gracePeriodMs = wakeGraceMs
            )
        }

        if (previousTap == null) {
            if (tap.hasValidCoordinates && hitsInteractiveNode(tap)) {
                firstOutsideTap = null
                return
            }
            firstOutsideTap = tap
            return
        }

        val isPair = BlankSpaceHitTester.isDoubleTapPair(
            firstTap = previousTap,
            secondTap = tap,
            doubleTapTimeoutMs = settings.clampedDoubleTapTimeoutMs,
            config = screenConfig
        )

        if (!isPair) {
            firstOutsideTap = if (tap.hasValidCoordinates && hitsInteractiveNode(tap)) {
                null
            } else {
                tap
            }
            return
        }

        if (tap.hasValidCoordinates && hitsInteractiveNode(tap)) {
            firstOutsideTap = null
            return
        }

        val hadCoords = previousTap.hasValidCoordinates && tap.hasValidCoordinates
        val tap1Time = previousTap.uptimeMillis
        firstOutsideTap = null

        // Use 60ms debounce when coordinates were verified, or 75ms when masked to (0f, 0f)
        val debounceMs = if (hadCoords) {
            BlankSpaceHitTester.EVENT_CORRELATION_DEBOUNCE_MS
        } else {
            MASKED_COORDINATE_DEBOUNCE_MS
        }

        debounceJob = scope.launch {
            delay(debounceMs)
            debounceJob = null
            onVerifiedDoubleTap(tap1Time, hadCoords)
        }
    }

    companion object {
        const val MASKED_COORDINATE_DEBOUNCE_MS = 75L
    }
}
```

5. **Prevent Duplicate Listener Leaks in `TapAccessibilityService.onServiceConnected()`:**
   - Before assigning a new `FlickerProtectionOverlayController` in `onServiceConnected()`, always call `flickerController?.stopListening()`.

---

### Subagent 3: `Pro-State-And-UI`

#### Role & Strict File Scope
- **Modify:**
  - `app/src/main/java/com/pixeltaplock/TapLockPreferences.kt`
  - `app/src/main/java/com/pixeltaplock/MainActivity.kt`
  - `app/src/main/res/values/strings.xml`
- **Forbidden:** Do not edit `TapAccessibilityService.kt`, `FlickerOverlayManager.kt`, or `BlankSpaceHitTester.kt`.

#### Exact Architectural Changes & Code Patterns

1. **Process-Wide Hot `StateFlow<TapLockSettings>` Singleton in `TapLockPreferences.kt`:**
   - Upgrade `TapLockPreferencesRepository` with a thread-safe singleton accessor `TapLockPreferencesRepository.getInstance(context)` backed by an application-scoped `StateFlow<TapLockSettings>` (`stateIn(appScope, SharingStarted.Eagerly, TapLockSettings())`).
   - Expose `val currentSettingsStateFlow: StateFlow<TapLockSettings>` alongside `val settingsFlow: Flow<TapLockSettings>` so existing callers and tests passing a custom `DataStore<Preferences>` continue to work seamlessly while `MainActivity`, `TapAccessibilityService`, and `BlankSpaceWidgetReceiver` read zero-latency in-memory state.

```kotlin
// Add inside TapLockPreferencesRepository in TapLockPreferences.kt:
private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

val settingsStateFlow: StateFlow<TapLockSettings> = settingsFlow.stateIn(
    scope = repositoryScope,
    started = SharingStarted.Eagerly,
    initialValue = TapLockSettings()
)

companion object {
    @Volatile
    private var INSTANCE: TapLockPreferencesRepository? = null

    fun getInstance(context: Context): TapLockPreferencesRepository {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: TapLockPreferencesRepository(
                context.applicationContext.tapLockDataStore
            ).also { INSTANCE = it }
        }
    }
}
```

2. **Add Play Store / Android 15–17 Prominent Disclosure `AlertDialog` in `MainActivity.kt`:**
   - Add a `hasAcceptedProminentDisclosure` boolean preference key in `TapLockPreferences.kt` (default `false`).
   - In `MainActivity.kt`, when `isServiceActive == false` and the user taps `"Enable Service"`, if `!settings.hasAcceptedProminentDisclosure`, display a Material 3 `AlertDialog` clearly disclosing:
     - **Why Accessibility Service is Needed:** To detect double-taps on empty areas of the Home Screen and Lock Screen and display a full-bleed QHD+ OLED black/snapshot flicker mask before calling `GLOBAL_ACTION_LOCK_SCREEN`.
     - **Privacy & Zero Data Collection Guarantee:** Tap2Sleep processes touch timing and window state strictly in volatile device RAM, never reads text content or passwords, and has zero internet permissions.
   - Provide **"Agree & Continue to Settings"** (which persists `setProminentDisclosureAccepted(true)` and opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`) and **"Not Now"** buttons.

---

### Subagent 4: `Pro-Build-And-ADB-QA`

#### Role & Strict File Scope
- **Modify:**
  - `app/src/main/res/xml/accessibility_service_config.xml`
  - `app/src/main/AndroidManifest.xml`
  - `app/build.gradle.kts`
  - `app/src/test/java/com/pixeltaplock/*` (Unit test suite verification & expansion)
- **Execute:** Terminal Gradle compilation, unit tests, APK installation, and live ADB hardware verification on the connected Pixel 11 Pro.

#### Exact Configuration Changes

1. **Harden `accessibility_service_config.xml`:**
   - Ensure `accessibilityEventTypes` includes `typeTouchInteractionStart` and `typeGestureDetectionStart` in addition to `typeViewClicked|typeViewLongClicked|typeViewScrolled|typeWindowStateChanged|typeWindowsChanged` so any interactive gesture or touch consumption on Android 15–17 is immediately visible to `TapAccessibilityService`:
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
       android:accessibilityEventTypes="typeViewClicked|typeViewLongClicked|typeViewScrolled|typeWindowStateChanged|typeWindowsChanged"
       android:accessibilityFeedbackType="feedbackGeneric"
       android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews"
       android:canRetrieveWindowContent="true"
       android:canTakeScreenshot="true"
       android:description="@string/accessibility_service_description"
       android:notificationTimeout="0"
       android:settingsActivity="com.pixeltaplock.MainActivity" />
   ```
   *(Note: Keep `accessibilityEventTypes` compatible with existing unit tests if any test asserts the XML attribute string, or verify unit test assertions before adding flags.)*

2. **Unit Test Coverage Expansion (`app/src/test/java/com/pixeltaplock/`):**
   - Add unit tests verifying:
     - `BlankSpaceHitTester.isShadeOrQuickSettingsClassOrId` and `isQuickSettingsOrExpandedShadeShowing` reject Quick Settings tiles (`QSTileView`, `qs_frame`, `qs_panel`) on both Home and Lock Screen.
     - `(0f, 0f)` masked coordinates use the $75\text{ms}$ debounce window and cancel when `lastInteractiveEventUptimeMillis` occurs within `(tap1UptimeMillis - 25L)..debounceEndUptimeMillis`.
     - `FlickerProtectionOverlayController` / `FlickerOverlayManager` detaches the view from `WindowManager` *before* recycling `snapshotBitmap`.

---

## Section 4: Native ADB Verification & Self-Healing Protocol

Once Waves 1–3 are complete, **`Pro-Build-And-ADB-QA`** must execute the following deterministic terminal & ADB verification suite against the connected Pixel 11 Pro. If any step fails, `Pro-Build-And-ADB-QA` must diagnose the failure from `logcat` / `dumpsys`, apply a targeted self-healing fix, and re-run the suite until $100\%$ of assertions pass.

### Step 1: Clean Build, Unit Tests & APK Installation
```bash
./gradlew clean testDebugUnitTest assembleDebug
adb devices -l
adb install -r app/build/outputs/apk/debug/Tap2Sleep-v1.0.10.apk
```
- **Pass Criteria:**
  - `BUILD SUCCESSFUL` with `0` unit test failures and `0` compiler warnings/errors.
  - `Success` returned by `adb install -r`.

### Step 2: Enable Accessibility Service & Unrestricted Battery via ADB
```bash
# Grant battery optimization exemption
adb shell dumpsys deviceidle whitelist +com.pixeltaplock

# Enable TapAccessibilityService directly via Secure Settings
EXISTING_A11Y=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
if [ "$EXISTING_A11Y" = "null" ] || [ -z "$EXISTING_A11Y" ]; then
  adb shell settings put secure enabled_accessibility_services com.pixeltaplock/com.pixeltaplock.TapAccessibilityService
else
  adb shell settings put secure enabled_accessibility_services "${EXISTING_A11Y}:com.pixeltaplock/com.pixeltaplock.TapAccessibilityService"
fi
adb shell settings put secure accessibility_enabled 1
```
- **Verification Command:**
  ```bash
  adb shell dumpsys accessibility | grep -A 15 "TapAccessibilityService"
  ```
- **Pass Criteria:** Service is listed under bound/enabled services with `canTakeScreenshot=true` and `canRetrieveWindowContent=true`.

### Step 3: Verify $1 \times 1$ Non-Blocking Touch Watcher Overlay & Untrusted Touch Compliance
```bash
adb shell dumpsys window windows | grep -E -A 18 "PixelTapLockTouchWatcher"
```
- **Pass Criteria:**
  - Window `PixelTapLockTouchWatcher` is present with type `TYPE_ACCESSIBILITY_OVERLAY`.
  - Frame dimensions are strictly $1 \times 1$ (`[0,0][1,1]`), `alpha=0.0`, and flags include `NOT_FOCUSABLE`, `NOT_TOUCH_MODAL`, and `WATCH_OUTSIDE_TOUCH`.

### Step 4: Verify Dynamic QHD+ Resolution Switching & Oversized Bleed Geometry
```bash
# 1. Check current physical & override display dimensions
adb shell wm size

# 2. Switch to FHD+ (1080x2400) and verify no crashes or stale bounds
adb shell wm size 1080x2400
sleep 1

# 3. Switch to Full QHD+ (1440x3120 / reset to hardware native)
adb shell wm size reset
sleep 1
adb shell wm size
```
- **Pass Criteria:** `TapAccessibilityService` remains bound with zero exceptions in `adb logcat -d | grep -i "AndroidRuntime"`.

### Step 5: Simulated Home Screen Blank-Space Double-Tap & Flicker Overlay Verification
```bash
# Wake device, dismiss keyguard to Home Screen, and clear logcat
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell input keyevent KEYCODE_HOME
sleep 1
adb logcat -c

# Simulate double-tap on empty wallpaper area (e.g. X=700, Y=600) with 120ms interval
adb shell "input tap 700 600 && sleep 0.12 && input tap 700 600"

# Wait 150ms and inspect overlay & display state transition
sleep 1
adb shell dumpsys power | grep -E "mWakefulness=|Display Power: state="
adb shell dumpsys window windows | grep "PixelTapLockFlickerGuard"
```
- **Pass Criteria:**
  - `mWakefulness` transitions to `Dozing` or `Asleep` (`Display Power: state=OFF` or `DOZE`).
  - `PixelTapLockFlickerGuard` is **removed** (`grep` returns empty) after the display enters `OFF`/`DOZE` + hold buffer (confirming zero leaked overlay blocking Always-On Display).
  - `adb logcat -d | grep -E "pixeltaplock|AndroidRuntime"` shows zero `trying to use a recycled bitmap` or `IllegalArgumentException: View not attached to window manager` errors.

### Step 6: Negative Test — Quick Settings & Notification Shade Rejection
```bash
# Wake and unlock to Home Screen
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell input keyevent KEYCODE_HOME
sleep 1

# Expand Notification / Quick Settings Shade
adb shell cmd statusbar expand-settings
sleep 1

# Double-tap inside the expanded Quick Settings area
adb shell "input tap 700 700 && sleep 0.12 && input tap 700 700"
sleep 0.5

# Assert display is STILL awake (mWakefulness=Awake)
adb shell dumpsys power | grep "mWakefulness="
adb shell cmd statusbar collapse
```
- **Pass Criteria:** `mWakefulness=Awake` (the device **must not** lock when double-tapping inside the expanded Quick Settings or Notification Shade).
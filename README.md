# Tap2Sleep for Pixel (Tap2Sleep)

<p align="center">
  <img src="art/app_icon.png" width="128" height="128" alt="Tap2Sleep Icon" />
</p>

<p align="center">
  <a href="https://github.com/joshxcode1123/Tap2Sleep/actions/workflows/build.yml"><img src="https://github.com/joshxcode1123/Tap2Sleep/actions/workflows/build.yml/badge.svg" alt="Build & Test" /></a>
  <a href="https://github.com/joshxcode1123/Tap2Sleep/releases/latest"><img src="https://img.shields.io/badge/Release-v1.0.7-blue.svg?logo=github" alt="Latest Release" /></a>
  <img src="https://img.shields.io/badge/Platform-Android%2012%E2%80%9315%2B-3DDC84?logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Designed%20For-Google%20Pixel-4285F4?logo=google&logoColor=white" alt="Designed for Pixel" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-orange.svg" alt="License" /></a>
  <img src="https://img.shields.io/badge/Dependencies-0%20(Pure%20AndroidX)-success" alt="Dependencies" />
</p>

<p align="center">
  <a href="https://github.com/joshxcode1123/Tap2Sleep/releases/latest">
    <img src="https://img.shields.io/badge/⬇%EF%B8%8F_Download_APK-Tap2Sleep--v1.0.7.apk-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" />
  </a>
</p>

A production-ready, zero-dependency (pure AndroidX & Material 3) Android application for Google Pixel devices (Android 12–15+). Shows as **Tap2Sleep** in your App Drawer and **Tap2Sleep for Pixel** in system settings.

Replicates the physical Power Button screen-off behavior by allowing users to **double-tap blank space** on the **Home Screen** or **Lock Screen** to instantly lock the display while **keeping biometric authentication (Fingerprint & Face Unlock) fully intact**.

---

## Key Features

- **True Edge-to-Edge QHD+ Display Engine:**
  - Queries physical hardware panel bounds via `WindowManager.maximumWindowMetrics` in real time (never cached) across dynamic resolution switches (FHD+ $\leftrightarrow$ QHD+ 1344×2992 / 1440×3120).
  - Oversized coverage margin (+200px) completely bypassing status bar, gesture navigation insets, camera cutouts, and rounded corners.

- **Power-Button Turn-Off Simulation:**
  - When locking from the Home Screen, captures a zero-latency hardware buffer snapshot via `AccessibilityService.takeScreenshot` and presents it during the display power-down animation. The lock screen wallpaper never flashes before sleep.
  - Instantly dismisses the overlay upon reaching `Display.STATE_OFF` or `Display.STATE_DOZE` so Always-On Display (AOD) is never obscured.

- **Direct Lock-Screen-to-AOD Transition:**
  - When double-tapping on the Lock Screen, bypasses overlay masks by default so SystemUI transitions smoothly and natively directly into Always-On Display (AOD).
  - Includes a configurable **Lock Screen Wake Grace Delay** slider (0ms – 1500ms) to prevent single/double tap-to-wake impulses from immediately re-locking.

- **Hybrid Blank-Space Detection (Android 15–17 Hardened):**
  - **Check A (Hit-Test):** Traverses `AccessibilityNodeInfo` to verify taps land on empty workspace/lockscreen containers rather than interactive leaves (app icons, widgets, folders, notification cards, QS tiles, or shortcuts).
  - **Check B (Event Correlation Debounce):** Solves modern Android `ACTION_OUTSIDE` `(0, 0)` coordinate masking by debouncing against interactive framework accessibility events (`TYPE_VIEW_CLICKED`, `TYPE_VIEW_SCROLLED`, `TYPE_WINDOW_STATE_CHANGED`).
  - **Check C (Workspace & Transition Filter):** Accepts taps during gesture-navigation app-close transitions while the launcher settles, and respects on-screen keyboards (IME) so typing never triggers an accidental lock.

- **Non-Blocking Touch Observer:**
  - Employs a 1×1 pixel transparent overlay (`alpha = 0f`) with `FLAG_WATCH_OUTSIDE_TOUCH` and `FLAG_NOT_FOCUSABLE`. Never intercepts or delays normal user touches or system dialogs.

- **Biometric Preservation:**
  - Invokes `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`, keeping Fingerprint and Face Unlock active without requiring PIN re-entry (unlike legacy Device Admin methods).

- **Transparent Home Screen Widget:**
  - Includes a resizable invisible widget (`BlankSpaceWidgetReceiver`) as a fallback that can be placed on empty home screen grids.

---

## Technical Specifications

- **Language:** 100% Kotlin
- **UI:** Jetpack Compose with Material 3 (Dynamic Color / Monet theme enabled)
- **Min SDK:** 31 (Android 12) | **Compile/Target SDK:** 35 (Android 15)
- **Build System:** Gradle Kotlin DSL (`build.gradle.kts`) with Version Catalog (`libs.versions.toml`)

---

## Building from Source

Prerequisites: JDK 17 or 21 and Android SDK Platform 35.

```bash
# Clone the repository
git clone https://github.com/<YOUR_USERNAME>/Tap2Sleep.git
cd Tap2Sleep

# Run tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

The compiled APK will be output at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Sideloading Setup (Android 13–15+)

1. Install `app-debug.apk` on your Pixel phone.
2. If Android displays **"Restricted setting"** when enabling Accessibility:
   - Go to **Settings $\rightarrow$ Apps $\rightarrow$ PixelTapLock**.
   - Tap the three-dot menu (**⋮**) in the top right corner.
   - Select **"Allow restricted settings"** and confirm with your biometric/PIN.
3. Open **PixelTapLock**, tap **"Enable Service"**, and turn the accessibility switch **ON**.
4. Disable battery optimization when prompted so Pixel Adaptive Battery never suspends background touch observation.

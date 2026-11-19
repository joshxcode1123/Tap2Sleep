package com.pixeltaplock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var preferencesRepository: TapLockPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferencesRepository = TapLockPreferencesRepository(applicationContext.tapLockDataStore)
        BlankSpaceWidgetReceiver.refreshAllWidgets(applicationContext)

        setContent {
            PixelTapLockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PixelTapLockSettingsScreen(repository = preferencesRepository)
                }
            }
        }
    }
}

@Composable
fun PixelTapLockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // Dynamic Color / Monet enabled for native Pixel Material 3 aesthetic (Min SDK 31+)
    val colorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PixelTapLockSettingsScreen(
    repository: TapLockPreferencesRepository
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val settings by repository.settingsFlow.collectAsState(initial = TapLockSettings())
    val serviceConnectedInMemory by TapAccessibilityService.serviceRunningFlow.collectAsState()

    var isServiceEnabledInSettings by remember {
        mutableStateOf(TapAccessibilityService.isAccessibilityServiceEnabled(context))
    }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(checkIgnoringBatteryOptimizations(context))
    }
    var hardwareWidthPx by remember { mutableIntStateOf(0) }
    var hardwareHeightPx by remember { mutableIntStateOf(0) }

    val refreshDisplayMetrics = {
        val maxBounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        if (maxBounds != null) {
            hardwareWidthPx = maxBounds.width()
            hardwareHeightPx = maxBounds.height()
        }
    }

    LaunchedEffect(configuration) {
        refreshDisplayMetrics()
    }

    DisposableEffect(lifecycleOwner, context) {
        val refreshSystemState = {
            isServiceEnabledInSettings =
                TapAccessibilityService.isAccessibilityServiceEnabled(context)
            isIgnoringBatteryOptimizations = checkIgnoringBatteryOptimizations(context)
            refreshDisplayMetrics()
        }
        refreshSystemState()

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSystemState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun updateSetting(block: suspend TapLockPreferencesRepository.() -> Unit) {
        coroutineScope.launch { repository.block() }
    }

    val isServiceActive = serviceConnectedInMemory || isServiceEnabledInSettings
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Tap2Sleep for Pixel",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "QHD+ Edge-to-Edge Blank-Space Double-Tap Lock",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. Service Status & Restricted Settings Onboarding Card
            ServiceStatusCard(
                isServiceActive = isServiceActive,
                onOpenAccessibilitySettings = {
                    openAccessibilitySettings(context)
                },
                onOpenAppInfo = {
                    openAppDetailsSettings(context)
                }
            )

            // 2. Unrestricted Battery Optimization Warning Card (Android 15-17 Hardening)
            if (!isIgnoringBatteryOptimizations) {
                BatteryOptimizationWarningCard(
                    onRequestUnrestrictedBattery = {
                        requestIgnoreBatteryOptimizations(context)
                    }
                )
            }

            // 3. Blank-Space Detection Toggles & Double-Tap Speed / Wake Grace Sliders
            DetectionSettingsCard(
                settings = settings,
                onToggleHomeScreen = { enabled -> updateSetting { setEnableHomeScreen(enabled) } },
                onToggleLockScreen = { enabled -> updateSetting { setEnableLockScreen(enabled) } },
                onDoubleTapTimeoutChange = { timeoutMs -> updateSetting { setDoubleTapTimeoutMs(timeoutMs) } },
                onLockScreenWakeGraceChange = { graceMs -> updateSetting { setLockScreenWakeGraceMs(graceMs) } }
            )

            // 4. QHD+ Edge-to-Edge Flicker Protection Tuning Card
            FlickerProtectionCard(
                settings = settings,
                hardwareWidthPx = hardwareWidthPx,
                hardwareHeightPx = hardwareHeightPx,
                onToggleSimulatePowerButton = { enabled -> updateSetting { setSimulatePowerButton(enabled) } },
                onFadeDurationChange = { durationMs -> updateSetting { setFadeToBlackDurationMs(durationMs) } },
                onToggleFlickerProtection = { enabled -> updateSetting { setEnableFlickerProtection(enabled) } },
                onToggleLockScreenFlickerProtection = { enabled ->
                    updateSetting { setEnableLockScreenFlickerProtection(enabled) }
                },
                onPreLockDelayChange = { delayMs -> updateSetting { setPreLockBlackoutDelayMs(delayMs) } },
                onPostLockHoldChange = { holdMs -> updateSetting { setPostLockHoldDurationMs(holdMs) } }
            )

            // 5. Test Lock & Flicker Mask Now Button Card
            TestLockActionCard(
                isServiceActive = isServiceActive,
                onTestLockNow = {
                    val triggered = TapAccessibilityService.triggerLockNow()
                    if (!triggered) {
                        Toast.makeText(
                            context,
                            "Please enable Tap2Sleep in Accessibility Settings first.",
                            Toast.LENGTH_SHORT
                        ).show()
                        openAccessibilitySettings(context)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ServiceStatusCard(
    isServiceActive: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    var showRestrictedGuide by rememberSaveable(isServiceActive) { mutableStateOf(!isServiceActive) }

    val containerColor = if (isServiceActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (isServiceActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isServiceActive) {
                            "Accessibility Service: Active"
                        } else {
                            "Accessibility Service: Inactive"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isServiceActive) {
                            "Blank-space double-tap detection and QHD+ OLED flicker mask are running."
                        } else {
                            "Enable Tap2Sleep in Accessibility Settings to activate double-tap screen sleep."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isServiceActive) {
                            "Accessibility Settings"
                        } else {
                            "Enable Service"
                        }
                    )
                }

                TextButton(
                    onClick = { showRestrictedGuide = !showRestrictedGuide }
                ) {
                    Text(
                        text = if (showRestrictedGuide) {
                            "Hide Guide"
                        } else {
                            "Restricted Settings Help"
                        },
                        color = contentColor
                    )
                }
            }

            AnimatedVisibility(visible = showRestrictedGuide) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = contentColor.copy(alpha = 0.25f))
                    Text(
                        text = "Android 13–17 Sideloaded APK Setup (\"Allow restricted settings\"):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "1. Tap \"Enable Service\" above and select Tap2Sleep (it may appear grayed out at first).\n" +
                            "2. Tap \"Open App Info\" below, then tap the 3-dot menu (⋮) in the top-right corner of the App Info screen.\n" +
                            "3. Select \"Allow restricted settings\" and verify with your fingerprint or PIN.\n" +
                            "4. Return to Accessibility Settings and toggle Tap2Sleep ON.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = onOpenAppInfo,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(text = "Open App Info", color = contentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationWarningCard(
    onRequestUnrestrictedBattery: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Pixel Adaptive Battery Optimization Active",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Set Tap2Sleep to Unrestricted battery usage so Android 15–17 never suspends the 1x1 touch-watcher overlay in the background.",
                style = MaterialTheme.typography.bodyMedium
            )
            FilledTonalButton(
                onClick = onRequestUnrestrictedBattery,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(text = "Disable Battery Optimization")
            }
        }
    }
}

@Composable
private fun DetectionSettingsCard(
    settings: TapLockSettings,
    onToggleHomeScreen: (Boolean) -> Unit,
    onToggleLockScreen: (Boolean) -> Unit,
    onDoubleTapTimeoutChange: (Int) -> Unit,
    onLockScreenWakeGraceChange: (Int) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Blank-Space Detection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            SettingSwitchRow(
                title = "Enable on Home Screen",
                subtitle = "Lock when double-tapping empty wallpaper on Pixel Launcher or default home screen",
                checked = settings.enableHomeScreen,
                onCheckedChange = onToggleHomeScreen
            )

            HorizontalDivider()

            SettingSwitchRow(
                title = "Enable on Lock Screen",
                subtitle = "Transition directly to AOD / turn off screen when double-tapping blank area on the Lock Screen",
                checked = settings.enableLockScreen,
                onCheckedChange = onToggleLockScreen
            )

            HorizontalDivider()

            MillisecondSliderWithDirectInput(
                title = "Double-Tap Speed Window",
                subtitle = "Maximum interval between taps (150ms – 500ms, default 300ms)",
                currentValueMs = settings.clampedDoubleTapTimeoutMs,
                minValueMs = TapLockSettings.MIN_DOUBLE_TAP_TIMEOUT_MS,
                maxValueMs = TapLockSettings.MAX_DOUBLE_TAP_TIMEOUT_MS,
                defaultValueMs = TapLockSettings.DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
                enabled = true,
                onValueCommitted = onDoubleTapTimeoutChange
            )

            HorizontalDivider()

            MillisecondSliderWithDirectInput(
                title = "Lock Screen Wake Grace Delay",
                subtitle = "Delay after waking from AOD before Lock Screen double-tap to AOD is allowed, preventing wake taps from re-locking (0ms – 1500ms, default 0ms)",
                currentValueMs = settings.clampedLockScreenWakeGraceMs,
                minValueMs = TapLockSettings.MIN_LOCK_SCREEN_WAKE_GRACE_MS,
                maxValueMs = TapLockSettings.MAX_LOCK_SCREEN_WAKE_GRACE_MS,
                defaultValueMs = TapLockSettings.DEFAULT_LOCK_SCREEN_WAKE_GRACE_MS,
                enabled = settings.enableLockScreen,
                onValueCommitted = onLockScreenWakeGraceChange
            )
        }
    }
}

@Composable
private fun FlickerProtectionCard(
    settings: TapLockSettings,
    hardwareWidthPx: Int,
    hardwareHeightPx: Int,
    onToggleSimulatePowerButton: (Boolean) -> Unit,
    onFadeDurationChange: (Int) -> Unit,
    onToggleFlickerProtection: (Boolean) -> Unit,
    onToggleLockScreenFlickerProtection: (Boolean) -> Unit,
    onPreLockDelayChange: (Int) -> Unit,
    onPostLockHoldChange: (Int) -> Unit
) {
    val anyFlickerMaskEnabled =
        settings.enableFlickerProtection || settings.enableLockScreenFlickerProtection || settings.simulatePowerButton

    val oversizedGeometry = remember(hardwareWidthPx, hardwareHeightPx) {
        FlickerProtectionOverlayController.calculateOversizedOverlayGeometry(
            maxBoundsWidth = hardwareWidthPx,
            maxBoundsHeight = hardwareHeightPx
        )
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "QHD+ Full-Bleed Flicker Protection & Turn-Off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (hardwareWidthPx > 0 && hardwareHeightPx > 0) {
                Text(
                    text = "Live Panel Metrics: ${hardwareWidthPx}×${hardwareHeightPx} px → Oversized Mask: ${oversizedGeometry.widthPx}×${oversizedGeometry.heightPx} px (offset ${oversizedGeometry.offsetXPx},${oversizedGeometry.offsetYPx})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            SettingSwitchRow(
                title = "Simulate Power Button Turn-Off",
                subtitle = "Captures an instantaneous snapshot of the Home Screen so it remains visible during turn-off without showing the Lock Screen",
                checked = settings.simulatePowerButton,
                onCheckedChange = onToggleSimulatePowerButton
            )

            AnimatedVisibility(visible = settings.simulatePowerButton) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    MillisecondSliderWithDirectInput(
                        title = "Fade to Black Speed",
                        subtitle = "Controls how quickly and smoothly the Home Screen snapshot fades into black during turn-off (100ms – 800ms, default 250ms)",
                        currentValueMs = settings.clampedFadeToBlackDurationMs,
                        minValueMs = TapLockSettings.MIN_FADE_TO_BLACK_DURATION_MS,
                        maxValueMs = TapLockSettings.MAX_FADE_TO_BLACK_DURATION_MS,
                        defaultValueMs = TapLockSettings.DEFAULT_FADE_TO_BLACK_DURATION_MS,
                        enabled = true,
                        onValueCommitted = onFadeDurationChange
                    )
                }
            }

            HorizontalDivider()

            SettingSwitchRow(
                title = "Enable Pure Black Flicker Protection",
                subtitle = "Covers 100% of the OLED panel with pure #000000 before locking (disabled by default; use if snapshot simulation is turned off)",
                checked = settings.enableFlickerProtection,
                onCheckedChange = onToggleFlickerProtection
            )

            HorizontalDivider()

            SettingSwitchRow(
                title = "Use Flicker Mask on Lock Screen",
                subtitle = "Off by default so double-tapping on the Lock Screen transitions directly into Always-On Display (AOD) without flashing black",
                checked = settings.enableLockScreenFlickerProtection,
                onCheckedChange = onToggleLockScreenFlickerProtection
            )

            HorizontalDivider()

            MillisecondSliderWithDirectInput(
                title = "Pre-Lock Blackout Delay",
                subtitle = "Ensures the overlay frame hits the OLED panel before GLOBAL_ACTION_LOCK_SCREEN executes (0ms – 100ms, default 24ms)",
                currentValueMs = settings.clampedPreLockBlackoutDelayMs,
                minValueMs = TapLockSettings.MIN_PRE_LOCK_DELAY_MS,
                maxValueMs = TapLockSettings.MAX_PRE_LOCK_DELAY_MS,
                defaultValueMs = TapLockSettings.DEFAULT_PRE_LOCK_DELAY_MS,
                enabled = anyFlickerMaskEnabled,
                onValueCommitted = onPreLockDelayChange
            )

            HorizontalDivider()

            MillisecondSliderWithDirectInput(
                title = "Post-Lock Hold Duration",
                subtitle = "How long the snapshot or black mask stays up while the display transitions to Off / Always-On Display (100ms – 1000ms, default 300ms)",
                currentValueMs = settings.clampedPostLockHoldDurationMs,
                minValueMs = TapLockSettings.MIN_POST_LOCK_HOLD_MS,
                maxValueMs = TapLockSettings.MAX_POST_LOCK_HOLD_MS,
                defaultValueMs = TapLockSettings.DEFAULT_POST_LOCK_HOLD_MS,
                enabled = anyFlickerMaskEnabled,
                onValueCommitted = onPostLockHoldChange
            )
        }
    }
}

@Composable
private fun MillisecondSliderWithDirectInput(
    title: String,
    subtitle: String,
    currentValueMs: Int,
    minValueMs: Int,
    maxValueMs: Int,
    defaultValueMs: Int,
    enabled: Boolean,
    onValueCommitted: (Int) -> Unit
) {
    var sliderValue by remember(currentValueMs) {
        mutableFloatStateOf(currentValueMs.toFloat())
    }
    var showDirectInputDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                onClick = { showDirectInputDialog = true },
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                color = if (enabled) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            ) {
                Text(
                    text = "${sliderValue.roundToInt()} ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                onValueCommitted(sliderValue.roundToInt().coerceIn(minValueMs, maxValueMs))
            },
            enabled = enabled,
            valueRange = minValueMs.toFloat()..maxValueMs.toFloat()
        )
    }

    if (showDirectInputDialog && enabled) {
        val initialText = sliderValue.roundToInt().toString()
        var textFieldValue by remember {
            mutableStateOf(
                TextFieldValue(
                    text = initialText,
                    selection = TextRange(0, initialText.length)
                )
            )
        }
        val focusRequester = remember { FocusRequester() }

        val commitTypedValue = {
            val parsed = textFieldValue.text.trim().toIntOrNull()
            if (parsed != null) {
                val clamped = parsed.coerceIn(minValueMs, maxValueMs)
                sliderValue = clamped.toFloat()
                onValueCommitted(clamped)
            }
            showDirectInputDialog = false
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        AlertDialog(
            onDismissRequest = { showDirectInputDialog = false },
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a value between ${minValueMs} ms and ${maxValueMs} ms (default ${defaultValueMs} ms):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            val filtered = newValue.text.filter { it.isDigit() }.take(5)
                            val clampedSelection = TextRange(
                                start = newValue.selection.start.coerceIn(0, filtered.length),
                                end = newValue.selection.end.coerceIn(0, filtered.length)
                            )
                            textFieldValue = TextFieldValue(
                                text = filtered,
                                selection = clampedSelection
                            )
                        },
                        singleLine = true,
                        suffix = { Text("ms") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { commitTypedValue() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            },
            confirmButton = {
                Button(onClick = commitTypedValue) {
                    Text("Apply")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            val clampedDefault = defaultValueMs.coerceIn(minValueMs, maxValueMs)
                            sliderValue = clampedDefault.toFloat()
                            onValueCommitted(clampedDefault)
                            showDirectInputDialog = false
                        }
                    ) {
                        Text("Default (${defaultValueMs} ms)")
                    }
                    TextButton(onClick = { showDirectInputDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
private fun TestLockActionCard(
    isServiceActive: Boolean,
    onTestLockNow: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Live Calibration & Widget Fallback",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Test the QHD+ full-bleed blackout overlay and lock timing immediately. You can also place the transparent \"Blank Space Double-Tap Lock\" widget from your launcher's Widgets menu onto any Home Screen grid.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onTestLockNow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isServiceActive) {
                        "Test Lock & Flicker Mask Now"
                    } else {
                        "Enable Service to Test Lock & Flicker Mask"
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun checkIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun launchActivityOrToast(
    context: Context,
    intent: Intent,
    errorMessage: String?
): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        if (errorMessage != null) {
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
        }
        false
    }
}

private fun openAccessibilitySettings(context: Context) {
    launchActivityOrToast(
        context,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        "Unable to open Accessibility Settings"
    )
}

private fun openAppDetailsSettings(context: Context) {
    launchActivityOrToast(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        "Unable to open App Info"
    )
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val directIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    if (!launchActivityOrToast(context, directIntent, errorMessage = null)) {
        launchActivityOrToast(
            context,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            "Unable to open Battery Optimization settings"
        )
    }
}

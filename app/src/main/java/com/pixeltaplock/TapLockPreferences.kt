package com.pixeltaplock

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.tapLockDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pixel_tap_lock_settings"
)

data class TapLockSettings(
    val enableHomeScreen: Boolean = DEFAULT_ENABLE_HOME_SCREEN,
    val enableLockScreen: Boolean = DEFAULT_ENABLE_LOCK_SCREEN,
    val doubleTapTimeoutMs: Int = DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
    val enableFlickerProtection: Boolean = DEFAULT_ENABLE_FLICKER_PROTECTION,
    val preLockBlackoutDelayMs: Int = DEFAULT_PRE_LOCK_DELAY_MS,
    val postLockHoldDurationMs: Int = DEFAULT_POST_LOCK_HOLD_MS
) {
    val clampedDoubleTapTimeoutMs: Int
        get() = doubleTapTimeoutMs.coerceIn(MIN_DOUBLE_TAP_TIMEOUT_MS, MAX_DOUBLE_TAP_TIMEOUT_MS)

    val clampedPreLockBlackoutDelayMs: Int
        get() = preLockBlackoutDelayMs.coerceIn(MIN_PRE_LOCK_DELAY_MS, MAX_PRE_LOCK_DELAY_MS)

    val clampedPostLockHoldDurationMs: Int
        get() = postLockHoldDurationMs.coerceIn(MIN_POST_LOCK_HOLD_MS, MAX_POST_LOCK_HOLD_MS)

    companion object {
        const val DEFAULT_ENABLE_HOME_SCREEN = true
        const val DEFAULT_ENABLE_LOCK_SCREEN = true

        const val MIN_DOUBLE_TAP_TIMEOUT_MS = 150
        const val MAX_DOUBLE_TAP_TIMEOUT_MS = 500
        const val DEFAULT_DOUBLE_TAP_TIMEOUT_MS = 300

        const val DEFAULT_ENABLE_FLICKER_PROTECTION = true

        const val MIN_PRE_LOCK_DELAY_MS = 0
        const val MAX_PRE_LOCK_DELAY_MS = 100
        const val DEFAULT_PRE_LOCK_DELAY_MS = 24

        const val MIN_POST_LOCK_HOLD_MS = 100
        const val MAX_POST_LOCK_HOLD_MS = 1000
        const val DEFAULT_POST_LOCK_HOLD_MS = 400
    }
}

class TapLockPreferencesRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val ENABLE_HOME_SCREEN = booleanPreferencesKey("enable_home_screen")
        val ENABLE_LOCK_SCREEN = booleanPreferencesKey("enable_lock_screen")
        val DOUBLE_TAP_TIMEOUT_MS = intPreferencesKey("double_tap_timeout_ms")
        val ENABLE_FLICKER_PROTECTION = booleanPreferencesKey("enable_flicker_protection")
        val PRE_LOCK_BLACKOUT_DELAY_MS = intPreferencesKey("pre_lock_blackout_delay_ms")
        val POST_LOCK_HOLD_DURATION_MS = intPreferencesKey("post_lock_hold_duration_ms")
    }

    val settingsFlow: Flow<TapLockSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            TapLockSettings(
                enableHomeScreen = prefs[Keys.ENABLE_HOME_SCREEN]
                    ?: TapLockSettings.DEFAULT_ENABLE_HOME_SCREEN,
                enableLockScreen = prefs[Keys.ENABLE_LOCK_SCREEN]
                    ?: TapLockSettings.DEFAULT_ENABLE_LOCK_SCREEN,
                doubleTapTimeoutMs = (prefs[Keys.DOUBLE_TAP_TIMEOUT_MS]
                    ?: TapLockSettings.DEFAULT_DOUBLE_TAP_TIMEOUT_MS)
                    .coerceIn(
                        TapLockSettings.MIN_DOUBLE_TAP_TIMEOUT_MS,
                        TapLockSettings.MAX_DOUBLE_TAP_TIMEOUT_MS
                    ),
                enableFlickerProtection = prefs[Keys.ENABLE_FLICKER_PROTECTION]
                    ?: TapLockSettings.DEFAULT_ENABLE_FLICKER_PROTECTION,
                preLockBlackoutDelayMs = (prefs[Keys.PRE_LOCK_BLACKOUT_DELAY_MS]
                    ?: TapLockSettings.DEFAULT_PRE_LOCK_DELAY_MS)
                    .coerceIn(
                        TapLockSettings.MIN_PRE_LOCK_DELAY_MS,
                        TapLockSettings.MAX_PRE_LOCK_DELAY_MS
                    ),
                postLockHoldDurationMs = (prefs[Keys.POST_LOCK_HOLD_DURATION_MS]
                    ?: TapLockSettings.DEFAULT_POST_LOCK_HOLD_MS)
                    .coerceIn(
                        TapLockSettings.MIN_POST_LOCK_HOLD_MS,
                        TapLockSettings.MAX_POST_LOCK_HOLD_MS
                    )
            )
        }

    suspend fun setEnableHomeScreen(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLE_HOME_SCREEN] = enabled }
    }

    suspend fun setEnableLockScreen(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLE_LOCK_SCREEN] = enabled }
    }

    suspend fun setDoubleTapTimeoutMs(timeoutMs: Int) {
        dataStore.edit {
            it[Keys.DOUBLE_TAP_TIMEOUT_MS] = timeoutMs.coerceIn(
                TapLockSettings.MIN_DOUBLE_TAP_TIMEOUT_MS,
                TapLockSettings.MAX_DOUBLE_TAP_TIMEOUT_MS
            )
        }
    }

    suspend fun setEnableFlickerProtection(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLE_FLICKER_PROTECTION] = enabled }
    }

    suspend fun setPreLockBlackoutDelayMs(delayMs: Int) {
        dataStore.edit {
            it[Keys.PRE_LOCK_BLACKOUT_DELAY_MS] = delayMs.coerceIn(
                TapLockSettings.MIN_PRE_LOCK_DELAY_MS,
                TapLockSettings.MAX_PRE_LOCK_DELAY_MS
            )
        }
    }

    suspend fun setPostLockHoldDurationMs(durationMs: Int) {
        dataStore.edit {
            it[Keys.POST_LOCK_HOLD_DURATION_MS] = durationMs.coerceIn(
                TapLockSettings.MIN_POST_LOCK_HOLD_MS,
                TapLockSettings.MAX_POST_LOCK_HOLD_MS
            )
        }
    }
}

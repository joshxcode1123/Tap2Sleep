package com.pixeltaplock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import android.widget.Toast

/**
 * Resizable, invisible Home Screen AppWidget (BlankSpaceWidgetReceiver) that users can
 * optionally place on empty Home Screen grids to trigger the QHD+ Flicker-Protected Lock
 * action upon double-tapping the widget area.
 */
class BlankSpaceWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateBlankWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateBlankWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TAP) {
            handleWidgetTap(context)
        }
    }

    private fun handleWidgetTap(context: Context) {
        val nowUptime = SystemClock.uptimeMillis()
        val timeoutMs = TapAccessibilityService.getCurrentDoubleTapTimeoutMs()

        if (isWidgetDoubleTap(lastWidgetTapUptimeMillis, nowUptime, timeoutMs)) {
            lastWidgetTapUptimeMillis = 0L
            val locked = TapAccessibilityService.triggerLockNow()
            if (!locked) {
                Toast.makeText(
                    context,
                    "Enable Tap2Sleep in Accessibility Settings to sleep the screen",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            lastWidgetTapUptimeMillis = nowUptime
        }
    }

    companion object {
        const val ACTION_WIDGET_TAP = "com.pixeltaplock.ACTION_WIDGET_TAP"

        @Volatile
        private var lastWidgetTapUptimeMillis: Long = 0L

        fun isWidgetDoubleTap(
            previousTapUptimeMillis: Long,
            currentTapUptimeMillis: Long,
            doubleTapTimeoutMs: Int
        ): Boolean {
            if (previousTapUptimeMillis <= 0L) return false
            val delta = currentTapUptimeMillis - previousTapUptimeMillis
            return delta in 16L..doubleTapTimeoutMs.toLong()
        }

        fun updateBlankWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val tapIntent = Intent(context, BlankSpaceWidgetReceiver::class.java).apply {
                action = ACTION_WIDGET_TAP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.widget_blank_space).apply {
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, BlankSpaceWidgetReceiver::class.java)
            )
            for (id in ids) {
                updateBlankWidget(context, manager, id)
            }
        }
    }
}

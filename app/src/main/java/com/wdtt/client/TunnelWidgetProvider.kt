package com.wdtt.client

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class TunnelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_TOGGLE) {
            if (TunnelManager.enabled.value) {
                TunnelControl.stop(context)
                return
            }

            try {
                VpnPermissionActivity.createPendingIntent(
                    context,
                    VpnPermissionActivity.SOURCE_WIDGET,
                    2001,
                ).send()
            } catch (e: PendingIntent.CanceledException) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "net.qwdtt.client.ACTION_TOGGLE"

        fun updateWidgetState(context: Context, enabled: Boolean, statsText: String?) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TunnelWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            
            for (widgetId in allWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.tunnel_widget)
                
                // Update status dot and title
                if (enabled) {
                    views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_green)
                    views.setTextViewText(R.id.widget_status_title, "Hoplet: Включен")
                    views.setTextViewText(R.id.widget_stats_text, statsText ?: "Туннель включен")
                    views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_pause)
                    views.setInt(R.id.widget_toggle_button, "setColorFilter", android.graphics.Color.parseColor("#FF5252")) // Red pause button
                } else {
                    views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_gray)
                    views.setTextViewText(R.id.widget_status_title, "Hoplet: Выключен")
                    views.setTextViewText(R.id.widget_stats_text, "Нажмите для подключения")
                    views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_play)
                    views.setInt(R.id.widget_toggle_button, "setColorFilter", android.graphics.Color.parseColor("#3DDC84")) // Green play button
                }
                
                // Set click listener
                val intent = Intent(context, TunnelWidgetProvider::class.java).apply {
                    action = ACTION_TOGGLE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_toggle_button, pendingIntent)
                
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.tunnel_widget)
            val enabled = TunnelManager.enabled.value
            val stats = TunnelManager.stats.value
            
            // Set initial state
            if (enabled) {
                views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_green)
                views.setTextViewText(R.id.widget_status_title, "Hoplet: Включен")
                views.setTextViewText(R.id.widget_stats_text, stats.ifBlank { "Туннель включен" })
                views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_pause)
                views.setInt(R.id.widget_toggle_button, "setColorFilter", android.graphics.Color.parseColor("#FF5252"))
            } else {
                views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_gray)
                views.setTextViewText(R.id.widget_status_title, "Hoplet: Выключен")
                views.setTextViewText(R.id.widget_stats_text, "Нажмите для подключения")
                views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_play)
                views.setInt(R.id.widget_toggle_button, "setColorFilter", android.graphics.Color.parseColor("#3DDC84"))
            }
            
            val intent = Intent(context, TunnelWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_toggle_button, pendingIntent)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

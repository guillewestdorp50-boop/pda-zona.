package com.pdazona.app
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Widget inicial de acceso rápido; clima y alertas se conectan en la siguiente etapa. */
class PdaWidgetProvider : AppWidgetProvider() {
 override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
  val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
  val pending = PendingIntent.getActivity(context, 0, launch ?: Intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  appWidgetIds.forEach { id ->
   val views = RemoteViews(context.packageName, R.layout.pda_widget)
   views.setOnClickPendingIntent(R.id.widget_root, pending)
   manager.updateAppWidget(id, views)
  }
 }
}

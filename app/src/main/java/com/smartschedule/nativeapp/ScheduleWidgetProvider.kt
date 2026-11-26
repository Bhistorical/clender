package com.smartschedule.nativeapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            renderWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun renderWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule)
        val today = LocalDate.now()
        val fmt = DateTimeFormatter.ofPattern("EEEE d MMM", Locale("th", "TH"))
        views.setTextViewText(R.id.widget_date_text, today.format(fmt))

        val prefs = context.getSharedPreferences("SmartScheduleWidgetPrefs", Context.MODE_PRIVATE)
        val evArr = JSONArray(prefs.getString("events_json", "[]") ?: "[]")
        val tkArr = JSONArray(prefs.getString("tasks_json", "[]") ?: "[]")

        data class RowModel(val title: String, val meta: String, val color: Int)
        val rows = mutableListOf<RowModel>()
        var evCount = 0
        var tkCount = 0

        for (i in 0 until evArr.length()) {
            val o = evArr.getJSONObject(i)
            if (o.optString("date") != today.toString()) continue
            evCount++
            val allDay = o.optBoolean("allDay", false)
            val timeStr = if (allDay) "ตลอดวัน" else "${SmartScheduleEngine.formatHm(o.optInt("startMin"))}–${SmartScheduleEngine.formatHm(o.optInt("endMin"))} น."
            val cat = runCatching { CategoryType.valueOf(o.optString("category")) }.getOrDefault(CategoryType.MEETING)
            rows.add(RowModel(o.optString("title"), timeStr, cat.colorHex.toInt()))
        }

        for (i in 0 until tkArr.length()) {
            val o = tkArr.getJSONObject(i)
            if (o.optBoolean("completed", false) || o.optBoolean("inInbox", false)) continue
            tkCount++
            rows.add(RowModel(o.optString("title"), "⚡ งาน • ${o.optInt("durationMinutes", 60)} นาที", 0xFFA78BFA.toInt()))
        }

        views.setTextViewText(R.id.widget_count_badge, "$evCount นัด • $tkCount งาน")

        val rowIds = intArrayOf(R.id.row_1, R.id.row_2, R.id.row_3)
        val barIds = intArrayOf(R.id.bar_1, R.id.bar_2, R.id.bar_3)
        val titleIds = intArrayOf(R.id.title_1, R.id.title_2, R.id.title_3)
        val metaIds = intArrayOf(R.id.meta_1, R.id.meta_2, R.id.meta_3)

        if (rows.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            rowIds.forEach { views.setViewVisibility(it, View.GONE) }
        } else {
            views.setViewVisibility(R.id.widget_empty, View.GONE)
            for (idx in 0..2) {
                if (idx < rows.size) {
                    views.setViewVisibility(rowIds[idx], View.VISIBLE)
                    views.setInt(barIds[idx], "setBackgroundColor", rows[idx].color)
                    views.setTextViewText(titleIds[idx], rows[idx].title)
                    views.setTextViewText(metaIds[idx], rows[idx].meta)
                } else {
                    views.setViewVisibility(rowIds[idx], View.GONE)
                }
            }
        }

        val launchIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        views.setOnClickPendingIntent(R.id.widget_add_btn, pi)

        manager.updateAppWidget(widgetId, views)
    }
}

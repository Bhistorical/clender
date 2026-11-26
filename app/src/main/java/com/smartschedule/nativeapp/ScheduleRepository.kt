package com.smartschedule.nativeapp

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("SmartScheduleWidgetPrefs", Context.MODE_PRIVATE)

    fun saveAll(events: List<CalendarEvent>, tasks: List<TaskItem>) {
        val evArr = JSONArray()
        events.forEach { ev ->
            evArr.put(
                JSONObject().apply {
                    put("id", ev.id)
                    put("title", ev.title)
                    put("date", ev.date.toString())
                    put("allDay", ev.allDay)
                    put("startMin", ev.startMin)
                    put("endMin", ev.endMin)
                    put("location", ev.location)
                    put("category", ev.category.name)
                    put("reminderContext", ev.reminderContext.name)
                    put("isRoutine", ev.isRoutine)
                }
            )
        }

        val tkArr = JSONArray()
        tasks.forEach { tk ->
            tkArr.put(
                JSONObject().apply {
                    put("id", tk.id)
                    put("title", tk.title)
                    put("dueDate", tk.dueDate?.toString() ?: "")
                    put("durationMinutes", tk.durationMinutes)
                    put("autoBlock", tk.autoBlock)
                    put("inInbox", tk.inInbox)
                    put("completed", tk.completed)
                    put("category", tk.category.name)
                    put("reminderContext", tk.reminderContext.name)
                    put("isRoutine", tk.isRoutine)
                    put("rolledFrom", tk.rolledFrom?.toString() ?: "")
                }
            )
        }

        prefs.edit()
            .putString("events_json", evArr.toString())
            .putString("tasks_json", tkArr.toString())
            .apply()

        notifyHomeScreenWidget()
    }

    fun loadEvents(): List<CalendarEvent> {
        val raw = prefs.getString("events_json", "[]") ?: "[]"
        val list = mutableListOf<CalendarEvent>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    CalendarEvent(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        date = LocalDate.parse(o.optString("date")),
                        allDay = o.optBoolean("allDay", false),
                        startMin = o.optInt("startMin", 540),
                        endMin = o.optInt("endMin", 600),
                        location = o.optString("location", ""),
                        category = runCatching { CategoryType.valueOf(o.optString("category")) }.getOrDefault(CategoryType.MEETING),
                        reminderContext = runCatching { ReminderContext.valueOf(o.optString("reminderContext")) }.getOrDefault(ReminderContext.NONE),
                        isRoutine = o.optBoolean("isRoutine", false)
                    )
                )
            }
        }
        return list
    }

    fun loadTasks(): List<TaskItem> {
        val raw = prefs.getString("tasks_json", "[]") ?: "[]"
        val list = mutableListOf<TaskItem>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dueStr = o.optString("dueDate", "")
                val rollStr = o.optString("rolledFrom", "")
                list.add(
                    TaskItem(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        dueDate = dueStr.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) },
                        durationMinutes = o.optInt("durationMinutes", 60),
                        autoBlock = o.optBoolean("autoBlock", true),
                        inInbox = o.optBoolean("inInbox", false),
                        completed = o.optBoolean("completed", false),
                        category = runCatching { CategoryType.valueOf(o.optString("category")) }.getOrDefault(CategoryType.STUDY),
                        reminderContext = runCatching { ReminderContext.valueOf(o.optString("reminderContext")) }.getOrDefault(ReminderContext.NONE),
                        isRoutine = o.optBoolean("isRoutine", false),
                        rolledFrom = rollStr.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
                    )
                )
            }
        }
        return list
    }

    fun notifyHomeScreenWidget() {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            val intent = Intent(context, ScheduleWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    // ซิงก์ 2 ทางกับ Google Calendar API v3 & Google Tasks API v1 ด้วย OAuth 2.0 Access Token
    suspend fun syncFromGoogleApis(accessToken: String): Result<Pair<List<CalendarEvent>, List<TaskItem>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val zone = ZoneId.systemDefault()
                val timeMin = ZonedDateTime.now(zone).minusDays(14).toInstant().toString()
                val calJson = httpGet(
                    "https://www.googleapis.com/calendar/v3/calendars/primary/events?singleEvents=true&orderBy=startTime&timeMin=$timeMin&maxResults=100",
                    accessToken
                )
                val tasksJson = httpGet(
                    "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks?showCompleted=true&maxResults=100",
                    accessToken
                )

                val syncedEvents = mutableListOf<CalendarEvent>()
                val calItems = JSONObject(calJson).optJSONArray("items") ?: JSONArray()
                for (i in 0 until calItems.length()) {
                    val item = calItems.getJSONObject(i)
                    if (item.optString("status") == "cancelled") continue
                    val startObj = item.optJSONObject("start") ?: continue
                    val endObj = item.optJSONObject("end")
                    val dateOnly = startObj.optString("date", "")
                    if (dateOnly.isNotEmpty()) {
                        syncedEvents.add(
                            CalendarEvent(
                                id = item.optString("id"),
                                title = item.optString("summary", "(ไม่มีชื่อนัดหมาย)"),
                                date = LocalDate.parse(dateOnly),
                                allDay = true,
                                location = item.optString("location", "")
                            )
                        )
                    } else {
                        val sIso = startObj.optString("dateTime", "")
                        if (sIso.isNotEmpty()) {
                            val sZdt = Instant.parse(sIso).atZone(zone)
                            val eZdt = endObj?.optString("dateTime", "")?.takeIf { it.isNotEmpty() }
                                ?.let { Instant.parse(it).atZone(zone) } ?: sZdt.plusHours(1)
                            syncedEvents.add(
                                CalendarEvent(
                                    id = item.optString("id"),
                                    title = item.optString("summary", "(ไม่มีชื่อนัดหมาย)"),
                                    date = sZdt.toLocalDate(),
                                    allDay = false,
                                    startMin = sZdt.hour * 60 + sZdt.minute,
                                    endMin = eZdt.hour * 60 + eZdt.minute,
                                    location = item.optString("location", "")
                                )
                            )
                        }
                    }
                }

                val syncedTasks = mutableListOf<TaskItem>()
                val tkItems = JSONObject(tasksJson).optJSONArray("items") ?: JSONArray()
                for (i in 0 until tkItems.length()) {
                    val t = tkItems.getJSONObject(i)
                    if (t.optBoolean("deleted", false)) continue
                    val title = t.optString("title", "").trim()
                    if (title.isEmpty()) continue
                    val dueRaw = t.optString("due", "")
                    syncedTasks.add(
                        TaskItem(
                            id = t.optString("id"),
                            title = title,
                            dueDate = dueRaw.takeIf { it.length >= 10 }?.let { LocalDate.parse(it.substring(0, 10)) } ?: LocalDate.now(),
                            completed = t.optString("status") == "completed"
                        )
                    )
                }

                syncedEvents to syncedTasks
            }
        }

    private fun httpGet(urlStr: String, bearerToken: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $bearerToken")
            connectTimeout = 10000
            readTimeout = 10000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) error("Google API HTTP $code: $body")
        return body
    }
}

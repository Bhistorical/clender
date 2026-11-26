package com.smartschedule.nativeapp

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class CategoryType(val colorHex: Long, val label: String) {
    MEETING(0xFF4F9CF9, "ประชุม/งาน"),
    STUDY(0xFFF59E0B, "โฟกัส/เอกสาร"),
    FITNESS(0xFF10B981, "สุขภาพ/ฟิตเนส"),
    PERSONAL(0xFF14B8A6, "ส่วนตัว"),
    AUTOBLOCK(0xFFA78BFA, "Time-block อัตโนมัติ");

    val isPersonalGroup: Boolean
        get() = this == PERSONAL || this == FITNESS
}

enum class FocusMode(val label: String) {
    ALL("โหมด: ทั้งหมด"),
    WORK("โหมด: งาน"),
    PERSONAL("โหมด: ส่วนตัว")
}

enum class AppTab(val label: String) {
    STREAM("วัน"),
    THREE_DAY("สัปดาห์ (3วัน)"),
    MONTH_AGENDA("เดือน"),
    TASKS("งาน")
}

enum class ReminderContext(val label: String) {
    NONE("ตามเวลาปกติ"),
    AFTER_MEETING("หลังจบประชุม"),
    ARRIVED_HOME("เมื่อถึงบ้าน"),
    ARRIVED_OFFICE("เมื่อถึงออฟฟิศ")
}

data class CalendarEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: LocalDate,
    val allDay: Boolean = false,
    val startMin: Int = 9 * 60,
    val endMin: Int = 10 * 60,
    val location: String = "",
    val category: CategoryType = CategoryType.MEETING,
    val reminderContext: ReminderContext = ReminderContext.NONE,
    val isRoutine: Boolean = false
)

data class TaskItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val dueDate: LocalDate? = LocalDate.now(),
    val durationMinutes: Int = 60,
    val autoBlock: Boolean = true,
    val inInbox: Boolean = false,
    val completed: Boolean = false,
    val category: CategoryType = CategoryType.STUDY,
    val reminderContext: ReminderContext = ReminderContext.NONE,
    val isRoutine: Boolean = false,
    val rolledFrom: LocalDate? = null
)

data class ScheduledBlock(
    val id: String,
    val sourceId: String,
    val title: String,
    val date: LocalDate,
    val startMin: Int,
    val endMin: Int,
    val location: String,
    val category: CategoryType,
    val isAutoBlock: Boolean,
    val dodgedConflict: Boolean = false
)

object SmartScheduleEngine {

    val THAI_HOLIDAYS = mapOf(
        "01-01" to "วันขึ้นปีใหม่",
        "04-06" to "วันจักรี",
        "04-13" to "วันสงกรานต์",
        "04-14" to "วันสงกรานต์",
        "04-15" to "วันสงกรานต์",
        "05-01" to "วันแรงงานแห่งชาติ",
        "05-04" to "วันฉัตรมงคล",
        "06-03" to "วันเฉลิมฯ พระราชินี",
        "07-28" to "วันเฉลิมฯ ร.10",
        "08-12" to "วันแม่แห่งชาติ",
        "10-13" to "วันนวมินทรมหาราช",
        "10-23" to "วันปิยมหาราช",
        "12-05" to "วันพ่อแห่งชาติ",
        "12-10" to "วันรัฐธรรมนูญ",
        "12-31" to "วันสิ้นปี"
    )

    fun holidayName(date: LocalDate): String? {
        val key = "%02d-%02d".format(date.monthValue, date.dayOfMonth)
        return THAI_HOLIDAYS[key]
    }

    // Auto Roll-over: เลื่อนงานค้างจากวันก่อนมารวมในวันนี้โดยไม่ค้างจุดแดง
    fun rollOverPastTasks(tasks: List<TaskItem>, today: LocalDate = LocalDate.now()): List<TaskItem> {
        return tasks.map { task ->
            if (!task.completed && !task.inInbox && task.dueDate != null && task.dueDate.isBefore(today)) {
                task.copy(dueDate = today, rolledFrom = task.rolledFrom ?: task.dueDate)
            } else {
                task
            }
        }
    }

    // Auto Time-blocking: หาช่องว่าง 09:00-18:00 และเลื่อนหลบอัตโนมัติเมื่อมีนัดแทรก
    fun computeAutoBlocks(
        events: List<CalendarEvent>,
        tasks: List<TaskItem>,
        today: LocalDate = LocalDate.now()
    ): List<ScheduledBlock> {
        val busyByDay = mutableMapOf<LocalDate, MutableList<IntRange>>()
        events.forEach { ev ->
            val list = busyByDay.getOrPut(ev.date) { mutableListOf() }
            if (ev.allDay && !ev.category.isPersonalGroup) {
                list.add((9 * 60)..(18 * 60))
            } else if (!ev.allDay) {
                list.add(ev.startMin..ev.endMin)
            }
        }

        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val result = mutableListOf<ScheduledBlock>()

        tasks.filter { !it.completed && !it.inInbox && it.autoBlock }.forEach { tk ->
            val startDay = tk.dueDate?.takeIf { !it.isBefore(today) } ?: today
            val dur = tk.durationMinutes.coerceIn(15, 240)
            var placed = false
            var dayOffset = 0L

            while (!placed && dayOffset < 21L) {
                val candDate = startDay.plusDays(dayOffset)
                val dayBusy = busyByDay.getOrPut(candDate) { mutableListOf() }
                dayBusy.sortBy { it.first }

                var cursor = 9 * 60
                if (candDate == today) {
                    val roundedNow = ceil(nowMin / 15.0).toInt() * 15
                    cursor = max(cursor, roundedNow)
                }

                var dodged = false
                while (cursor + dur <= 18 * 60) {
                    val candEnd = cursor + dur
                    val hit = dayBusy.firstOrNull { cursor < it.last && candEnd > it.first }
                    if (hit == null) {
                        dayBusy.add(cursor..candEnd)
                        result.add(
                            ScheduledBlock(
                                id = "tb-${tk.id}",
                                sourceId = tk.id,
                                title = tk.title,
                                date = candDate,
                                startMin = cursor,
                                endMin = candEnd,
                                location = "",
                                category = CategoryType.AUTOBLOCK,
                                isAutoBlock = true,
                                dodgedConflict = dodged
                            )
                        )
                        placed = true
                        break
                    } else {
                        dodged = true
                        cursor = ceil(hit.last / 15.0).toInt() * 15
                    }
                }
                dayOffset++
            }
        }
        return result
    }

    // Natural Language Input Parser ภาษาไทย
    fun parseNaturalLanguage(input: String, defaultDate: LocalDate = LocalDate.now()): CalendarEvent? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        var working = raw

        var category = CategoryType.MEETING
        if (Regex("(#ส่วนตัว|\\(ส่วนตัว\\))").containsMatchIn(working)) {
            category = CategoryType.PERSONAL
            working = working.replace(Regex("(#ส่วนตัว|\\(ส่วนตัว\\))"), " ")
        }

        val today = LocalDate.now()
        var date = defaultDate
        when {
            working.contains("มะรืน") -> {
                date = today.plusDays(2)
                working = working.replaceFirst(Regex("มะรืน(นี้)?"), " ")
            }
            working.contains("พรุ่งนี้") -> {
                date = today.plusDays(1)
                working = working.replaceFirst("พรุ่งนี้", " ")
            }
            working.contains("วันนี้") -> {
                date = today
                working = working.replaceFirst("วันนี้", " ")
            }
            else -> {
                val weekdays = listOf(
                    "จันทร์" to DayOfWeek.MONDAY,
                    "อังคาร" to DayOfWeek.TUESDAY,
                    "พุธ" to DayOfWeek.WEDNESDAY,
                    "พฤหัส" to DayOfWeek.THURSDAY,
                    "ศุกร์" to DayOfWeek.FRIDAY,
                    "เสาร์" to DayOfWeek.SATURDAY,
                    "อาทิตย์" to DayOfWeek.SUNDAY
                )
                for ((name, dow) in weekdays) {
                    val m = Regex("(?:วัน)?$name(?:บดี)?(หน้า)?").find(working)
                    if (m != null) {
                        var diff = (dow.value - today.dayOfWeek.value + 7) % 7
                        if (diff == 0 || m.groupValues[1].isNotEmpty()) diff += 7
                        date = today.plusDays(diff.toLong())
                        working = working.replaceFirst(m.value, " ")
                        break
                    }
                }
            }
        }

        var allDay = true
        var startMin = 9 * 60
        var endMin = 10 * 60

        val rangeRx = Regex("(?:เวลา\\s*)?(\\d{1,2})[:.](\\d{2})\\s*(?:-|–|ถึง)\\s*(\\d{1,2})[:.](\\d{2})")
        val singleRx = Regex("(?:เวลา\\s*)?\\b(\\d{1,2})[:.](\\d{2})\\b")

        val rm = rangeRx.find(working)
        if (rm != null) {
            allDay = false
            startMin = (rm.groupValues[1].toInt().coerceIn(0, 23) * 60) + rm.groupValues[2].toInt().coerceIn(0, 59)
            endMin = (rm.groupValues[3].toInt().coerceIn(0, 23) * 60) + rm.groupValues[4].toInt().coerceIn(0, 59)
            if (endMin <= startMin) endMin = min(1439, startMin + 60)
            working = working.replaceFirst(rm.value, " ")
        } else {
            val sm = singleRx.find(working)
            if (sm != null) {
                allDay = false
                startMin = (sm.groupValues[1].toInt().coerceIn(0, 23) * 60) + sm.groupValues[2].toInt().coerceIn(0, 59)
                endMin = min(1439, startMin + 60)
                working = working.replaceFirst(sm.value, " ")
            }
        }

        var location = ""
        val locRx = Regex("(?:@|(?:^|\\s)ที่\\s*)([^#@]+)$")
        val lm = locRx.find(working)
        if (lm != null) {
            location = lm.groupValues[1].trim()
            working = working.removeRange(lm.range).trim()
        }

        val cleanTitle = working.replace(Regex("\\s+"), " ").trim().ifEmpty { "นัดหมายใหม่" }
        if (category == CategoryType.MEETING) {
            category = when {
                Regex("วิ่ง|ฟิตเนส|ยิม|ออกกำลัง|โยคะ").containsMatchIn(cleanTitle) -> CategoryType.FITNESS
                Regex("อ่าน|สรุป|สไลด์|รายงาน|เรียน|โฟกัส").containsMatchIn(cleanTitle) -> CategoryType.STUDY
                else -> CategoryType.MEETING
            }
        }

        return CalendarEvent(
            title = cleanTitle,
            date = date,
            allDay = allDay,
            startMin = startMin,
            endMin = endMin,
            location = location,
            category = category
        )
    }

    // Built-in Scheduling Link: คำนวณสล็อตเวลาว่างหลบทั้งนัดและ Auto Time-block
    fun findMutualFreeSlots(
        events: List<CalendarEvent>,
        autoBlocks: List<ScheduledBlock>,
        durationMin: Int = 60
    ): List<Pair<LocalDate, IntRange>> {
        val slots = mutableListOf<Pair<LocalDate, IntRange>>()
        val today = LocalDate.now()
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }

        for (d in 0..3) {
            val day = today.plusDays(d.toLong())
            val busy = buildList {
                events.filter { it.date == day && !it.allDay }.forEach { add(it.startMin..it.endMin) }
                autoBlocks.filter { it.date == day }.forEach { add(it.startMin..it.endMin) }
            }
            for (hour in 9..16) {
                val s = hour * 60
                val e = s + durationMin
                if (day == today && s <= nowMin) continue
                if (busy.none { s < it.last && e > it.first }) {
                    slots.add(day to (s..e))
                    if (slots.size >= 8) return slots
                }
            }
        }
        return slots
    }

    fun formatHm(mins: Int): String {
        val c = mins.coerceIn(0, 1439)
        return "%02d:%02d".format(c / 60, c % 60)
    }
}

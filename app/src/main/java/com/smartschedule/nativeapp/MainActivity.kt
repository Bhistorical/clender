package com.smartschedule.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val BgDark = Color(0xFF121212)
private val Surface1 = Color(0xFF1B1B1E)
private val Surface2 = Color(0xFF242428)
private val Surface3 = Color(0xFF2E2E34)
private val TextPrimary = Color(0xFFEDEDED)
private val TextSecondary = Color(0xFFB4B8C0)
private val TextMuted = Color(0xFF7E8590)
private val BrandBlue = Color(0xFF4F9CF9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = ScheduleRepository(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgDark,
                    surface = Surface1,
                    primary = BrandBlue,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            ) {
                SmartScheduleApp(repository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartScheduleApp(repository: ScheduleRepository) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val thaiLocale = remember { Locale("th", "TH") }

    var events by remember { mutableStateOf(repository.loadEvents()) }
    var tasks by remember { mutableStateOf(SmartScheduleEngine.rollOverPastTasks(repository.loadTasks())) }

    var activeTab by remember { mutableStateOf(AppTab.MONTH_AGENDA) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var threeDayAnchor by remember { mutableStateOf(LocalDate.now()) }
    var monthCollapsed by remember { mutableStateOf(false) }
    var collapseNightHours by remember { mutableStateOf(true) }
    var snapMinutes by remember { mutableIntStateOf(15) }
    var focusMode by remember { mutableStateOf(FocusMode.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    var activeReminderCtx by remember { mutableStateOf(ReminderContext.NONE) }
    var skipHolidays by remember { mutableStateOf(true) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var oauthAccessToken by remember { mutableStateOf("") }
    var syncStatusMsg by remember { mutableStateOf("โหมดในเครื่อง") }

    fun persistChanges(newEvents: List<CalendarEvent>, newTasks: List<TaskItem>) {
        val rolled = SmartScheduleEngine.rollOverPastTasks(newTasks)
        events = newEvents
        tasks = rolled
        repository.saveAll(newEvents, rolled)
    }

    val autoBlocks = remember(events, tasks) {
        SmartScheduleEngine.computeAutoBlocks(events, tasks)
    }

    fun matchesFilter(title: String, location: String, cat: CategoryType): Boolean {
        if (focusMode == FocusMode.WORK && cat.isPersonalGroup) return false
        if (focusMode == FocusMode.PERSONAL && !cat.isPersonalGroup) return false
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            if (!title.lowercase().contains(q) && !location.lowercase().contains(q)) return false
        }
        return true
    }

    val filteredEvents = events.filter { matchesFilter(it.title, it.location, it.category) }
    val filteredBlocks = autoBlocks.filter { matchesFilter(it.title, "", it.category) }
    val filteredTasks = tasks.filter { matchesFilter(it.title, "", it.category) }

    val today = LocalDate.now()
    val todayHoliday = SmartScheduleEngine.holidayName(today)
    val triggeredReminders = remember(events, tasks, activeReminderCtx, skipHolidays) {
        if (activeReminderCtx == ReminderContext.NONE) emptyList()
        else buildList {
            events.filter { it.reminderContext == activeReminderCtx }.forEach {
                if (!(skipHolidays && it.isRoutine && todayHoliday != null)) add(it.title)
            }
            tasks.filter { !it.completed && it.reminderContext == activeReminderCtx }.forEach {
                if (!(skipHolidays && it.isRoutine && todayHoliday != null)) add(it.title)
            }
        }
    }

    val monthHeaderFmt = remember { DateTimeFormatter.ofPattern("MMMM yyyy", thaiLocale) }
    val headerDate = if (activeTab == AppTab.THREE_DAY) threeDayAnchor else selectedDate
    val todayBusyMins = (events.filter { it.date == today && !it.allDay }.sumOf { (it.endMin - it.startMin).coerceAtLeast(0) } +
        autoBlocks.filter { it.date == today }.sumOf { (it.endMin - it.startMin).coerceAtLeast(0) }).coerceAtMost(540)
    val freeHoursToday = ((540 - todayBusyMins) / 60f)

    Scaffold(
        containerColor = BgDark,
        topBar = {
            // 1. Passive Top Header: อ่านชื่อเดือนและสถิติอย่างเดียว ไม่ต้องเอื้อมนิ้วกด
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgDark)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = headerDate.format(monthHeaderFmt),
                        color = TextPrimary,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Surface1)
                            .border(1.dp, Surface3, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(syncStatusMsg, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "นัดวันนี้ ${events.count { it.date == today }}  •  งานตามแผน ${tasks.count { !it.completed && !it.inInbox }}  •  กล่องพักงาน ${tasks.count { !it.completed && it.inInbox }}  •  ว่าง ${"%.1f".format(freeHoursToday)} ชม.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                AnimatedVisibility(visible = triggeredReminders.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2D2149))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "แจ้งเตือน (${activeReminderCtx.label}): ${triggeredReminders.joinToString(", ")}",
                            color = Color(0xFFDDD6FE),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        bottomBar = {
            // 1. Bottom-First & Thumb Zone (โซนนิ้วโป้งด้านล่าง 20% ของหน้าจอ)
            Surface(
                color = Color(0xF018181B),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("ค้นหานัด/งาน...", fontSize = 12.sp, color = TextMuted) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(50),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Surface2,
                                unfocusedContainerColor = Surface1,
                                focusedBorderColor = BrandBlue,
                                unfocusedBorderColor = Surface3
                            )
                        )
                        ThumbChip("วันนี้") {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedDate = LocalDate.now()
                            threeDayAnchor = LocalDate.now()
                        }
                        ThumbChip("ล็อก ${snapMinutes}น.") {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            snapMinutes = if (snapMinutes == 15) 30 else 15
                        }
                        ThumbChip(focusMode.label) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            focusMode = FocusMode.entries[(focusMode.ordinal + 1) % FocusMode.entries.size]
                        }
                        ThumbChip("เมนู") {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showToolsSheet = true
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Surface1)
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            AppTab.entries.forEach { tab ->
                                val selected = activeTab == tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(if (selected) Surface3 else Color.Transparent)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            activeTab = tab
                                        }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tab.label,
                                        color = if (selected) Color.White else TextMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showCreateSheet = true
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("สร้าง", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                AppTab.MONTH_AGENDA -> CollapsibleMonthAgendaPane(
                    selectedDate = selectedDate,
                    monthCollapsed = monthCollapsed,
                    collapseNightHours = collapseNightHours,
                    snapMinutes = snapMinutes,
                    events = filteredEvents,
                    autoBlocks = filteredBlocks,
                    onSelectDate = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedDate = it
                        threeDayAnchor = it
                    },
                    onToggleCollapse = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        monthCollapsed = it
                    },
                    onExpandNight = { collapseNightHours = false },
                    onUpdateEventTime = { id, newStart, newEnd ->
                        val updated = events.map {
                            if (it.id == id) it.copy(startMin = newStart, endMin = newEnd) else it
                        }
                        persistChanges(updated, tasks)
                    },
                    onDeleteEvent = { id ->
                        persistChanges(events.filterNot { it.id == id }, tasks)
                    }
                )

                AppTab.THREE_DAY -> ThreeDaySwipePane(
                    anchorDate = threeDayAnchor,
                    collapseNightHours = collapseNightHours,
                    snapMinutes = snapMinutes,
                    events = filteredEvents,
                    autoBlocks = filteredBlocks,
                    onShiftDays = { delta ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        threeDayAnchor = threeDayAnchor.plusDays(delta.toLong())
                    },
                    onExpandNight = { collapseNightHours = false },
                    onUpdateEventTime = { id, newStart, newEnd ->
                        val updated = events.map {
                            if (it.id == id) it.copy(startMin = newStart, endMin = newEnd) else it
                        }
                        persistChanges(updated, tasks)
                    },
                    onDeleteEvent = { id ->
                        persistChanges(events.filterNot { it.id == id }, tasks)
                    }
                )

                AppTab.STREAM -> ContinuousStreamPane(
                    events = filteredEvents,
                    autoBlocks = filteredBlocks,
                    onQuickAddAtDate = { date ->
                        selectedDate = date
                        showCreateSheet = true
                    }
                )

                AppTab.TASKS -> SwipeTasksAndInboxPane(
                    tasks = filteredTasks,
                    autoBlocks = autoBlocks,
                    onToggleDone = { id ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        persistChanges(events, tasks.map { if (it.id == id) it.copy(completed = !it.completed) else it })
                    },
                    onSwipeTomorrow = { id ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val tmr = LocalDate.now().plusDays(1)
                        persistChanges(events, tasks.map { if (it.id == id) it.copy(dueDate = tmr, inInbox = false) else it })
                    },
                    onMoveToInbox = { id ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        persistChanges(events, tasks.map { if (it.id == id) it.copy(inInbox = true, dueDate = null) else it })
                    },
                    onScheduleToday = { id ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        persistChanges(events, tasks.map { if (it.id == id) it.copy(inInbox = false, dueDate = LocalDate.now(), autoBlock = true) else it })
                    },
                    onDeleteTask = { id ->
                        persistChanges(events, tasks.filterNot { it.id == id })
                    }
                )
            }
        }
    }

    // Bottom Sheet 1: เพิ่มนัดหมาย (พิมพ์ภาษาคน NLP + กรอกละเอียด) & เพิ่มงาน Auto Time-block
    if (showCreateSheet) {
        CreateItemBottomSheet(
            initialDate = selectedDate,
            onDismiss = { showCreateSheet = false },
            onAddEvent = { newEv ->
                selectedDate = newEv.date
                persistChanges(events + newEv, tasks)
                showCreateSheet = false
            },
            onAddTask = { newTk ->
                persistChanges(events, listOf(newTk) + tasks)
                showCreateSheet = false
            }
        )
    }

    // Bottom Sheet 2: ลิงก์นัดเวลาว่างในตัว + เงื่อนไขแจ้งเตือน + Google Calendar/Tasks Sync
    if (showToolsSheet) {
        ToolsAndSyncBottomSheet(
            events = events,
            autoBlocks = autoBlocks,
            activeReminderCtx = activeReminderCtx,
            skipHolidays = skipHolidays,
            collapseNightHours = collapseNightHours,
            oauthToken = oauthAccessToken,
            onChangeContext = { activeReminderCtx = it },
            onToggleHolidays = { skipHolidays = it },
            onToggleNight = { collapseNightHours = it },
            onBookFreeSlot = { date, range, guest, topic ->
                val booked = CalendarEvent(
                    title = "$topic (นัดโดย $guest)",
                    date = date,
                    allDay = false,
                    startMin = range.first,
                    endMin = range.last,
                    location = "ลิงก์นัดเวลา",
                    category = CategoryType.MEETING
                )
                persistChanges(events + booked, tasks)
                showToolsSheet = false
            },
            onSyncGoogle = { token ->
                oauthAccessToken = token
                scope.launch {
                    val res = repository.syncFromGoogleApis(token)
                    res.onSuccess { (gEvents, gTasks) ->
                        syncStatusMsg = "ซิงก์ Google API แล้ว"
                        persistChanges(gEvents, gTasks)
                    }.onFailure { err ->
                        syncStatusMsg = "ซิงก์ไม่สำเร็จ: ${err.message?.take(28)}"
                    }
                }
            },
            onCopyLink = { url ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Scheduling Link", url))
            },
            onDismiss = { showToolsSheet = false }
        )
    }
}

@Composable
private fun ThumbChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Surface2)
            .border(1.dp, Surface3, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// 2. Collapsible Month + Agenda View (ปัดขึ้นเพื่อหดปฏิทินเดือนเหลือแถบ 1 สัปดาห์)
@Composable
private fun CollapsibleMonthAgendaPane(
    selectedDate: LocalDate,
    monthCollapsed: Boolean,
    collapseNightHours: Boolean,
    snapMinutes: Int,
    events: List<CalendarEvent>,
    autoBlocks: List<ScheduledBlock>,
    onSelectDate: (LocalDate) -> Unit,
    onToggleCollapse: (Boolean) -> Unit,
    onExpandNight: () -> Unit,
    onUpdateEventTime: (String, Int, Int) -> Unit,
    onDeleteEvent: (String) -> Unit
) {
    val gridHeight by animateDpAsState(targetValue = if (monthCollapsed) 52.dp else 216.dp, label = "monthHeight")
    val datesToShow = remember(selectedDate, monthCollapsed) {
        if (monthCollapsed) {
            val sunday = selectedDate.minusDays((selectedDate.dayOfWeek.value % 7).toLong())
            (0L..6L).map { sunday.plusDays(it) }
        } else {
            val first = selectedDate.withDayOfMonth(1)
            val startOffset = first.dayOfWeek.value % 7
            val gridStart = first.minusDays(startOffset.toLong())
            (0L..34L).map { gridStart.plusDays(it) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface1)
                .pointerInput(monthCollapsed) {
                    var totalDy = 0f
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount -> totalDy += dragAmount },
                        onDragEnd = {
                            if (totalDy < -28f && !monthCollapsed) onToggleCollapse(true)
                            else if (totalDy > 28f && monthCollapsed) onToggleCollapse(false)
                            totalDy = 0f
                        }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                listOf("อา", "จ", "อ", "พ", "พฤ", "ศ", "ส").forEach { d ->
                    Text(d, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.height(gridHeight)) {
                datesToShow.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { d ->
                            val isSel = d == selectedDate
                            val isToday = d == LocalDate.now()
                            val dayDots = buildList {
                                events.filter { it.date == d }.take(2).forEach { add(Color(it.category.colorHex)) }
                                if (autoBlocks.any { it.date == d }) add(Color(CategoryType.AUTOBLOCK.colorHex))
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) BrandBlue.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable { onSelectDate(d) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = d.dayOfMonth.toString(),
                                    color = when {
                                        isSel -> Color.White
                                        isToday -> BrandBlue
                                        d.month != selectedDate.month -> TextMuted.copy(alpha = 0.45f)
                                        else -> TextPrimary
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = if (isSel || isToday) FontWeight.Bold else FontWeight.Medium
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    dayDots.take(3).forEach { dotColor ->
                                        Box(Modifier.size(4.5.dp).clip(CircleShape).background(dotColor))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleCollapse(!monthCollapsed) }
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.width(38.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Surface3))
            }
        }

        ScannableHourlyTimeline(
            dates = listOf(selectedDate),
            collapseNightHours = collapseNightHours,
            snapMinutes = snapMinutes,
            events = events,
            autoBlocks = autoBlocks,
            onExpandNight = onExpandNight,
            onUpdateEventTime = onUpdateEventTime,
            onDeleteEvent = onDeleteEvent
        )
    }
}

// 3. 3-Day Weekly View (วันนี้ + พรุ่งนี้ + มะรืน ปัดซ้าย-ขวาเลื่อนวัน)
@Composable
private fun ThreeDaySwipePane(
    anchorDate: LocalDate,
    collapseNightHours: Boolean,
    snapMinutes: Int,
    events: List<CalendarEvent>,
    autoBlocks: List<ScheduledBlock>,
    onShiftDays: (Int) -> Unit,
    onExpandNight: () -> Unit,
    onUpdateEventTime: (String, Int, Int) -> Unit,
    onDeleteEvent: (String) -> Unit
) {
    val dates = remember(anchorDate) { (0L..2L).map { anchorDate.plusDays(it) } }
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale("th", "TH")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(anchorDate) {
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount -> totalDx += dragAmount },
                    onDragEnd = {
                        if (totalDx < -60f) onShiftDays(3)
                        else if (totalDx > 60f) onShiftDays(-3)
                        totalDx = 0f
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface1)
                .padding(vertical = 8.dp)
        ) {
            Spacer(Modifier.width(50.dp))
            dates.forEach { d ->
                Text(
                    text = d.format(fmt),
                    color = if (d == LocalDate.now()) BrandBlue else TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        ScannableHourlyTimeline(
            dates = dates,
            collapseNightHours = collapseNightHours,
            snapMinutes = snapMinutes,
            events = events,
            autoBlocks = autoBlocks,
            onExpandNight = onExpandNight,
            onUpdateEventTime = onUpdateEventTime,
            onDeleteEvent = onDeleteEvent
        )
    }
}

// 4 & 5. ไทม์ไลน์โหมดมืด #121212 ย่อช่วง 01:00–06:00 + แตะค้างลากย้ายและดึงขอบล่างยืดเวลาพร้อม Haptic
@Composable
private fun ScannableHourlyTimeline(
    dates: List<LocalDate>,
    collapseNightHours: Boolean,
    snapMinutes: Int,
    events: List<CalendarEvent>,
    autoBlocks: List<ScheduledBlock>,
    onExpandNight: () -> Unit,
    onUpdateEventTime: (String, Int, Int) -> Unit,
    onDeleteEvent: (String) -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val hourDp = 56.dp
    val collapsedNightDp = 28.dp
    val hourPx = with(density) { hourDp.toPx() }
    val nightPx = with(density) { collapsedNightDp.toPx() }

    val hasNightItems = events.any { it.date in dates && !it.allDay && it.startMin < 360 && it.endMin > 60 }
    val nightCollapsed = collapseNightHours && !hasNightItems

    fun minToDp(min: Int): Dp {
        if (!nightCollapsed) return hourDp * (min / 60f)
        return when {
            min <= 60 -> hourDp * (min / 60f)
            min <= 360 -> hourDp + collapsedNightDp * ((min - 60) / 300f)
            else -> hourDp + collapsedNightDp + hourDp * ((min - 360) / 60f)
        }
    }

    val totalHeight = if (nightCollapsed) (hourDp * 19 + collapsedNightDp) else (hourDp * 24)
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Column(modifier = Modifier.width(50.dp).height(totalHeight)) {
            for (h in 0..23) {
                if (nightCollapsed && h in 1..5) {
                    if (h == 1) {
                        Box(Modifier.height(collapsedNightDp).fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text("01–06", color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                    continue
                }
                Box(Modifier.height(hourDp).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    Text("%02d:00".format(h), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp, top = 2.dp))
                }
            }
        }

        dates.forEach { day ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(totalHeight)
                    .border(width = 0.5.dp, color = Surface2)
            ) {
                Column(Modifier.fillMaxSize()) {
                    for (h in 0..23) {
                        if (nightCollapsed && h in 1..5) {
                            if (h == 1) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(collapsedNightDp)
                                        .background(Surface1)
                                        .clickable { onExpandNight() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("── ซ่อนช่วงพักผ่อน 01:00–06:00 (แตะเพื่อขยาย) ──", color = TextMuted, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                            continue
                        }
                        Box(Modifier.fillMaxWidth().height(hourDp).border(0.5.dp, Color(0x12FFFFFF)))
                    }
                }

                val dayBlocks = buildList {
                    events.filter { it.date == day && !it.allDay }.forEach {
                        add(ScheduledBlock(it.id, it.id, it.title, it.date, it.startMin, it.endMin, it.location, it.category, false))
                    }
                    addAll(autoBlocks.filter { it.date == day })
                }

                dayBlocks.forEach { blk ->
                    var dragStartMin by remember(blk.id, blk.startMin) { mutableIntStateOf(blk.startMin) }
                    var dragEndMin by remember(blk.id, blk.endMin) { mutableIntStateOf(blk.endMin) }

                    val topDp = minToDp(dragStartMin)
                    val heightDp = (minToDp(dragEndMin) - topDp).coerceAtLeast(28.dp)
                    val accent = Color(blk.category.colorHex)

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .offset(y = topDp)
                            .fillMaxWidth()
                            .height(heightDp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (blk.isAutoBlock) accent.copy(alpha = 0.12f) else Surface2)
                            .pointerInput(blk.id, snapMinutes) {
                                if (blk.isAutoBlock) return@pointerInput
                                var accPx = 0f
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        accPx += dragAmount.y
                                        val stepPx = (snapMinutes / 60f) * hourPx
                                        val steps = (accPx / stepPx).toInt()
                                        if (steps != 0) {
                                            accPx -= steps * stepPx
                                            val dur = dragEndMin - dragStartMin
                                            val nextStart = (dragStartMin + steps * snapMinutes).coerceIn(0, 1440 - dur)
                                            if (nextStart != dragStartMin) {
                                                dragStartMin = nextStart
                                                dragEndMin = nextStart + dur
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onDragEnd = { onUpdateEventTime(blk.sourceId, dragStartMin, dragEndMin) }
                                )
                            }
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
                            Column(Modifier.weight(1f).padding(horizontal = 7.dp, vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(blk.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (!blk.isAutoBlock) {
                                        Text("✕", color = TextMuted, fontSize = 11.sp, modifier = Modifier.clickable { onDeleteEvent(blk.sourceId) })
                                    }
                                }
                                Text(
                                    text = "${SmartScheduleEngine.formatHm(dragStartMin)}–${SmartScheduleEngine.formatHm(dragEndMin)}" +
                                        if (blk.location.isNotBlank()) " • ${blk.location}" else "",
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        // ขอบล่างสำหรับดึงยืด-หดเวลา (Snap-to-grid Resize Handle)
                        if (!blk.isAutoBlock) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .pointerInput(blk.id, snapMinutes) {
                                        var accPx = 0f
                                        detectDragGestures(
                                            onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                accPx += dragAmount.y
                                                val stepPx = (snapMinutes / 60f) * hourPx
                                                val steps = (accPx / stepPx).toInt()
                                                if (steps != 0) {
                                                    accPx -= steps * stepPx
                                                    val nextEnd = (dragEndMin + steps * snapMinutes).coerceIn(dragStartMin + snapMinutes, 1440)
                                                    if (nextEnd != dragEndMin) {
                                                        dragEndMin = nextEnd
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                }
                                            },
                                            onDragEnd = { onUpdateEventTime(blk.sourceId, dragStartMin, dragEndMin) }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(Modifier.width(22.dp).height(3.dp).clip(RoundedCornerShape(50)).background(Surface3))
                            }
                        }
                    }
                }
            }
        }
    }
}

// 5. Continuous Scroll เลื่อนดูข้ามเดือนไร้รอยต่อ
@Composable
private fun ContinuousStreamPane(
    events: List<CalendarEvent>,
    autoBlocks: List<ScheduledBlock>,
    onQuickAddAtDate: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val days = remember { (-2L..90L).map { today.plusDays(it) } }
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale("th", "TH")) }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(days, key = { it.toString() }) { day ->
            val holiday = SmartScheduleEngine.holidayName(day)
            val dayEvents = events.filter { it.date == day }
            val dayBlocks = autoBlocks.filter { it.date == day }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .clickable { onQuickAddAtDate(day) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.width(92.dp)) {
                    Text(day.format(fmt), color = if (day == today) BrandBlue else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (holiday != null) {
                        Text(holiday, color = Color(0xFFFB7185), fontSize = 10.sp)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (dayEvents.isEmpty() && dayBlocks.isEmpty()) {
                        Text("+ ว่าง — แตะเพื่อเพิ่มนัดหมาย", color = TextMuted, fontSize = 12.sp)
                    }
                    dayEvents.forEach { ev ->
                        Text(
                            text = (if (ev.allDay) "ตลอดวัน" else "${SmartScheduleEngine.formatHm(ev.startMin)}–${SmartScheduleEngine.formatHm(ev.endMin)}") + " • ${ev.title}",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    dayBlocks.forEach { tb ->
                        Text(
                            text = "⚡ ${SmartScheduleEngine.formatHm(tb.startMin)}–${SmartScheduleEngine.formatHm(tb.endMin)} • ${tb.title}",
                            color = Color(0xFFC4B5FD),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// 4. Swipeable Tasks + กล่องพักงาน (Unscheduled Inbox)
@Composable
private fun SwipeTasksAndInboxPane(
    tasks: List<TaskItem>,
    autoBlocks: List<ScheduledBlock>,
    onToggleDone: (String) -> Unit,
    onSwipeTomorrow: (String) -> Unit,
    onMoveToInbox: (String) -> Unit,
    onScheduleToday: (String) -> Unit,
    onDeleteTask: (String) -> Unit
) {
    val scheduled = tasks.filter { !it.completed && !it.inInbox }
    val inbox = tasks.filter { !it.completed && it.inInbox }
    val done = tasks.filter { it.completed }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                text = "ปัดขวา = ติ๊กเสร็จ  •  ปัดซ้าย = เลื่อนพรุ่งนี้  •  ปัดซ้ายสุด = เก็บเข้ากล่องพักงาน",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface1)
                    .padding(10.dp)
            )
        }

        item { Text("รายการงานตามแผน (${scheduled.size})", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        items(scheduled, key = { it.id }) { tk ->
            val slot = autoBlocks.firstOrNull { it.sourceId == tk.id }
            SwipeableTaskCard(tk, slot, onToggleDone, onSwipeTomorrow, onMoveToInbox, onScheduleToday, onDeleteTask)
        }

        item { Text("กล่องพักงาน — Unscheduled Inbox (${inbox.size})", color = Color(0xFFFCD34D), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        items(inbox, key = { it.id }) { tk ->
            SwipeableTaskCard(tk, null, onToggleDone, onSwipeTomorrow, onMoveToInbox, onScheduleToday, onDeleteTask)
        }

        if (done.isNotEmpty()) {
            item { Text("เสร็จแล้ว (${done.size})", color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            items(done, key = { it.id }) { tk ->
                SwipeableTaskCard(tk, null, onToggleDone, onSwipeTomorrow, onMoveToInbox, onScheduleToday, onDeleteTask)
            }
        }
    }
}

@Composable
private fun SwipeableTaskCard(
    task: TaskItem,
    slot: ScheduledBlock?,
    onToggleDone: (String) -> Unit,
    onSwipeTomorrow: (String) -> Unit,
    onMoveToInbox: (String) -> Unit,
    onScheduleToday: (String) -> Unit,
    onDeleteTask: (String) -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    offsetX > 60f -> Color(0xFF14532D)
                    offsetX < -150f -> Color(0xFF78350F)
                    offsetX < -60f -> Color(0xFF1E3A8A)
                    else -> Surface1
                }
            )
    ) {
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(Surface2)
                .pointerInput(task.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-220f, 180f)
                        },
                        onDragEnd = {
                            when {
                                offsetX > 90f -> onToggleDone(task.id)
                                offsetX < -160f -> onMoveToInbox(task.id)
                                offsetX < -80f -> onSwipeTomorrow(task.id)
                            }
                            offsetX = 0f
                        }
                    )
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Checkbox(checked = task.completed, onCheckedChange = { onToggleDone(task.id) })
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    color = if (task.completed) TextMuted else TextPrimary,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                val sub = when {
                    task.inInbox -> "พักไว้ใน Inbox • ${task.durationMinutes} นาที"
                    slot != null -> "Auto Time-block: ${slot.date} ${SmartScheduleEngine.formatHm(slot.startMin)}–${SmartScheduleEngine.formatHm(slot.endMin)}"
                    else -> "${task.durationMinutes} นาที"
                }
                Text(sub, color = TextSecondary, fontSize = 11.sp)
            }
            if (task.inInbox && !task.completed) {
                ThumbChip("ลงวันนี้") { onScheduleToday(task.id) }
            }
            IconButton(onClick = { onDeleteTask(task.id) }) {
                Icon(Icons.Default.Close, contentDescription = "ลบ", tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateItemBottomSheet(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onAddEvent: (CalendarEvent) -> Unit,
    onAddTask: (TaskItem) -> Unit
) {
    var isTaskTab by remember { mutableStateOf(false) }
    var nlpText by remember { mutableStateOf("") }
    var taskTitle by remember { mutableStateOf("") }
    var taskDuration by remember { mutableIntStateOf(60) }
    var putInInbox by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThumbChip(if (!isTaskTab) "● นัดหมาย (NLP)" else "นัดหมาย (NLP)") { isTaskTab = false }
                ThumbChip(if (isTaskTab) "● งาน (Auto Time-block)" else "งาน (Auto Time-block)") { isTaskTab = true }
            }

            if (!isTaskTab) {
                val parsed = remember(nlpText) { SmartScheduleEngine.parseNaturalLanguage(nlpText, initialDate) }
                Text("พิมพ์สร้างนัดด้วยภาษาคน", color = TextPrimary, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = nlpText,
                    onValueChange = { nlpText = it },
                    placeholder = { Text("เช่น ประชุมทีมพรุ่งนี้ 10:00-11:30 ที่ห้อง A") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (parsed != null) {
                    Text(
                        text = "แยกอัตโนมัติ: ${parsed.title} • ${parsed.date} • " +
                            (if (parsed.allDay) "ตลอดวัน" else "${SmartScheduleEngine.formatHm(parsed.startMin)}–${SmartScheduleEngine.formatHm(parsed.endMin)}") +
                            (if (parsed.location.isNotEmpty()) " @ ${parsed.location}" else ""),
                        color = BrandBlue,
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = { parsed?.let(onAddEvent) },
                    enabled = parsed != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("สร้างนัดหมายทันที")
                }
            } else {
                Text("เพิ่มงานพร้อมจัดเวลาลงตารางอัตโนมัติ", color = TextPrimary, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = taskTitle,
                    onValueChange = { taskTitle = it },
                    placeholder = { Text("ระบุชื่องานที่ต้องทำ") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 90, 120).forEach { m ->
                        ThumbChip(if (taskDuration == m) "● ${m}น." else "${m}น.") { taskDuration = m }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = putInInbox, onCheckedChange = { putInInbox = it })
                    Text("เก็บไว้ในกล่องพักงาน (Unscheduled Inbox) ก่อน", color = TextSecondary, fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        if (taskTitle.isNotBlank()) {
                            onAddTask(
                                TaskItem(
                                    title = taskTitle.trim(),
                                    dueDate = if (putInInbox) null else initialDate,
                                    durationMinutes = taskDuration,
                                    autoBlock = !putInInbox,
                                    inInbox = putInInbox
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("บันทึกงาน")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsAndSyncBottomSheet(
    events: List<CalendarEvent>,
    autoBlocks: List<ScheduledBlock>,
    activeReminderCtx: ReminderContext,
    skipHolidays: Boolean,
    collapseNightHours: Boolean,
    oauthToken: String,
    onChangeContext: (ReminderContext) -> Unit,
    onToggleHolidays: (Boolean) -> Unit,
    onToggleNight: (Boolean) -> Unit,
    onBookFreeSlot: (LocalDate, IntRange, String, String) -> Unit,
    onSyncGoogle: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var tokenText by remember { mutableStateOf(oauthToken) }
    val freeSlots = remember(events, autoBlocks) { SmartScheduleEngine.findMutualFreeSlots(events, autoBlocks, 60) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("แจ้งเตือนแบบมีเงื่อนไข & ไทม์ไลน์", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReminderContext.entries.forEach { ctx ->
                    ThumbChip(if (activeReminderCtx == ctx) "● ${ctx.label}" else ctx.label) { onChangeContext(ctx) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = skipHolidays, onCheckedChange = onToggleHolidays)
                Text("งดเตือนงานประจำในวันหยุดนักขัตฤกษ์ไทย", color = TextSecondary, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = collapseNightHours, onCheckedChange = onToggleNight)
                Text("ย่อช่วงเวลา 01:00–06:00 เป็นเส้นบาง", color = TextSecondary, fontSize = 13.sp)
            }

            HorizontalDivider(color = Surface3)
            Text("ลิงก์นัดเวลาว่างในตัว (Built-in Scheduling Link)", color = TextPrimary, fontWeight = FontWeight.Bold)
            Button(onClick = { onCopyLink("https://smartschedule.app/book?dur=60") }) {
                Text("คัดลอกลิงก์ส่งให้ผู้ร่วมงานเลือกเวลาว่าง")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                freeSlots.take(3).forEach { (date, rng) ->
                    ThumbChip("${date.dayOfMonth}/${date.monthValue} ${SmartScheduleEngine.formatHm(rng.first)}") {
                        onBookFreeSlot(date, rng, "ผู้ร่วมงาน", "ประชุมนัดหมาย")
                    }
                }
            }

            HorizontalDivider(color = Surface3)
            Text("ซิงก์ Google Calendar & Google Tasks API (OAuth 2.0)", color = TextPrimary, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = tokenText,
                onValueChange = { tokenText = it },
                placeholder = { Text("วาง OAuth 2.0 Access Token เพื่อดึงข้อมูลจริง") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { if (tokenText.isNotBlank()) onSyncGoogle(tokenText.trim()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ซิงก์ข้อมูล 2 ทางกับ Google ทันที")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

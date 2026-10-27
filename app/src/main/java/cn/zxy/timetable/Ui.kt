package cn.zxy.timetable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val schoolZone = ZoneId.of("Asia/Shanghai")
private val ink = Color(0xFF202631)
private val muted = Color(0xFF798292)
private val line = Color(0xFFE5EAF1)
private val courseColors = listOf(0xFFE5EDFC, 0xFFE1F0ED, 0xFFF1E8FA, 0xFFFCEDDD, 0xFFE3F0F8, 0xFFF9E7EB, 0xFFECEBD9, 0xFFE6E8F1).map { Color(it) }
private val courseInks = listOf(0xFF385D9A, 0xFF356B60, 0xFF77549A, 0xFF96652D, 0xFF3E6F8D, 0xFF99566A, 0xFF717239, 0xFF586180).map { Color(it) }
private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun TimetableApp(vm: TimetableViewModel = viewModel()) {
    val schedule by vm.schedule.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedWeek by rememberSaveable { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(LocalDateTime.now(schoolZone)) }
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var weekPicker by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Lesson?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editingLesson by remember { mutableStateOf<Lesson?>(null) }
    var deletingLesson by remember { mutableStateOf<Lesson?>(null) }
    var pendingImport by remember { mutableStateOf<Schedule?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = LocalDateTime.now(schoolZone) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(schoolZone); delay(15_000) } }
    val currentWeek = schedule?.weekOn(now.toLocalDate()) ?: 1
    val shownWeek = if (selectedWeek == 0) currentWeek.coerceIn(1, schedule?.totalWeeks ?: 19) else selectedWeek.coerceIn(1, schedule?.totalWeeks ?: 19)

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importing = true
            runCatching {
                val base = schedule ?: error("课表尚未加载，请稍后重试")
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            require(output.size() + count <= 5_000_000) { "文件过大，请选择教务导出的排课信息.xlsx" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("无法打开所选文件")
                }
                vm.parseExcel(bytes, base)
            }.onSuccess { pendingImport = it }.onFailure {
                importError = when (it) {
                    is java.util.zip.ZipException, is org.xml.sax.SAXException -> "Excel文件无法读取，请重新从教务网站导出 .xlsx 文件"
                    else -> it.message ?: "文件内容无法识别"
                }
            }
            importing = false
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SchoolXlsx.MIME)) { uri ->
        val value = schedule
        if (uri != null && value != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(SchoolXlsx.encode(value)) }
                        ?: error("无法写入所选位置")
                }
            }.onSuccess { snackbar.showSnackbar("课表已导出") }.onFailure { importError = "导出失败：${it.message}" }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (tabIndex == 0) "今日日程" else "课程表", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text(if (tabIndex == 0) "${now.monthValue}月${now.dayOfMonth}日 · 星期${weekdays[now.dayOfWeek.value - 1]}" else schedule?.title ?: "简课", color = muted, fontSize = 13.sp)
                    }
                    IconButton(onClick = { editingLesson = null; editorOpen = true }, enabled = schedule != null && !busy,
                        modifier = Modifier.testTag("add_course")) { AppIcon("add", "新增课程") }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("more")) { AppIcon("more", "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("学期设置") }, onClick = { menu = false; settings = true }, enabled = schedule != null && !busy)
                            DropdownMenuItem(text = { Text("导入Excel课表") }, onClick = { menu = false; importLauncher.launch(arrayOf(SchoolXlsx.MIME, "application/octet-stream")) }, enabled = schedule != null && !busy && !importing)
                            DropdownMenuItem(text = { Text("导出Excel课表") }, onClick = { menu = false; exportLauncher.launch("简课-排课信息.xlsx") }, enabled = schedule != null && !busy)
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                HorizontalDivider(color = line)
                Row(Modifier.fillMaxWidth().height(72.dp)) {
                    listOf("今日", "课表").forEachIndexed { i, name ->
                        Column(Modifier.weight(1f).fillMaxHeight().clickable { tabIndex = i }.testTag("tab_$i"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(62.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(if (tabIndex == i) Color(0xFFE7EDF9) else Color.Transparent), contentAlignment = Alignment.Center) {
                                AppIcon(if (i == 0) "today" else "calendar", name, if (tabIndex == i) MaterialTheme.colorScheme.primary else muted)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(name, fontSize = 12.sp, fontWeight = if (tabIndex == i) FontWeight.SemiBold else FontWeight.Normal, color = if (tabIndex == i) MaterialTheme.colorScheme.primary else muted)
                        }
                    }
                }
            }
        }
    ) { padding ->
        val value = schedule
        if (value == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (tabIndex == 0) TodayScreen(value, now, Modifier.padding(padding), onDetail = { detail = it })
        else WeekScreen(value, shownWeek, now.toLocalDate(), Modifier.padding(padding),
            onWeekChanged = { selectedWeek = it },
            onPick = { weekPicker = true }, onCurrent = { selectedWeek = 0 }, onDetail = { detail = it })
    }
    if (settings && schedule != null) SettingsDialog(schedule!!, busy, onDismiss = { settings = false }) { updated ->
        vm.save(updated) { settings = false; selectedWeek = 0; scope.launch { snackbar.showSnackbar("学期设置已保存") } }
    }
    if (weekPicker && schedule != null) AlertDialog(
        onDismissRequest = { weekPicker = false }, title = { Text("选择教学周") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            for (week in 1..schedule!!.totalWeeks) TextButton(onClick = { selectedWeek = week; weekPicker = false }, modifier = Modifier.fillMaxWidth().testTag("week_$week")) {
                Text("第${week}周${if (week == currentWeek) " · 本周" else ""}", Modifier.weight(1f), textAlign = TextAlign.Start)
                Text(schedule!!.mondayOf(week).format(DateTimeFormatter.ofPattern("M.d")), color = muted)
            }
        } }, confirmButton = { TextButton(onClick = { weekPicker = false }) { Text("关闭") } }
    )
    detail?.let { lesson -> schedule?.let { s ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(lesson.title, fontWeight = FontWeight.Bold) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("时间", "星期${weekdays[lesson.day - 1]} · 第${lesson.start}–${lesson.end}节\n${s.startOf(lesson)}–${s.endOf(lesson)}")
                DetailRow("教室", lesson.roomDetail)
                if (lesson.teacher.isNotBlank()) DetailRow("教师", lesson.teacher)
                DetailRow("周次", lesson.weeks.weekDescription())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { editingLesson = lesson; editorOpen = true; detail = null }, modifier = Modifier.testTag("edit_course")) { Text("编辑") }
                    TextButton(enabled = !busy, onClick = { deletingLesson = lesson; detail = null }, modifier = Modifier.testTag("delete_course")) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("知道了") } })
    } }
    if (editorOpen && schedule != null) LessonEditorDialog(schedule!!, editingLesson, busy, onDismiss = { editorOpen = false }) { updated ->
        vm.save(updated) { editorOpen = false; scope.launch { snackbar.showSnackbar("课程已保存") } }
    }
    deletingLesson?.let { lesson ->
        AlertDialog(onDismissRequest = { if (!busy) deletingLesson = null }, title = { Text("删除这条课程安排？") },
            text = { Text("${lesson.title}\n星期${weekdays[lesson.day - 1]} · 第${lesson.start}–${lesson.end}节\n${lesson.weeks.weekDescription()}\n\n该课程的其他上课安排将保留。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { schedule?.let { current -> vm.save(current.withoutLesson(lesson.id)) {
                deletingLesson = null; scope.launch { snackbar.showSnackbar("课程已删除") }
            } } }, modifier = Modifier.testTag("confirm_delete")) { Text(if (busy) "删除中…" else "删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deletingLesson = null }) { Text("取消") } })
    }
    pendingImport?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!busy) pendingImport = null }, title = { Text("导入这份课表？") },
            text = { Text("${candidate.title}\n${candidate.lessons.map { it.courseId }.distinct().size}门课程 · ${candidate.lessons.size}条安排\n第1周：${candidate.firstMonday}\n\n保留当前学期日期和作息时间，替换课程安排（包括手动修改）。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { vm.save(candidate) { pendingImport = null; selectedWeek = 0; scope.launch { snackbar.showSnackbar("课表已导入") } } }, modifier = Modifier.testTag("confirm_import")) { Text(if (busy) "导入中…" else "导入") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingImport = null }) { Text("取消") } })
    }
    (importError ?: error)?.let { message -> AlertDialog(onDismissRequest = { importError = null; vm.clearError() },
        title = { Text("暂时无法完成") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { importError = null; vm.clearError() }) { Text("知道了") } }) }
    if (importing) AlertDialog(onDismissRequest = {}, title = { Text("正在读取课表") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
}

@Composable
private fun TodayScreen(s: Schedule, now: LocalDateTime, modifier: Modifier, onDetail: (Lesson) -> Unit) {
    val week = s.weekOn(now.toLocalDate())
    val lessons = s.lessonsOn(now.toLocalDate())
    val next = s.nextLesson(now.toLocalDate(), now.toLocalTime())
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (week < 1) "学期尚未开始" else if (week > s.totalWeeks) "本学期已结束" else "第${week}教学周", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFEAF0FA)).padding(horizontal = 10.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f)); Text("${lessons.size}门课程", color = muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
        if (next != null) {
            val status = s.status(next, now.toLocalTime())
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF405E9B)).clickable { onDetail(next) }.padding(22.dp).testTag("next_lesson")) {
                Text(when (status) { "进行中" -> "正在上课"; "课间休息" -> "课间休息"; else -> "下一节课" }, color = Color(0xFFD1DEF6), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text(next.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Text("${s.startOf(next)}–${s.endOf(next)}   ·   ${next.room}", color = Color.White, fontSize = 14.sp)
            }
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFECF0F7)).padding(24.dp)) {
                AppIcon("sun", "休息", MaterialTheme.colorScheme.primary, Modifier.size(30.dp))
                Spacer(Modifier.height(18.dp))
                Text(if (lessons.isEmpty()) "今天没有课程" else "今天的课程已结束", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(if (week !in 1..s.totalWeeks) "可以在课表中查看学期安排" else "留一点时间给自己", color = muted, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(30.dp))
        Text("当天安排", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        if (lessons.isEmpty()) {
            Text("没有需要赶的下一堂课。", color = muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        lessons.forEach { lesson ->
            val status = s.status(lesson, now.toLocalTime())
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).clickable { onDetail(lesson) }.padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(60.dp)) {
                    Text(s.startOf(lesson).format(timeFormat), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (status == "已结束") muted else ink)
                    Spacer(Modifier.height(4.dp)); Text(s.endOf(lesson).format(timeFormat), color = muted, fontSize = 12.sp)
                }
                Box(Modifier.padding(horizontal = 10.dp).width(3.dp).height(46.dp).clip(RoundedCornerShape(2.dp)).background(courseColors[lesson.color]))
                Column(Modifier.weight(1f)) {
                    Text(lesson.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (status == "已结束") muted else ink)
                    Spacer(Modifier.height(6.dp)); Text("${lesson.room} · 第${lesson.start}–${lesson.end}节", color = muted, fontSize = 12.sp)
                    if (status != "未开始") { Spacer(Modifier.height(6.dp)); Text(status, color = if (status == "已结束") muted else MaterialTheme.colorScheme.primary, fontSize = 11.sp) }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun WeekScreen(s: Schedule, week: Int, today: LocalDate, modifier: Modifier, onWeekChanged: (Int) -> Unit, onPick: () -> Unit, onCurrent: () -> Unit, onDetail: (Lesson) -> Unit) {
    val pager = rememberPagerState(initialPage = week - 1, pageCount = { s.totalWeeks })
    LaunchedEffect(week) { if (pager.currentPage != week - 1) pager.scrollToPage(week - 1) }
    LaunchedEffect(pager.settledPage) { if (pager.settledPage + 1 != week) onWeekChanged(pager.settledPage + 1) }
    val displayedWeek = pager.currentPage + 1
    val monday = s.mondayOf(displayedWeek)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(64.dp))
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPick).testTag("week_picker"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第${displayedWeek}周 ▾", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("${monday.format(DateTimeFormatter.ofPattern("M.d"))} – ${monday.plusDays(6).format(DateTimeFormatter.ofPattern("M.d"))}", color = muted, fontSize = 11.sp)
            }
            TextButton(onClick = onCurrent, modifier = Modifier.testTag("current_week")) { Text("本周", fontSize = 13.sp) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth().testTag("week_pager"), beyondViewportPageCount = 1) { page ->
            WeekGrid(s, page + 1, today, onDetail)
        }
    }
}

@Composable
private fun WeekGrid(s: Schedule, week: Int, today: LocalDate, onDetail: (Lesson) -> Unit) {
    val monday = s.mondayOf(week)
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp).testTag("week_grid_$week")) {
        val gridWidth = maxWidth
        val gutter = 32.dp
        val dayWidth = (gridWidth - gutter) / 7
        val rowHeight = 76.dp
        Column(Modifier.width(gridWidth)) {
            Row(Modifier.height(54.dp)) {
                Box(Modifier.width(gutter).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("${monday.monthValue}月", color = muted, fontSize = 10.sp) }
                for (day in 1..7) {
                    val date = monday.plusDays((day - 1).toLong())
                    Column(Modifier.width(dayWidth).fillMaxHeight().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(if (date == today) Color(0xFFE7EDF9) else Color.Transparent), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(weekdays[day - 1], color = if (date == today) MaterialTheme.colorScheme.primary else muted, fontSize = 12.sp)
                        Spacer(Modifier.height(3.dp)); Text("${date.dayOfMonth}", fontSize = 14.sp, fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).height(rowHeight * s.periods.size)) {
                Column {
                    s.periods.forEach { period ->
                        Row(Modifier.height(rowHeight)) {
                            Column(Modifier.width(gutter).fillMaxHeight().padding(top = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${period.number}", color = muted, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp)); Text(period.start.toString(), color = muted, fontSize = 8.sp)
                                Text(period.end.toString(), color = muted, fontSize = 8.sp)
                            }
                            for (day in 1..7) Box(Modifier.width(dayWidth).fillMaxHeight().border(0.4.dp, line))
                        }
                    }
                }
                s.lessons.filter { week in it.weeks }.forEach { lesson ->
                    Column(Modifier.offset(x = gutter + dayWidth * (lesson.day - 1), y = rowHeight * (lesson.start - 1)).width(dayWidth).height(rowHeight * (lesson.end - lesson.start + 1)).padding(2.dp)
                        .clip(RoundedCornerShape(8.dp)).background(courseColors[lesson.color]).clickable { onDetail(lesson) }.padding(horizontal = 3.dp, vertical = 8.dp)
                        .semantics { contentDescription = "${lesson.title}，星期${weekdays[lesson.day - 1]}，第${lesson.start}至${lesson.end}节，${lesson.room}" }) {
                        Text(lesson.title, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, color = courseInks[lesson.color], overflow = TextOverflow.Ellipsis, maxLines = minOf(7, (lesson.end - lesson.start + 1) * 4 - 2))
                        Spacer(Modifier.height(4.dp)); Text(lesson.room, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp, maxLines = 2, color = courseInks[lesson.color])
                    }
                }
                if (s.lessons.none { week in it.weeks }) Text("本周没有课程", Modifier.align(Alignment.TopCenter).padding(top = 100.dp), color = muted)
            }
        }
    }
}

@Composable
private fun SettingsDialog(schedule: Schedule, busy: Boolean, onDismiss: () -> Unit, onSave: (Schedule) -> Unit) {
    var title by remember { mutableStateOf(schedule.title) }
    var monday by remember { mutableStateOf(schedule.firstMonday.toString()) }
    var weeks by remember { mutableStateOf(schedule.totalWeeks.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("学期设置") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it; error = null }, label = { Text("学期名称") }, singleLine = true, modifier = Modifier.testTag("term_title"))
                OutlinedTextField(monday, { monday = it; error = null }, label = { Text("第1周周一") }, supportingText = { Text("格式：2026-09-07") }, singleLine = true, modifier = Modifier.testTag("first_monday"))
                OutlinedTextField(weeks, { weeks = it; error = null }, label = { Text("学期总周数") }, singleLine = true, modifier = Modifier.testTag("total_weeks"))
            }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            runCatching {
                schedule.copy(title = title.trim(), firstMonday = LocalDate.parse(monday.trim()), totalWeeks = weeks.trim().toInt()).also { ScheduleJson.validate(it) }
            }.onSuccess(onSave).onFailure { error = if (it is java.time.format.DateTimeParseException || it is NumberFormatException) "请检查日期和总周数格式" else it.message }
        }, modifier = Modifier.testTag("save_settings")) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row { Text(label, Modifier.width(48.dp), color = muted, fontSize = 14.sp); Text(value, fontSize = 14.sp) }
}

@Composable
private fun AppIcon(kind: String, description: String, color: Color = ink, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier.semantics { contentDescription = description }) {
        val w = size.width; val h = size.height; val stroke = Stroke(width = w * 0.075f)
        when (kind) {
            "add" -> {
                drawLine(color, Offset(w * .2f, h * .5f), Offset(w * .8f, h * .5f), strokeWidth = w * .075f)
                drawLine(color, Offset(w * .5f, h * .2f), Offset(w * .5f, h * .8f), strokeWidth = w * .075f)
            }
            "more" -> for (x in listOf(.22f, .5f, .78f)) drawCircle(color, w * .065f, Offset(w * x, h * .5f))
            "sun" -> {
                drawCircle(color, w * .21f, Offset(w / 2, h / 2), style = stroke)
                for (i in 0..7) { val a = i * Math.PI / 4; drawLine(color, Offset(w * (.5f + .33f * kotlin.math.cos(a).toFloat()), h * (.5f + .33f * kotlin.math.sin(a).toFloat())), Offset(w * (.5f + .45f * kotlin.math.cos(a).toFloat()), h * (.5f + .45f * kotlin.math.sin(a).toFloat())), strokeWidth = w * .07f) }
            }
            else -> {
                drawRoundRect(color, Offset(w * .13f, h * .2f), androidx.compose.ui.geometry.Size(w * .74f, h * .67f), androidx.compose.ui.geometry.CornerRadius(w * .1f), style = stroke)
                drawLine(color, Offset(w * .13f, h * .4f), Offset(w * .87f, h * .4f), strokeWidth = w * .075f)
                for (x in listOf(.32f, .68f)) drawLine(color, Offset(w * x, h * .1f), Offset(w * x, h * .29f), strokeWidth = w * .075f)
                if (kind == "today") drawCircle(color, w * .09f, Offset(w * .5f, h * .62f))
                else for (x in listOf(.34f, .65f)) for (y in listOf(.57f, .73f)) drawCircle(color, w * .045f, Offset(w * x, h * y))
            }
        }
    }
}

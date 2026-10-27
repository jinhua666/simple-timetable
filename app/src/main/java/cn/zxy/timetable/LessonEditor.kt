package cn.zxy.timetable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@Composable
fun LessonEditorDialog(schedule: Schedule, existing: Lesson?, busy: Boolean, onDismiss: () -> Unit, onSave: (Schedule) -> Unit) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var teacher by remember { mutableStateOf(existing?.teacher.orEmpty()) }
    var room by remember { mutableStateOf(existing?.room.orEmpty()) }
    var day by remember { mutableIntStateOf(existing?.day ?: 1) }
    var start by remember { mutableStateOf(existing?.start?.toString() ?: "1") }
    var end by remember { mutableStateOf(existing?.end?.toString() ?: "2") }
    var weeks by remember { mutableStateOf(existing?.weeks?.weekDescription() ?: "1-${schedule.totalWeeks}") }
    var dayMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val days = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (existing == null) "新增课程" else "编辑课程") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (existing != null && schedule.lessons.count { it.courseId == existing.courseId } > 1) {
                    Text("编辑当前上课安排，其他安排保留。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                OutlinedTextField(title, { title = it; error = null }, label = { Text("课程名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("lesson_title"))
                OutlinedTextField(room, { room = it; error = null }, label = { Text("教室") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("lesson_room"))
                OutlinedTextField(teacher, { teacher = it; error = null }, label = { Text("教师（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("lesson_teacher"))
                Box {
                    OutlinedButton(onClick = { dayMenu = true }, modifier = Modifier.fillMaxWidth().testTag("lesson_day")) { Text("${days[day - 1]} ▾") }
                    DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                        days.forEachIndexed { index, name -> DropdownMenuItem(text = { Text(name) }, onClick = { day = index + 1; dayMenu = false; error = null }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(start, { start = it; error = null }, label = { Text("开始节次") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("lesson_start"))
                    OutlinedTextField(end, { end = it; error = null }, label = { Text("结束节次") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("lesson_end"))
                }
                OutlinedTextField(weeks, { weeks = it; error = null }, label = { Text("上课周次") }, supportingText = { Text("例如1-16或2,4,6,8；支持单双周") },
                    modifier = Modifier.fillMaxWidth().testTag("lesson_weeks"))
            }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            runCatching {
                val first = start.trim().toIntOrNull() ?: error("请填写正确的开始节次")
                val last = end.trim().toIntOrNull() ?: error("请填写正确的结束节次")
                require(first in 1..schedule.periods.size && last in first..schedule.periods.size) { "节次应在1至${schedule.periods.size}之间，结束不能早于开始" }
                val id = existing?.id ?: "manual-${UUID.randomUUID()}"
                val lesson = Lesson(id, existing?.courseId ?: id, title.trim(), teacher.trim(), room.trim(),
                    if (existing != null && room.trim() == existing.room) existing.roomDetail else room.trim(),
                    day, first, last, parseWeeks(weeks, schedule.totalWeeks), existing?.color ?: (schedule.lessons.map { it.courseId }.distinct().size % 8))
                schedule.withLesson(lesson)
            }.onSuccess(onSave).onFailure { error = it.message ?: "请检查课程信息" }
        }, modifier = Modifier.testTag("save_lesson")) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

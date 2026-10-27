package cn.zxy.timetable

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class Period(val number: Int, val start: LocalTime, val end: LocalTime)
data class Lesson(
    val id: String,
    val courseId: String,
    val title: String,
    val teacher: String,
    val room: String,
    val roomDetail: String,
    val day: Int,
    val start: Int,
    val end: Int,
    val weeks: Set<Int>,
    val color: Int,
)
data class Schedule(
    val title: String,
    val firstMonday: LocalDate,
    val totalWeeks: Int,
    val periods: List<Period>,
    val lessons: List<Lesson>,
) {
    fun weekOn(date: LocalDate): Int = Math.floorDiv(ChronoUnit.DAYS.between(firstMonday, date), 7).toInt() + 1
    fun mondayOf(week: Int): LocalDate = firstMonday.plusWeeks((week - 1).toLong())
    fun lessonsOn(date: LocalDate): List<Lesson> = lessonsAt(weekOn(date), date.dayOfWeek.value)
    fun lessonsAt(week: Int, day: Int): List<Lesson> = lessons.filter { it.day == day && week in it.weeks }.sortedBy { it.start }
    fun period(number: Int): Period = periods.first { it.number == number }
    fun startOf(lesson: Lesson): LocalTime = period(lesson.start).start
    fun endOf(lesson: Lesson): LocalTime = period(lesson.end).end

    fun status(lesson: Lesson, time: LocalTime): String = when {
        time < startOf(lesson) -> "未开始"
        time >= endOf(lesson) -> "已结束"
        (lesson.start..lesson.end).any { time >= period(it).start && time < period(it).end } -> "进行中"
        else -> "课间休息"
    }

    fun nextLesson(date: LocalDate, time: LocalTime): Lesson? = lessonsOn(date).firstOrNull { endOf(it) > time }

    fun withLesson(lesson: Lesson): Schedule {
        val index = lessons.indexOfFirst { it.id == lesson.id }
        val updated = lessons.toMutableList()
        if (index == -1) updated += lesson else updated[index] = lesson
        return copy(lessons = updated).also { ScheduleJson.validate(it) }
    }

    fun withoutLesson(id: String): Schedule = copy(lessons = lessons.filterNot { it.id == id })
}

fun parseWeeks(text: String, totalWeeks: Int): Set<Int> {
    var value = text.trim().replace("第", "").replace("周", "")
        .replace('，', ',').replace('、', ',').replace('；', ',').replace(';', ',')
        .replace('–', '-').replace('—', '-').replace('~', '-').replace('～', '-')
        .replace('至', '-').replace('（', '(').replace('）', ')').replace(Regex("\\s+"), "")
    val odd = value.contains("单")
    val even = value.contains("双")
    require(!(odd && even)) { "周次不能同时标记单周和双周" }
    value = value.replace(Regex("[单双()]"), "")
    require(value.isNotEmpty()) { "请填写上课周次，例如1-16或2,4,6,8" }
    val weeks = linkedSetOf<Int>()
    for (part in value.split(',')) {
        require(Regex("\\d+(?:-\\d+)?").matches(part)) { "周次格式不正确，请使用1-16或2,4,6,8" }
        val ends = part.split('-').map { it.toIntOrNull() ?: 0 }
        val start = ends.first()
        val end = ends.last()
        require(start in 1..totalWeeks && end in start..totalWeeks) { "上课周次应在1至${totalWeeks}周之间" }
        for (n in start..end) if ((!odd || n % 2 == 1) && (!even || n % 2 == 0)) weeks += n
    }
    require(weeks.isNotEmpty()) { "所选范围没有符合条件的上课周次" }
    return weeks
}

object ScheduleJson {
    fun parse(text: String): Schedule {
        require(text.length <= 1_000_000) { "文件过大，请选择课表 JSON 文件" }
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "不支持的课表格式版本" }
        val periods = root.getJSONArray("periods").objects().map {
            Period(it.getInt("number"), LocalTime.parse(it.getString("start")), LocalTime.parse(it.getString("end")))
        }
        val lessons = root.getJSONArray("lessons").objects().map {
            val weeks = it.getJSONArray("weeks")
            Lesson(it.getString("id"), it.getString("courseId"), it.getString("title"),
                it.optString("teacher"), it.getString("room"), it.optString("roomDetail", it.getString("room")),
                it.getInt("day"), it.getInt("start"), it.getInt("end"),
                (0 until weeks.length()).map { n -> weeks.getInt(n) }.toSet(), it.getInt("color"))
        }
        return Schedule(root.getString("title"), LocalDate.parse(root.getString("firstMonday")),
            root.getInt("totalWeeks"), periods, lessons).also { validate(it) }
    }

    fun validate(s: Schedule) {
        require(s.title.isNotBlank() && s.title.length <= 80) { "学期名称不能为空且不能超过80字" }
        require(s.firstMonday.dayOfWeek == DayOfWeek.MONDAY) { "第1周的起始日期必须是周一" }
        require(s.totalWeeks in 1..53) { "总周数应在1至53之间" }
        require(s.periods.isNotEmpty() && s.periods.size <= 20) { "课表必须包含1至20个节次" }
        require(s.periods.map { it.number } == (1..s.periods.size).toList()) { "节次必须从1开始连续排列" }
        require(s.periods.all { it.start < it.end }) { "节次结束时间应晚于开始时间" }
        require(s.periods.zipWithNext().all { (a, b) -> a.end <= b.start }) { "节次时间不能重叠" }
        require(s.lessons.size <= 500) { "上课安排不能超过500条" }
        require(s.lessons.map { it.id }.distinct().size == s.lessons.size) { "上课安排编号重复" }
        s.lessons.forEach { l ->
            require(l.id.isNotBlank() && l.courseId.isNotBlank()) { "课程编号不能为空" }
            require(l.title.isNotBlank() && l.title.length <= 100) { "课程名称无效" }
            require(l.room.isNotBlank() && l.room.length <= 100) { "教室信息无效" }
            require(l.teacher.length <= 100 && l.roomDetail.length <= 200) { "课程详情过长" }
            require(l.day in 1..7 && l.start in 1..s.periods.size && l.end in l.start..s.periods.size) { "星期或节次无效" }
            require(l.weeks.isNotEmpty() && l.weeks.all { it in 1..s.totalWeeks }) { "课程周次超出学期范围" }
            require(l.color in 0..7) { "课程颜色应在0至7之间" }
        }
        for (week in 1..s.totalWeeks) for (day in 1..7) {
            require(s.lessonsAt(week, day).zipWithNext().all { (a, b) -> a.end < b.start }) {
                "第${week}周星期${day}存在重叠课程，请核对后导入"
            }
        }
    }

    fun encode(s: Schedule): String = JSONObject().apply {
        put("version", 1); put("title", s.title); put("firstMonday", s.firstMonday.toString()); put("totalWeeks", s.totalWeeks)
        put("periods", JSONArray(s.periods.map { JSONObject().put("number", it.number).put("start", it.start.toString()).put("end", it.end.toString()) }))
        put("lessons", JSONArray(s.lessons.map {
            JSONObject().put("id", it.id).put("courseId", it.courseId).put("title", it.title)
                .put("teacher", it.teacher).put("room", it.room).put("roomDetail", it.roomDetail)
                .put("day", it.day).put("start", it.start).put("end", it.end)
                .put("weeks", JSONArray(it.weeks.sorted())).put("color", it.color)
        }))
    }.toString(2)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}

fun Set<Int>.weekDescription(): String {
    val sorted = sorted()
    if (sorted.isEmpty()) return ""
    val result = mutableListOf<String>()
    var start = sorted.first()
    var end = start
    for (n in sorted.drop(1)) {
        if (n == end + 1) end = n else {
            result += if (start == end) "$start" else "$start–$end"
            start = n; end = n
        }
    }
    result += if (start == end) "$start" else "$start–$end"
    return "第${result.joinToString("、")}周"
}

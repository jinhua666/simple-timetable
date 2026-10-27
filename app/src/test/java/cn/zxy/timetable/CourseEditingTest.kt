package cn.zxy.timetable

import org.junit.Assert.*
import org.junit.Test

class CourseEditingTest {
    private fun sample() = ScheduleJson.parse(javaClass.getResource("/schedule.json")!!.readText())

    @Test fun addModifyAndDeletePreserveOtherLessons() {
        val source = sample()
        val extra = source.lessons.first().copy(id = "manual", courseId = "manual", title = "自习", day = 1, start = 1, end = 2, weeks = setOf(8))
        val added = source.withLesson(extra)
        assertEquals(10, added.lessons.size)
        val edited = added.withLesson(extra.copy(title = "实验", room = "X-101"))
        assertEquals(10, edited.lessons.size)
        assertEquals("实验", edited.lessons.single { it.id == extra.id }.title)
        assertEquals(source, edited.withoutLesson(extra.id))
        assertEquals(9, source.lessons.size)
    }

    @Test fun modifyingDoesNotConflictWithItselfButRejectsOtherOverlaps() {
        val source = sample()
        val lesson = source.lessons.first()
        assertEquals("新名称", source.withLesson(lesson.copy(title = "新名称")).lessons.first().title)
        assertThrows(IllegalArgumentException::class.java) { source.withLesson(lesson.copy(id = "other")) }
        assertEquals(9, source.lessons.size)
    }

    @Test fun deletingSpecialArrangementPreservesRegularOne() {
        val result = sample().withoutLesson("demo-8-special")
        assertEquals(8, result.lessons.size)
        assertTrue(result.lessons.any { it.id == "demo-8-regular" })
        assertEquals(0, result.lessons.count { it.courseId == "DEMO008" && 6 in it.weeks })
    }

    @Test fun parsesMixedAndOddEvenWeeks() {
        assertEquals(setOf(5, 7, 8, 9, 10, 11, 12), parseWeeks("第5、7–12周", 19))
        assertEquals(setOf(2, 4, 6, 8), parseWeeks("1-8(双周)", 19))
        assertEquals(setOf(1, 3, 5, 7), parseWeeks("1-8（单）", 19))
        assertThrows(IllegalArgumentException::class.java) { parseWeeks("8-2", 19) }
        assertThrows(IllegalArgumentException::class.java) { parseWeeks("1-20", 19) }
        assertThrows(IllegalArgumentException::class.java) { parseWeeks("1-8单双", 19) }
    }
}

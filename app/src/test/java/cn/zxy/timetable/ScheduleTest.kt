package cn.zxy.timetable

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleTest {
    private fun sample() = ScheduleJson.parse(javaClass.getResource("/schedule.json")!!.readText())

    @Test fun defaultTemplateHasNoPreloadedLessons() {
        val defaults = ScheduleJson.parse(javaClass.getResource("/default-schedule.json")!!.readText())
        assertTrue(defaults.lessons.isEmpty())
        assertEquals(11, defaults.periods.size)
    }

    @Test fun semesterBoundaries() {
        val s = sample()
        assertEquals(1, s.weekOn(LocalDate.parse("2026-09-07")))
        assertEquals(1, s.weekOn(LocalDate.parse("2026-09-13")))
        assertEquals(2, s.weekOn(LocalDate.parse("2026-09-14")))
        assertEquals(0, s.weekOn(LocalDate.parse("2026-09-06")))
        assertEquals(-1, s.weekOn(LocalDate.parse("2026-08-30")))
        assertEquals(8, s.weekOn(LocalDate.parse("2026-10-26")))
        assertTrue(s.lessonsOn(LocalDate.parse("2026-09-06")).isEmpty())
        assertTrue(s.lessonsOn(s.firstMonday.plusWeeks(19)).isEmpty())
    }

    @Test fun extractedTimetableHasEightCoursesAndNineArrangements() {
        val s = sample()
        assertEquals(8, s.lessons.map { it.courseId }.distinct().size)
        assertEquals(9, s.lessons.size)
        assertEquals(11, s.periods.size)
        assertEquals(19, s.totalWeeks)
    }

    @Test fun evenWeekCourseOnlyAppearsOnWednesday() {
        val s = sample()
        assertTrue(s.lessonsAt(8, 3).any { it.id == "demo-1" })
        assertFalse(s.lessonsAt(7, 3).any { it.id == "demo-1" })
        assertFalse(s.lessonsAt(8, 1).any { it.id == "demo-1" })
        assertFalse(s.lessonsAt(18, 3).any { it.id == "demo-1" })
    }

    @Test fun specialWeekReplacesFridayWithTuesdayNight() {
        val s = sample()
        for (week in listOf(5, 7, 8, 12)) {
            val l = s.lessonsAt(week, 5).single { it.courseId == "DEMO008" }
            assertEquals("DEMO-301", l.room); assertEquals(7, l.start); assertEquals(8, l.end)
            assertFalse(s.lessonsAt(week, 2).any { it.courseId == "DEMO008" })
        }
        val special = s.lessonsAt(6, 2).single { it.courseId == "DEMO008" }
        assertEquals("DEMO-201", special.room)
        assertEquals(LocalTime.parse("19:55"), s.startOf(special))
        assertEquals(LocalTime.parse("21:35"), s.endOf(special))
        assertFalse(s.lessonsAt(6, 5).any { it.courseId == "DEMO008" })
        assertFalse(s.lessonsAt(13, 5).any { it.courseId == "DEMO008" })
    }

    @Test fun breakIsNotMarkedAsInClassAndEndIsExclusive() {
        val s = sample(); val l = s.lessons.single { it.id == "demo-1" }
        assertEquals("未开始", s.status(l, LocalTime.parse("08:59")))
        assertEquals("进行中", s.status(l, LocalTime.parse("09:00")))
        assertEquals("课间休息", s.status(l, LocalTime.parse("09:45")))
        assertEquals("进行中", s.status(l, LocalTime.parse("09:55")))
        assertEquals("已结束", s.status(l, LocalTime.parse("12:30")))
    }

    @Test fun nextLessonSkipsEndedCoursesAndKeepsBreakBlock() {
        val s = sample(); val date = LocalDate.parse("2026-10-28")
        assertEquals("demo-1", s.nextLesson(date, LocalTime.parse("09:50"))?.id)
        assertEquals("demo-5", s.nextLesson(date, LocalTime.parse("12:30"))?.id)
        assertNull(s.nextLesson(date, LocalTime.parse("15:40")))
        assertTrue(s.lessonsOn(LocalDate.parse("2026-10-26")).isEmpty())
    }

    @Test fun usesActualFiftyMinuteTenthPeriod() {
        assertEquals(50, java.time.Duration.between(sample().period(10).start, sample().period(10).end).toMinutes())
    }

    @Test fun jsonRoundTripPreservesEverything() {
        val s = sample()
        assertEquals(s, ScheduleJson.parse(ScheduleJson.encode(s)))
    }

    @Test fun rejectsInvalidMonday() {
        val s = sample()
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.validate(s.copy(firstMonday = s.firstMonday.plusDays(1))) }
    }

    @Test fun rejectsOverlapsAndDuplicateIdentifiers() {
        val s = sample(); val l = s.lessons.first()
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.validate(s.copy(lessons = s.lessons + l)) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.validate(s.copy(lessons = s.lessons + l.copy(id = "overlap"))) }
    }

    @Test fun rejectsInvalidWeeksAndShortenedTerm() {
        val s = sample()
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.validate(s.copy(totalWeeks = 8)) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.validate(s.copy(lessons = listOf(s.lessons.first().copy(weeks = setOf(0))))) }
    }

    @Test fun rejectsUnorderedPeriodsAndBadFiles() {
        val s = sample()
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.validate(s.copy(periods = s.periods.reversed())) }
        assertThrows(Exception::class.java) { ScheduleJson.parse("not a timetable") }
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.parse(ScheduleJson.encode(s).replace("\"version\": 1", "\"version\": 2")) }
    }
}

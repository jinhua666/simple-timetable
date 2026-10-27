package cn.zxy.timetable

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SchoolXlsxTest {
    private fun base() = ScheduleJson.parse(javaClass.getResource("/schedule.json")!!.readText())
    private fun actual() = javaClass.getResource("/sample-timetable.xlsx")!!.readBytes()

    @Test fun importsSyntheticExportAndOrderedSpecialRooms() {
        val original = base()
        val imported = SchoolXlsx.parse(actual(), original)
        assertEquals(8, imported.lessons.map { it.courseId }.distinct().size)
        assertEquals(9, imported.lessons.size)
        val prompting = imported.lessons.filter { it.courseId == "DEMO008" }
        val regular = prompting.single { 5 in it.weeks }
        assertEquals(setOf(5, 7, 8, 9, 10, 11, 12), regular.weeks)
        assertEquals(5, regular.day)
        assertEquals("DEMO-301+302", regular.room)
        assertEquals("DEMO-301+302(示例校区)", regular.roomDetail)
        val special = prompting.single { 6 in it.weeks }
        assertEquals(2, special.day); assertEquals(10, special.start); assertEquals(11, special.end)
        assertEquals("DEMO-201", special.room)
        assertEquals("示例教师八", special.teacher)
        assertEquals(original.firstMonday, imported.firstMonday)
        assertEquals(original.periods, imported.periods)
        assertEquals(original.title, imported.title)
        assertEquals(original.totalWeeks, imported.totalWeeks)
    }

    @Test fun exportsReimportableExcelWithInlineStringsAndEscapedText() {
        val source = SchoolXlsx.parse(actual(), base())
        val extra = source.lessons.first().copy(id = "manual", courseId = "manual", title = "<课堂&练习>", teacher = "老师", room = "X-123", roomDetail = "X-123", day = 1, start = 1, end = 2, weeks = setOf(1, 3), color = 0)
        val schedule = source.withLesson(extra)
        val result = SchoolXlsx.parse(SchoolXlsx.encode(schedule), schedule)
        assertEquals(schedule.lessons.map { it.copy(id = "") }.toSet(), result.lessons.map { it.copy(id = "") }.toSet())
    }

    @Test fun rejectsAmbiguousRoomMappingInsteadOfGuessing() {
        val bad = rewrite(actual(), "xl/worksheets/sheet1.xml") { it.replace("DEMO-301+302(示例校区),DEMO-201(示例校区)", "DEMO-301,DEMO-201,DEMO-999") }
        assertThrows(IllegalArgumentException::class.java) { SchoolXlsx.parse(bad, base()) }
    }

    @Test fun rejectsIncompleteTimeExpressionsInsteadOfDroppingArrangements() {
        val bad = rewrite(actual(), "xl/worksheets/sheet1.xml") { it.replace("2-17周： 周二 5-7节", "2-17周： 周二 5-7节 待定") }
        assertThrows(IllegalArgumentException::class.java) { SchoolXlsx.parse(bad, base()) }
    }

    @Test fun rejectsWrongFilesAndExternalEntityDocuments() {
        assertThrows(IllegalArgumentException::class.java) { SchoolXlsx.parse("{}".toByteArray(), base()) }
        val bad = rewrite(actual(), "xl/worksheets/sheet1.xml") { "<!DOCTYPE worksheet [<!ENTITY x SYSTEM 'file:///invalid'>]>$it" }
        assertThrows(IllegalArgumentException::class.java) { SchoolXlsx.parse(bad, base()) }
    }

    @Test fun importedDataIsStableAcrossRepeatedImports() {
        val once = SchoolXlsx.parse(actual(), base())
        assertEquals(once, SchoolXlsx.parse(actual(), once))
    }

    @Test fun emptyTimetableCanBeExportedAndReimportedAfterDeletingAllCourses() {
        val empty = base().copy(lessons = emptyList())
        assertEquals(empty, SchoolXlsx.parse(SchoolXlsx.encode(empty), empty))
    }

    private fun rewrite(bytes: ByteArray, path: String, change: (String) -> String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { dest -> ZipInputStream(bytes.inputStream()).use { source ->
            while (true) {
                val entry = source.nextEntry ?: break
                val data = source.readBytes()
                dest.putNextEntry(ZipEntry(entry.name))
                dest.write(if (entry.name == path) change(data.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else data)
                dest.closeEntry()
            }
        } }
        return out.toByteArray()
    }
}

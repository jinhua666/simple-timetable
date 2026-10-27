package cn.zxy.timetable

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URI
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the school's actual column-based export, without a server or a large Excel runtime. */
object SchoolXlsx {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val requiredHeaders = setOf("课程编号", "课程名称", "任课教师", "上课时间", "上课教室")
    private val timePattern = Regex("(?:第)?([0-9][0-9,\\-()单双]*)周[:：]?\\s*(?:星期|周)([一二三四五六日天1-7])\\s*(\\d+)(?:\\s*[-~至]\\s*(\\d+))?节")

    fun parse(bytes: ByteArray, base: Schedule): Schedule {
        require(bytes.size in 4..5_000_000 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            "请选择教务网站导出的 .xlsx 排课信息文件"
        }
        val entries = unzip(bytes)
        val strings = entries["xl/sharedStrings.xml"]?.let { xml(it).elements("si").map { si -> si.elements("t").joinToString("") { t -> t.textContent } } } ?: emptyList()
        val workbook = xml(entries["xl/workbook.xml"] ?: error("文件不是有效的Excel工作簿"))
        val relationships = xml(entries["xl/_rels/workbook.xml.rels"] ?: error("Excel文件缺少工作表信息"))
        val paths = workbook.elements("sheet").mapNotNull { sheet ->
            val id = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val rel = relationships.elements("Relationship").firstOrNull { it.getAttribute("Id") == id } ?: return@mapNotNull null
            require(rel.getAttribute("TargetMode") != "External") { "课表不能引用外部工作表" }
            val uri = URI("xl/").resolve(rel.getAttribute("Target")).normalize()
            require(uri.scheme == null) { "工作表路径无效" }
            uri.path.removePrefix("/").also { require(it.startsWith("xl/") && ".." !in it.split('/')) { "工作表路径无效" } }
        }
        val colors = base.lessons.associate { it.courseId to it.color }.toMutableMap()
        for (path in paths) {
            val rows = rows(entries[path] ?: continue, strings)
            val headerIndex = rows.indexOfFirst { requiredHeaders.all(it.values.map(::cleanHeader).toSet()::contains) }
            if (headerIndex == -1) continue
            val columns = rows[headerIndex].entries.associate { cleanHeader(it.value) to it.key }
            val lessons = mutableListOf<Lesson>()
            for ((index, row) in rows.withIndex().drop(headerIndex + 1)) {
                if (row.values.all { it.isBlank() }) continue
                fun cell(name: String) = row[columns[name]]?.trim().orEmpty()
                val code = cell("课程编号")
                val title = cell("课程名称")
                if (code.isBlank() && title.isBlank()) continue
                require(code.isNotBlank() && title.isNotBlank()) { "第${index + 1}行缺少课程编号或课程名称" }
                val times = normalized(cell("上课时间"))
                val matches = timePattern.findAll(times).toList()
                require(matches.isNotEmpty() && timePattern.replace(times, "").replace(Regex("[\\s,;；]+"), "").isEmpty()) {
                    "无法识别「${title}」的上课时间，请检查导出内容"
                }
                val rooms = splitRooms(cell("上课教室"))
                require(rooms.isNotEmpty() && (rooms.size == 1 || rooms.size == matches.size)) {
                    "「${title}」的教室数量与上课安排不对应，请核对后导入"
                }
                val color = colors.getOrPut(code) { colors.size % 8 }
                val teacher = cell("任课教师").replace(Regex("[PF]\\d{6,}"), "").trim()
                matches.forEachIndexed { arrangement, match ->
                    val weeks = parseWeeks(match.groupValues[1], base.totalWeeks)
                    val dayText = match.groupValues[2]
                    val day = dayText.toIntOrNull() ?: ("一二三四五六日".indexOf(if (dayText == "天") "日" else dayText) + 1)
                    val start = match.groupValues[3].toIntOrNull() ?: 0
                    val end = match.groupValues[4].ifBlank { match.groupValues[3] }.toIntOrNull() ?: 0
                    val fullRoom = rooms[if (rooms.size == 1) 0 else arrangement]
                    val room = fullRoom.replace(Regex("[（(][^()（）]*[)）]"), "").trim()
                    val identity = listOf(code, cell("教学班编号"), title, day, start, end, weeks.sorted().joinToString(","), fullRoom).joinToString("|")
                    lessons += Lesson("xlsx-${UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))}", code,
                        title, teacher, room, fullRoom, day, start, end, weeks, color)
                }
            }
            return base.copy(lessons = lessons).also { ScheduleJson.validate(it) }
        }
        error("没有找到排课信息表，请选择包含课程名称、上课时间、上课教室的教务导出文件")
    }

    /** Uses the same columns as the school's export, so manually edited courses can be backed up and reimported. */
    fun encode(schedule: Schedule): ByteArray {
        ScheduleJson.validate(schedule)
        val headers = listOf("课程编号", "课程名称", "教学班编号", "任课教师", "上课时间", "上课教室")
        val data = listOf(headers) + schedule.lessons.groupBy { listOf(it.courseId, it.title, it.teacher) }.values.map { group ->
            val lesson = group.first()
            listOf(lesson.courseId, lesson.title, "", lesson.teacher,
                group.joinToString(",") { "${it.weeks.weekDescription().removePrefix("第")}： 周${"一二三四五六日"[it.day - 1]} ${it.start}-${it.end}节" },
                group.joinToString(",") { it.roomDetail })
        }
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        val sheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"$NS\"><sheetData>")
            data.forEachIndexed { r, cells ->
                append("<row r=\"${r + 1}\">")
                cells.forEachIndexed { c, value -> append("<c r=\"${('A'.code + c).toChar()}${r + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>") }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val files = mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<workbook xmlns="$NS" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="排课信息" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheet,
        )
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        } }.toByteArray()
    }

    private fun normalized(text: String) = text.replace('，', ',').replace('、', ',').replace('（', '(').replace('）', ')')
        .replace('–', '-').replace('—', '-').replace('～', '-').replace('\u00a0', ' ')
    private fun cleanHeader(text: String) = text.replace(Regex("[\\s\uFEFF]+"), "")

    private fun splitRooms(text: String): List<String> {
        val result = mutableListOf<String>()
        val part = StringBuilder()
        var depth = 0
        for (char in text) {
            if (char == '(' || char == '（') depth++
            if (char == ')' || char == '）') depth--
            if (char in ",，;；\n" && depth == 0) { result += part.toString().trim(); part.clear() } else part.append(char)
        }
        if (part.isNotBlank()) result += part.toString().trim()
        require(result.all { it.isNotBlank() } && depth == 0) { "教室信息格式不正确" }
        return result
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        var total = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 200) { "Excel文件内容过多" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = zip.read(buffer)
                    if (size == -1) break
                    total += size
                    require(total <= 20_000_000 && output.size() + size <= 8_000_000) { "Excel文件过大" }
                    output.write(buffer, 0, size)
                }
                if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) result[entry.name] = output.toByteArray()
            }
        }
        return result
    }

    private fun xml(bytes: ByteArray): Element {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true) && !text.contains('\u0000')) { "Excel文件包含不支持的XML内容" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        return factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            .parse(InputSource(StringReader(text))).documentElement
    }

    private fun Element.elements(name: String): List<Element> = getElementsByTagNameNS("*", name).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    private fun rows(bytes: ByteArray, strings: List<String>): List<Map<Int, String>> = xml(bytes).elements("row").also {
        require(it.size <= 2000) { "排课信息不能超过2000行" }
    }.map { row -> row.elements("c").associate { cell ->
        val address = cell.getAttribute("r").takeWhile { it.isLetter() }
        val column = address.fold(0) { n, c -> n * 26 + c.uppercaseChar().code - 'A'.code + 1 } - 1
        require(column in 0..199) { "排课信息列数过多" }
        val value = cell.elements("v").firstOrNull()?.textContent.orEmpty()
        val text = when (cell.getAttribute("t")) {
            "s" -> strings.getOrNull(value.toIntOrNull() ?: -1) ?: error("Excel文本索引无效")
            "inlineStr" -> cell.elements("t").joinToString("") { it.textContent }
            else -> value
        }
        require(text.length <= 20_000) { "Excel单元格内容过长" }
        column to text
    } }
}

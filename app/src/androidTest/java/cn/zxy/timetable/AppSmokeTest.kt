package cn.zxy.timetable

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.After
import org.junit.rules.TestName
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import android.content.res.Configuration
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import java.util.regex.Pattern
import java.time.LocalDate
import java.time.ZoneId

class AppSmokeTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = TestName()
    private lateinit var vm: TimetableViewModel
    private var originalSchedule: Schedule? = null

    @Before fun prepareFixture() {
        loaded()
        vm = ViewModelProvider(app.activity)[TimetableViewModel::class.java]
        originalSchedule = vm.schedule.value!!
        val defaults = app.activity.assets.open("default-schedule.json").bufferedReader().use { ScheduleJson.parse(it.readText()) }
        val initial = if (testName.methodName in setOf("startsWithZeroCoursesAndKeepsSemesterConfiguration", "importSyntheticExcelThroughSystemFilePicker")) defaults
            else InstrumentationRegistry.getInstrumentation().context.assets.open("sample-timetable.xlsx").use { SchoolXlsx.parse(it.readBytes(), defaults) }
        restore(vm, initial)
    }

    @After fun restoreUserData() {
        originalSchedule?.let { restore(vm, it) }
    }

    @Test fun startsWithZeroCoursesAndKeepsSemesterConfiguration() {
        app.waitUntil(15_000) { app.onAllNodesWithText("0门课程").fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithText("0门课程").assertIsDisplayed()
        app.onNodeWithText("今天没有课程").assertIsDisplayed()
        val schedule = vm.schedule.value!!
        assertTrue(schedule.lessons.isEmpty())
        assertEquals(11, schedule.periods.size)
        val week = schedule.weekOn(LocalDate.now(ZoneId.of("Asia/Shanghai"))).coerceIn(1, schedule.totalWeeks)
        app.onNodeWithTag("tab_1").performClick()
        app.onNode(hasText("本周没有课程") and hasAnyAncestor(hasTestTag("week_grid_$week"))).assertIsDisplayed()
    }

    @Test fun navigateToSpecialWeekAndOpenSampleLesson() {
        app.waitUntil(15_000) { app.onAllNodesWithText("当天安排").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Configuration.ORIENTATION_PORTRAIT, app.activity.resources.configuration.orientation)
        app.onNodeWithTag("tab_1").performClick()
        app.onNodeWithTag("week_picker").performClick()
        app.onNodeWithTag("week_6").performScrollTo().performClick()
        app.onNodeWithText("第6周 ▾").assertIsDisplayed()
        course("示例课程八，星期二，第10至11节，DEMO-201", 6).performScrollTo().performClick()
        app.onNodeWithText("星期二 · 第10–11节\n19:55–21:35").assertIsDisplayed()
        app.onNodeWithText("知道了").performClick()
        app.onNodeWithTag("week_picker").performClick()
        app.onNodeWithTag("week_8").performScrollTo().performClick()
        course("示例课程一，星期三，第1至4节，DEMO-201", 8).performScrollTo()
        course("示例课程八，星期五，第7至8节", 8).assertExists()
        course("示例课程八，星期二，第10至11节，DEMO-201", 8).assertDoesNotExist()
        app.onNodeWithTag("tab_0").performClick()
        app.onNodeWithText("当天安排").performScrollTo().assertIsDisplayed()
    }

    @Test fun swipeWeeksAndRespectSemesterEdges() {
        loaded()
        app.onNodeWithTag("tab_1").performClick()
        selectWeek(8)
        app.onNodeWithTag("previous_week").assertDoesNotExist()
        app.onNodeWithTag("next_week").assertDoesNotExist()
        app.onNodeWithTag("week_pager").performTouchInput { swipeLeft() }
        app.onNodeWithText("第9周 ▾").assertIsDisplayed()
        app.onNodeWithTag("week_pager").performTouchInput { swipeRight() }
        app.onNodeWithText("第8周 ▾").assertIsDisplayed()
        selectWeek(1)
        app.onNodeWithTag("week_pager").performTouchInput { swipeRight() }
        app.onNodeWithText("第1周 ▾").assertIsDisplayed()
        selectWeek(19)
        app.onNodeWithTag("week_pager").performTouchInput { swipeLeft() }
        app.onNodeWithText("第19周 ▾").assertIsDisplayed()
        app.onNodeWithTag("current_week").performClick()
    }

    @Test fun addEditAndDeleteCourseThroughDialogs() {
        loaded()
        val vm = ViewModelProvider(app.activity)[TimetableViewModel::class.java]
        val original = vm.schedule.value!!
        try {
            app.onNodeWithTag("add_course").performClick()
            app.onNodeWithTag("lesson_title").performTextReplacement("测试新增课程")
            app.onNodeWithTag("lesson_room").performTextReplacement("X-101")
            app.onNodeWithTag("lesson_weeks").performScrollTo().performTextReplacement("8")
            app.onNodeWithTag("save_lesson").performClick()
            app.waitUntil(10_000) { app.onAllNodesWithTag("lesson_title").fetchSemanticsNodes().isEmpty() }
            app.onNodeWithTag("tab_1").performClick()
            selectWeek(8)
            course("测试新增课程，星期一，第1至2节，X-101", 8).performScrollTo().performClick()
            app.onNodeWithTag("edit_course").performClick()
            app.onNodeWithTag("lesson_title").performTextReplacement("测试修改课程")
            app.onNodeWithTag("lesson_room").performTextReplacement("X-202")
            app.onNodeWithTag("save_lesson").performClick()
            app.waitUntil(10_000) { app.onAllNodesWithTag("lesson_title").fetchSemanticsNodes().isEmpty() }
            course("测试修改课程，星期一，第1至2节，X-202", 8).performScrollTo().performClick()
            app.onNodeWithTag("delete_course").performClick()
            app.onNodeWithText("取消").performClick()
            course("测试修改课程，星期一，第1至2节，X-202", 8).assertExists().performClick()
            app.onNodeWithTag("delete_course").performClick()
            app.onNodeWithTag("confirm_delete").performClick()
            app.waitUntil(10_000) { vm.schedule.value?.lessons?.none { it.title == "测试修改课程" } == true }
            course("测试修改课程", 8).assertDoesNotExist()
        } finally { restore(vm, original) }
    }

    @Test fun importSyntheticExcelThroughSystemFilePicker() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        loaded()
        val vm = ViewModelProvider(app.activity)[TimetableViewModel::class.java]
        val original = vm.schedule.value!!
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = app.activity.contentResolver
        val fileName = "简课导入测试-${System.currentTimeMillis()}.xlsx"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, SchoolXlsx.MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        val device = UiDevice.getInstance(instrumentation)
        try {
            resolver.openOutputStream(uri)!!.use { output -> instrumentation.context.assets.open("sample-timetable.xlsx").use { it.copyTo(output) } }
            app.onNodeWithTag("more").performClick()
            app.onNodeWithText("导入Excel课表").performClick()
            var file = device.wait(Until.findObject(By.text(fileName)), 4_000)
            if (file == null) {
                device.findObject(By.desc(Pattern.compile("显示根目录|Show roots|Open navigation drawer")))?.click()
                device.wait(Until.findObject(By.text(Pattern.compile("下载|Downloads"))), 5_000)?.click()
                file = device.wait(Until.findObject(By.text(fileName)), 10_000)
            }
            checkNotNull(file) { "系统文件选择器未显示测试Excel文件" }.click()
            app.waitUntil(15_000) { app.onAllNodesWithTag("confirm_import").fetchSemanticsNodes().isNotEmpty() }
            app.onNodeWithText("8门课程 · 9条安排", substring = true).assertIsDisplayed()
            app.onNodeWithTag("confirm_import").performClick()
            app.waitUntil(10_000) { vm.schedule.value?.lessons?.any { it.room == "DEMO-301+302" } == true }
            app.onNodeWithTag("tab_1").performClick()
            selectWeek(8)
            course("示例课程八，星期五，第7至8节，DEMO-301+302", 8).performScrollTo().performClick()
            app.onNodeWithText("DEMO-301+302(示例校区)").assertIsDisplayed()
            app.onNodeWithText("知道了").performClick()
            course("示例课程一，星期三，第1至4节，DEMO-201", 8).performScrollTo()
        } finally {
            if (app.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty()) app.onAllNodesWithText("知道了").onLast().performClick()
            resolver.delete(uri, null, null)
            restore(vm, original)
        }
    }

    private fun loaded() = app.waitUntil(15_000) { app.onAllNodesWithText("当天安排").fetchSemanticsNodes().isNotEmpty() }
    private fun selectWeek(week: Int) {
        app.onNodeWithTag("week_picker").performClick()
        app.onNodeWithTag("week_$week").performScrollTo().performClick()
        app.onNodeWithText("第${week}周 ▾").assertIsDisplayed()
    }
    private fun course(description: String, week: Int) = app.onNode(hasContentDescription(description, substring = true) and hasAnyAncestor(hasTestTag("week_grid_$week")))
    private fun restore(vm: TimetableViewModel, original: Schedule) {
        app.waitUntil(10_000) { !vm.busy.value }
        app.runOnUiThread { vm.save(original) {} }
        app.waitUntil(10_000) { !vm.busy.value && vm.schedule.value == original }
    }

    @Test fun invalidSettingsAreRejectedAndSavedSettingsSurviveRecreation() {
        app.waitUntil(15_000) { app.onAllNodesWithText("当天安排").fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithTag("more").performClick()
        app.onNodeWithText("学期设置").performClick()
        app.onNodeWithTag("first_monday").performTextReplacement("2026-09-08")
        app.onNodeWithTag("save_settings").performClick()
        app.onNodeWithText("第1周的起始日期必须是周一").assertIsDisplayed()
        app.onNodeWithTag("first_monday").performTextReplacement("2026-09-07")
        app.onNodeWithTag("term_title").performTextReplacement("持久化测试")
        app.onNodeWithTag("save_settings").performClick()
        app.waitUntil(10_000) { app.onAllNodesWithText("学期设置").fetchSemanticsNodes().isEmpty() }
        app.activityRule.scenario.recreate()
        app.waitUntil(15_000) { app.onAllNodesWithText("当天安排").fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithTag("tab_1").performClick()
        app.onNodeWithText("持久化测试").assertIsDisplayed()
        app.onNodeWithTag("more").performClick()
        app.onNodeWithText("学期设置").performClick()
        app.onNodeWithTag("term_title").performTextReplacement("2026–2027学年 · 第一学期")
        app.onNodeWithTag("save_settings").performClick()
        app.waitUntil(10_000) { app.onAllNodesWithText("学期设置").fetchSemanticsNodes().isEmpty() }
    }

}

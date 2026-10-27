package cn.zxy.timetable

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

// A timetable is imported/replaced as one atomic document, so settings and lessons
// cannot drift apart after a failed import or process interruption.
@Entity(tableName = "timetable")
data class ScheduleDocument(@PrimaryKey val id: Int = 1, val json: String)

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM timetable WHERE id = 1")
    fun observe(): Flow<ScheduleDocument?>
    @Query("SELECT * FROM timetable WHERE id = 1")
    suspend fun get(): ScheduleDocument?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(document: ScheduleDocument)
}

@Database(entities = [ScheduleDocument::class], version = 1, exportSchema = false)
abstract class ScheduleDatabase : RoomDatabase() { abstract fun scheduleDao(): ScheduleDao }

class ScheduleRepository(context: Context) {
    private val app = context.applicationContext
    private val dao = Room.databaseBuilder(app, ScheduleDatabase::class.java, "schedule.db").build().scheduleDao()
    val schedule: Flow<Schedule?> = dao.observe().map { doc -> withContext(Dispatchers.Default) { doc?.let { ScheduleJson.parse(it.json) } } }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (dao.get() == null) {
            val text = app.assets.open("default-schedule.json").bufferedReader().use { it.readText() }
            val schedule = ScheduleJson.parse(text).copy(
                firstMonday = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            )
            dao.save(ScheduleDocument(json = ScheduleJson.encode(schedule)))
        }
    }

    suspend fun save(schedule: Schedule) = withContext(Dispatchers.IO) {
        ScheduleJson.validate(schedule)
        dao.save(ScheduleDocument(json = ScheduleJson.encode(schedule)))
    }
}

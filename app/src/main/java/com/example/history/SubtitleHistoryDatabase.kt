package com.example.history

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
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "subtitle_history_records")
data class SubtitleHistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val spokenText: String,
    val originalText: String,
    val translatedText: String,
    val sourceAppPackage: String,
    val characterLabel: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

@Dao
interface SubtitleHistoryDao {
    @Query("SELECT * FROM subtitle_history_records ORDER BY timestampMs DESC LIMIT 500")
    fun observeAllRecords(): Flow<List<SubtitleHistoryRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: SubtitleHistoryRecord)

    @Query("DELETE FROM subtitle_history_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM subtitle_history_records")
    suspend fun clearAll()
}

@Database(
    entities = [SubtitleHistoryRecord::class],
    version = 1,
    exportSchema = true
)
abstract class SubtitleHistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): SubtitleHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: SubtitleHistoryDatabase? = null

        fun getInstance(context: Context): SubtitleHistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SubtitleHistoryDatabase::class.java,
                    "animesub_voz_history.db"
                )
                    .addMigrations(*Migrations.ALL_MIGRATIONS)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

class SubtitleHistoryRepository(context: Context) {
    private val dao = SubtitleHistoryDatabase.getInstance(context).historyDao()

    val allRecordsFlow: Flow<List<SubtitleHistoryRecord>> = dao.observeAllRecords()

    suspend fun insert(record: SubtitleHistoryRecord) {
        runCatching { dao.insertRecord(record) }
    }

    suspend fun deleteById(id: Long) {
        runCatching { dao.deleteById(id) }
    }

    suspend fun clearAll() {
        runCatching { dao.clearAll() }
    }
}

/**
 * Exportador del historial de subtítulos a formatos estándar `.txt` y `.srt` (SubRip)
 * con marcas de tiempo aproximadas calculadas a partir del inicio de la sesión.
 */
object SubtitleHistoryExporter {

    fun formatAsPlainText(records: List<SubtitleHistoryRecord>): String {
        if (records.isEmpty()) return ""
        val chronological = records.sortedBy { it.timestampMs }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        for (rec in chronological) {
            val dateStr = sdf.format(Date(rec.timestampMs))
            val appTag = rec.sourceAppPackage.substringAfterLast('.').ifBlank { "General" }
            sb.append("[$dateStr] ($appTag) ${rec.spokenText}")
            if (rec.originalText.isNotBlank() && rec.originalText != rec.spokenText) {
                sb.append(" | Orig: ${rec.originalText}")
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun formatAsSrt(records: List<SubtitleHistoryRecord>): String {
        if (records.isEmpty()) return ""
        val chronological = records.sortedBy { it.timestampMs }
        val baseTimeMs = chronological.first().timestampMs
        val sb = StringBuilder()

        for (i in chronological.indices) {
            val rec = chronological[i]
            val startOffsetMs = (rec.timestampMs - baseTimeMs).coerceAtLeast(0L)
            val nextStartMs = chronological.getOrNull(i + 1)?.let {
                (it.timestampMs - baseTimeMs).coerceAtLeast(startOffsetMs + 800L)
            }
            val estimatedDurationMs = (rec.spokenText.length * 75L).coerceIn(1500L, 4500L)
            val endOffsetMs = if (nextStartMs != null) {
                minOf(startOffsetMs + estimatedDurationMs, (nextStartMs - 120L).coerceAtLeast(startOffsetMs + 600L))
            } else {
                startOffsetMs + estimatedDurationMs
            }

            sb.append(i + 1).append('\n')
            sb.append(formatSrtTimestamp(startOffsetMs))
                .append(" --> ")
                .append(formatSrtTimestamp(endOffsetMs))
                .append('\n')
            sb.append(rec.spokenText).append('\n')
            if (rec.originalText.isNotBlank() && rec.originalText != rec.spokenText) {
                sb.append(rec.originalText).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun formatSrtTimestamp(offsetMs: Long): String {
        val totalMs = offsetMs.coerceAtLeast(0L)
        val hours = totalMs / 3_600_000L
        val minutes = (totalMs % 3_600_000L) / 60_000L
        val seconds = (totalMs % 60_000L) / 1_000L
        val millis = totalMs % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}

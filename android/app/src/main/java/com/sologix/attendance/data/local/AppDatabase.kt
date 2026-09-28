package com.sologix.attendance.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sologix.attendance.data.local.dao.AttendanceDao
import com.sologix.attendance.data.local.dao.CustomerDao
import com.sologix.attendance.data.local.dao.GpsPointDao
import com.sologix.attendance.data.local.dao.SyncQueueDao
import com.sologix.attendance.data.local.dao.TaskDao
import com.sologix.attendance.data.local.dao.VisitDao
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.CustomerEntity
import com.sologix.attendance.data.local.entity.GpsPointEntity
import com.sologix.attendance.data.local.entity.SyncQueueEntity
import com.sologix.attendance.data.local.entity.TaskEntity
import com.sologix.attendance.data.local.entity.VisitEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_queue ADD COLUMN last_error TEXT DEFAULT NULL")
    }
}

@Database(
    entities = [
        SyncQueueEntity::class,
        AttendanceEntity::class,
        GpsPointEntity::class,
        TaskEntity::class,
        CustomerEntity::class,
        VisitEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun gpsPointDao(): GpsPointDao
    abstract fun taskDao(): TaskDao
    abstract fun customerDao(): CustomerDao
    abstract fun visitDao(): VisitDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sologix_attendance.db"
                ).addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.sologix.attendance.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sologix.attendance.data.local.dao.AttendanceDao
import com.sologix.attendance.data.local.dao.CustomerDao
import com.sologix.attendance.data.local.dao.ExpenseDao
import com.sologix.attendance.data.local.dao.GpsPointDao
import com.sologix.attendance.data.local.dao.SyncQueueDao
import com.sologix.attendance.data.local.dao.TaskDao
import com.sologix.attendance.data.local.dao.VisitDao
import com.sologix.attendance.data.local.entity.AttendanceEntity
import com.sologix.attendance.data.local.entity.CustomerEntity
import com.sologix.attendance.data.local.entity.ExpenseEntity
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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `expenses` (
                `id` TEXT NOT NULL,
                `user_id` TEXT NOT NULL,
                `amount` REAL NOT NULL,
                `category` TEXT NOT NULL,
                `receipt_photo_path` TEXT,
                `status` TEXT NOT NULL,
                `reviewed_by` TEXT,
                `operation_id` TEXT NOT NULL,
                `sync_state` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
    }
}

@Database(
    entities = [
        SyncQueueEntity::class,
        AttendanceEntity::class,
        GpsPointEntity::class,
        TaskEntity::class,
        CustomerEntity::class,
        VisitEntity::class,
        ExpenseEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun gpsPointDao(): GpsPointDao
    abstract fun taskDao(): TaskDao
    abstract fun customerDao(): CustomerDao
    abstract fun visitDao(): VisitDao
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sologix_attendance.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

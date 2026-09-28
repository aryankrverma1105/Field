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

@Database(
    entities = [
        SyncQueueEntity::class,
        AttendanceEntity::class,
        GpsPointEntity::class,
        TaskEntity::class,
        CustomerEntity::class,
        VisitEntity::class
    ],
    version = 1,
    exportSchema = false
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
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

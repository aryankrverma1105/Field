package com.sologix.attendance.di

import android.content.Context
import com.sologix.attendance.data.local.AppDatabase
import com.sologix.attendance.data.local.dao.AttendanceDao
import com.sologix.attendance.data.local.dao.CustomerDao
import com.sologix.attendance.data.local.dao.GpsPointDao
import com.sologix.attendance.data.local.dao.SyncQueueDao
import com.sologix.attendance.data.local.dao.TaskDao
import com.sologix.attendance.data.local.dao.VisitDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun provideSyncQueueDao(db: AppDatabase): SyncQueueDao = db.syncQueueDao()

    @Provides
    fun provideAttendanceDao(db: AppDatabase): AttendanceDao = db.attendanceDao()

    @Provides
    fun provideGpsPointDao(db: AppDatabase): GpsPointDao = db.gpsPointDao()

    @Provides
    fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideCustomerDao(db: AppDatabase): CustomerDao = db.customerDao()

    @Provides
    fun provideVisitDao(db: AppDatabase): VisitDao = db.visitDao()
}

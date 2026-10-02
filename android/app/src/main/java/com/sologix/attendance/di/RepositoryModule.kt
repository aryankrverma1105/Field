package com.sologix.attendance.di

import com.sologix.attendance.data.repository.AttendanceRepository
import com.sologix.attendance.data.repository.AttendanceRepositoryImpl
import com.sologix.attendance.data.repository.AuthRepository
import com.sologix.attendance.data.repository.AuthRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAttendanceRepository(
        impl: AttendanceRepositoryImpl
    ): AttendanceRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        impl: AuthRepositoryImpl
    ): AuthRepository
}

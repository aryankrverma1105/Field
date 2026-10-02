package com.sologix.attendance.di

import com.sologix.attendance.data.repository.AttendanceRepository
import com.sologix.attendance.data.repository.AttendanceRepositoryImpl
import com.sologix.attendance.data.repository.AuthRepository
import com.sologix.attendance.data.repository.AuthRepositoryImpl
import com.sologix.attendance.data.repository.CustomerRepository
import com.sologix.attendance.data.repository.CustomerRepositoryImpl
import com.sologix.attendance.data.repository.ExpenseRepository
import com.sologix.attendance.data.repository.ExpenseRepositoryImpl
import com.sologix.attendance.data.repository.TaskRepository
import com.sologix.attendance.data.repository.TaskRepositoryImpl
import com.sologix.attendance.data.repository.VisitRepository
import com.sologix.attendance.data.repository.VisitRepositoryImpl
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

    @Binds
    @Singleton
    abstract fun bindTaskRepository(
        impl: TaskRepositoryImpl
    ): TaskRepository

    @Binds
    @Singleton
    abstract fun bindCustomerRepository(
        impl: CustomerRepositoryImpl
    ): CustomerRepository

    @Binds
    @Singleton
    abstract fun bindVisitRepository(
        impl: VisitRepositoryImpl
    ): VisitRepository

    @Binds
    @Singleton
    abstract fun bindExpenseRepository(
        impl: ExpenseRepositoryImpl
    ): ExpenseRepository
}

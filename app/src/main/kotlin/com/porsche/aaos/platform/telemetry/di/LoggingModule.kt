package com.porsche.aaos.platform.telemetry.di

import com.porsche.aaos.platform.telemetry.core.logging.LogcatLogger
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {
    @Provides
    @Singleton
    fun provideLogger(): Logger = LogcatLogger()
}

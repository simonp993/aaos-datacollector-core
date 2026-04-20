package com.porsche.datacollector.di

import com.porsche.datacollector.core.logging.LogcatLogger
import com.porsche.datacollector.core.logging.Logger
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

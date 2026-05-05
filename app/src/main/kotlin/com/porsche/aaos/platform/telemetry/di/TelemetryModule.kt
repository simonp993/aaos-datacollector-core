package com.porsche.aaos.platform.telemetry.di

import com.porsche.aaos.platform.telemetry.telemetry.LogTelemetry
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryModule {
    @Binds
    @Singleton
    abstract fun bindTelemetry(impl: LogTelemetry): Telemetry
}

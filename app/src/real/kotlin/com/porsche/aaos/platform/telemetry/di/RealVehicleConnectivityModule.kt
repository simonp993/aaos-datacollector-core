package com.porsche.aaos.platform.telemetry.di

import android.content.Context
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.AidlDisplayStandbySource
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayStandbySource
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayStateSource
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.RsiDisplayStateSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RealVehicleConnectivityModule {
    @Provides
    @Singleton
    fun provideDisplayStateSource(
        @ApplicationContext context: Context,
        logger: Logger,
    ): DisplayStateSource = RsiDisplayStateSource(context, logger)

    @Provides
    @Singleton
    fun provideDisplayStandbySource(
        @ApplicationContext context: Context,
        logger: Logger,
    ): DisplayStandbySource = AidlDisplayStandbySource(context, logger)
}

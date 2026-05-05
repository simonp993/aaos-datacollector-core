package com.porsche.aaos.platform.telemetry.di

import android.content.Context
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.vehicleplatform.CarPropertyAdapter
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RealVehiclePlatformModule {
    @Provides
    @Singleton
    fun provideVhalPropertyService(
        @ApplicationContext context: Context,
        logger: Logger,
    ): VhalPropertyService = CarPropertyAdapter.create(context, logger)
}

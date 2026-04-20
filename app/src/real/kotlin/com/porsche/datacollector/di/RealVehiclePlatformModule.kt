package com.porsche.datacollector.di

import android.content.Context
import com.porsche.sportapps.core.logging.Logger
import com.porsche.sportapps.vehicleplatform.CarPropertyAdapter
import com.porsche.sportapps.vehicleplatform.VhalPropertyService
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

package com.porsche.aaos.platform.telemetry.di

import com.porsche.aaos.platform.telemetry.vehicleconnectivity.fake.FakeDisplayStateSource
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayStateSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MockVehicleConnectivityModule {
    @Provides
    @Singleton
    fun provideDisplayStateSource(): DisplayStateSource = FakeDisplayStateSource()
}

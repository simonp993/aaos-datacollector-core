package com.porsche.datacollector.di

import com.porsche.datacollector.vehicleplatform.VhalPropertyIds
import com.porsche.datacollector.vehicleplatform.VhalPropertyService
import com.porsche.datacollector.vehicleplatform.fake.FakeVhalPropertyService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Module
@InstallIn(SingletonComponent::class)
object MockVehiclePlatformModule {
    @Provides
    @Singleton
    fun provideVhalPropertyService(): VhalPropertyService = FakeVhalPropertyService().apply {
        runBlocking {
            emit(VhalPropertyIds.PORSCHE_DIAG_MMTR_AVAILABLE, 1)
        }
    }
}

package com.porsche.datacollector.di

import com.porsche.sportapps.vehicleplatform.VhalPropertyIds
import com.porsche.sportapps.vehicleplatform.VhalPropertyService
import com.porsche.sportapps.vehicleplatform.fake.FakeVhalPropertyService
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

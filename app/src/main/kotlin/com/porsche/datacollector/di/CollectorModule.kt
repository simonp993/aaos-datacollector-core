package com.porsche.datacollector.di

import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.collector.media.MediaPlaybackCollector
import com.porsche.datacollector.collector.network.NetworkStatsCollector
import com.porsche.datacollector.collector.system.AppLifecycleCollector
import com.porsche.datacollector.collector.system.AudioCollector
import com.porsche.datacollector.collector.system.ConnectivityCollector
import com.porsche.datacollector.collector.system.MemoryCollector
import com.porsche.datacollector.collector.system.PackageCollector
import com.porsche.datacollector.collector.system.ProcessCollector
import com.porsche.datacollector.collector.system.SensorBatteryCollector
import com.porsche.datacollector.collector.system.TelephonyCollector
import com.porsche.datacollector.collector.system.TouchInputCollector
import com.porsche.datacollector.collector.vehicle.CarInfoCollector
import com.porsche.datacollector.collector.vehicle.DriveStateCollector
import com.porsche.datacollector.collector.vehicle.VehiclePropertyCollector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class CollectorModule {

    @Binds
    @IntoSet
    abstract fun bindVehiclePropertyCollector(impl: VehiclePropertyCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindDriveStateCollector(impl: DriveStateCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindCarInfoCollector(impl: CarInfoCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindMediaPlaybackCollector(impl: MediaPlaybackCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindAppLifecycleCollector(impl: AppLifecycleCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindProcessCollector(impl: ProcessCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindMemoryCollector(impl: MemoryCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindNetworkStatsCollector(impl: NetworkStatsCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindConnectivityCollector(impl: ConnectivityCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindAudioCollector(impl: AudioCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindTelephonyCollector(impl: TelephonyCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindTouchInputCollector(impl: TouchInputCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindSensorBatteryCollector(impl: SensorBatteryCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindPackageCollector(impl: PackageCollector): Collector
}

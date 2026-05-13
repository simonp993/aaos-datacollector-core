package com.porsche.aaos.platform.telemetry.di

import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.collector.media.MediaPlaybackCollector
import com.porsche.aaos.platform.telemetry.collector.network.NetworkStatsCollector
import com.porsche.aaos.platform.telemetry.collector.system.AppLifecycleCollector
import com.porsche.aaos.platform.telemetry.collector.system.AssistantCollector
import com.porsche.aaos.platform.telemetry.collector.system.AudioCollector
import com.porsche.aaos.platform.telemetry.collector.system.BluetoothCollector
import com.porsche.aaos.platform.telemetry.collector.system.ConnectivityCollector
import com.porsche.aaos.platform.telemetry.collector.system.AppExitCollector
import com.porsche.aaos.platform.telemetry.collector.system.DisplayStateCollector
import com.porsche.aaos.platform.telemetry.collector.system.FrameRateCollector
// import com.porsche.aaos.platform.telemetry.collector.system.IoCollector
import com.porsche.aaos.platform.telemetry.collector.system.LocationCollector
import com.porsche.aaos.platform.telemetry.collector.system.MemoryCollector
import com.porsche.aaos.platform.telemetry.collector.system.NavigationCollector
import com.porsche.aaos.platform.telemetry.collector.system.PackageCollector
import com.porsche.aaos.platform.telemetry.collector.system.ProcessCollector
import com.porsche.aaos.platform.telemetry.collector.system.SelfMonitorCollector
import com.porsche.aaos.platform.telemetry.collector.system.SensorBatteryCollector
import com.porsche.aaos.platform.telemetry.collector.system.StorageCollector
import com.porsche.aaos.platform.telemetry.collector.system.SystemCpuCollector
import com.porsche.aaos.platform.telemetry.collector.system.SystemMemoryCollector
import com.porsche.aaos.platform.telemetry.collector.system.TelephonyCollector
import com.porsche.aaos.platform.telemetry.collector.system.TimeChangeCollector
import com.porsche.aaos.platform.telemetry.collector.system.TouchInputCollector
import com.porsche.aaos.platform.telemetry.collector.system.UserStateCollector
import com.porsche.aaos.platform.telemetry.collector.vehicle.CarInfoCollector
import com.porsche.aaos.platform.telemetry.collector.vehicle.PowerStateCollector
import com.porsche.aaos.platform.telemetry.collector.vehicle.VehiclePropertyCollector
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
    abstract fun bindCarInfoCollector(impl: CarInfoCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindMediaPlaybackCollector(impl: MediaPlaybackCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindAppLifecycleCollector(impl: AppLifecycleCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindAssistantCollector(impl: AssistantCollector): Collector

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

    @Binds
    @IntoSet
    abstract fun bindTimeChangeCollector(impl: TimeChangeCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindFrameRateCollector(impl: FrameRateCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindStorageCollector(impl: StorageCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindLocationCollector(impl: LocationCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindNavigationCollector(impl: NavigationCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindPowerStateCollector(impl: PowerStateCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindSystemCpuCollector(impl: SystemCpuCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindSystemMemoryCollector(impl: SystemMemoryCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindAppExitCollector(impl: AppExitCollector): Collector

    // Limitation: IoCollector (System_IoPerPackage) disabled — SELinux denies
    // platform_app access to carwatchdogd_service, producing no I/O data.
    // @Binds @IntoSet
    // abstract fun bindIoCollector(impl: IoCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindSelfMonitorCollector(impl: SelfMonitorCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindBluetoothCollector(impl: BluetoothCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindDisplayStateCollector(impl: DisplayStateCollector): Collector

    @Binds
    @IntoSet
    abstract fun bindUserStateCollector(impl: UserStateCollector): Collector
}

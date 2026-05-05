package com.porsche.aaos.platform.telemetry.collector

interface Collector {
    val name: String

    /**
     * Whether this collector is enabled. Checked at service start.
     * Override in subclass or toggle at runtime via system property:
     *   adb shell setprop datacollector.enable.<name> false
     */
    val enabled: Boolean get() = true

    suspend fun start()

    fun stop()
}

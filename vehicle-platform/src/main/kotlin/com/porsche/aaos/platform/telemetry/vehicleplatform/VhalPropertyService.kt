package com.porsche.aaos.platform.telemetry.vehicleplatform

import kotlinx.coroutines.flow.Flow

interface VhalPropertyService {
    fun <T : Any> observeProperty(
        propertyId: Int,
        areaId: Int = 0,
    ): Flow<T>

    fun <T : Any> readProperty(
        propertyId: Int,
        areaId: Int = 0,
    ): T?
}

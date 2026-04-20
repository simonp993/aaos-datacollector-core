package com.porsche.datacollector.vehicleplatform.fake

import com.porsche.datacollector.vehicleplatform.VhalPropertyService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeVhalPropertyService : VhalPropertyService {
    private val propertyFlows = mutableMapOf<PropertyKey, MutableSharedFlow<Any>>()
    private val propertyValues = mutableMapOf<PropertyKey, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observeProperty(
        propertyId: Int,
        areaId: Int,
    ): Flow<T> = getOrCreateFlow(PropertyKey(propertyId, areaId)) as Flow<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> readProperty(
        propertyId: Int,
        areaId: Int,
    ): T? = propertyValues[PropertyKey(propertyId, areaId)] as? T

    suspend fun <T : Any> emit(
        propertyId: Int,
        value: T,
        areaId: Int = 0,
    ) {
        val key = PropertyKey(propertyId, areaId)
        propertyValues[key] = value
        getOrCreateFlow(key).emit(value)
    }

    private fun getOrCreateFlow(key: PropertyKey): MutableSharedFlow<Any> = propertyFlows.getOrPut(key) {
        MutableSharedFlow(replay = 1, extraBufferCapacity = 1)
    }

    private data class PropertyKey(
        val propertyId: Int,
        val areaId: Int,
    )
}

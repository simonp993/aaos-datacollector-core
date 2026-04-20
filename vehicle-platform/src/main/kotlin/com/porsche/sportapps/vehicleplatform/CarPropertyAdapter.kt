package com.porsche.sportapps.vehicleplatform

import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.PropertyNotAvailableException
import android.content.Context
import com.porsche.sportapps.core.logging.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class CarPropertyAdapter internal constructor(
    private val carPropertyManager: CarPropertyManager,
    private val logger: Logger,
) : VhalPropertyService {
    init {
        logger.i(TAG, "CarPropertyAdapter created with CarPropertyManager")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observeProperty(
        propertyId: Int,
        areaId: Int,
    ): Flow<T> = callbackFlow {
        val available = carPropertyManager.propertyList.any { it.propertyId == propertyId }
        if (!available) {
            logger.w(TAG, "Property $propertyId not in propertyList — closing flow")
            close(IllegalArgumentException("Property $propertyId not available or access not granted"))
            return@callbackFlow
        }

        val callback =
            object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(carPropertyValue: CarPropertyValue<*>) {
                    if (carPropertyValue.areaId != areaId) return
                    val value = carPropertyValue.value as? T
                    if (value != null) {
                        trySend(value)
                    }
                }

                override fun onErrorEvent(
                    propertyId: Int,
                    zone: Int,
                ) {
                    logger.e(TAG, "onErrorEvent for propertyId=$propertyId, zone=$zone")
                    close(
                        RuntimeException(
                            "VHAL error for propertyId=$propertyId, zone=$zone",
                        ),
                    )
                }
            }

        carPropertyManager.registerCallback(
            callback,
            propertyId,
            CarPropertyManager.SENSOR_RATE_ONCHANGE,
        )

        // Emit current value immediately so flows don't hang waiting for ON_CHANGE
        try {
            val currentValue = carPropertyManager.getProperty<T>(propertyId, areaId)
            if (currentValue != null && currentValue.status == CarPropertyValue.STATUS_AVAILABLE) {
                val value = currentValue.value as? T
                if (value != null) {
                    trySend(value)
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.w(TAG, "Failed to read initial value for propertyId=$propertyId", e)
        }

        awaitClose {
            carPropertyManager.unregisterCallback(callback)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> readProperty(
        propertyId: Int,
        areaId: Int,
    ): T? = try {
        carPropertyManager.getProperty<T>(propertyId, areaId)?.value as? T
    } catch (_: PropertyNotAvailableException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    companion object {
        private const val TAG = "CarPropertyAdapter"

        fun create(
            context: Context,
            logger: Logger,
        ): CarPropertyAdapter {
            val car =
                Car.createCar(context)
                    ?: error("Car.createCar() returned null")
            val carPropertyManager =
                car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            return CarPropertyAdapter(carPropertyManager, logger)
        }
    }
}

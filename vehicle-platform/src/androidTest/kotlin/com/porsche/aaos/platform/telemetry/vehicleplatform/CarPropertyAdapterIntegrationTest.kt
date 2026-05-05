package com.porsche.aaos.platform.telemetry.vehicleplatform

import android.car.Car
import android.car.hardware.property.CarPropertyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarPropertyAdapterIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val noOpLogger =
        object : Logger {
            override fun d(
                tag: String,
                message: String,
            ) {}

            override fun i(
                tag: String,
                message: String,
            ) {}

            override fun w(
                tag: String,
                message: String,
            ) {}

            override fun w(
                tag: String,
                message: String,
                throwable: Throwable,
            ) {}

            override fun e(
                tag: String,
                message: String,
            ) {}

            override fun e(
                tag: String,
                message: String,
                throwable: Throwable,
            ) {}
        }

    private fun createCarPropertyManager(): CarPropertyManager {
        val car = Car.createCar(context)!!
        return car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
    }

    @Test
    fun carCreateCarReturnsNonNull() {
        val car = Car.createCar(context)
        assertNotNull("Car.createCar() must return non-null on AAOS", car)
    }

    @Test
    fun carPropertyManagerIsObtainable() {
        val manager = createCarPropertyManager()
        assertNotNull("CarPropertyManager must be obtainable", manager)
    }

    @Test
    fun adapterConstructsSuccessfully() {
        val adapter = CarPropertyAdapter.create(context, noOpLogger)
        assertNotNull("CarPropertyAdapter must construct without error", adapter)
    }

    @Test
    fun readPropertyReturnsNullForUnknownProperty() {
        val adapter = CarPropertyAdapter.create(context, noOpLogger)
        // Property ID 0 should not exist — readProperty should return null, not crash
        val result = adapter.readProperty<Int>(0)
        assertTrue("readProperty for unknown ID should return null", result == null)
    }

    @Test
    fun readMmtrPropertyDoesNotCrash() {
        val adapter = CarPropertyAdapter.create(context, noOpLogger)
        // On emulator this may return null (property unavailable or lacking permission)
        // or a default value. The key assertion is that it doesn't throw.
        val result = adapter.readProperty<Int>(VhalPropertyIds.PORSCHE_DIAG_MMTR_AVAILABLE)
        // result is nullable — either null or an integer, both are acceptable
        assertTrue(
            "readProperty should return null or Int, got: $result",
            result == null || result is Int,
        )
    }
}

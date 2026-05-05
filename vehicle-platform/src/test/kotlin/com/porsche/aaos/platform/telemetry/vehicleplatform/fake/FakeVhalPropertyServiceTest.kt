package com.porsche.aaos.platform.telemetry.vehicleplatform.fake

import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FakeVhalPropertyServiceTest {
    private val service = FakeVhalPropertyService()

    @Test
    fun `observeProperty emits values`() = runTest {
        service.observeProperty<Int>(PROPERTY_ID).test {
            service.emit(PROPERTY_ID, 42)
            assertEquals(42, awaitItem())

            service.emit(PROPERTY_ID, 100)
            assertEquals(100, awaitItem())
        }
    }

    @Test
    fun `readProperty returns null when no value emitted`() {
        assertNull(service.readProperty<Int>(PROPERTY_ID))
    }

    @Test
    fun `readProperty returns last emitted value`() = runTest {
        service.emit(PROPERTY_ID, "hello")
        assertEquals("hello", service.readProperty<String>(PROPERTY_ID))
    }

    @Test
    fun `different areaIds are independent`() = runTest {
        service.emit(PROPERTY_ID, 10, areaId = 1)
        service.emit(PROPERTY_ID, 20, areaId = 2)

        assertEquals(10, service.readProperty<Int>(PROPERTY_ID, areaId = 1))
        assertEquals(20, service.readProperty<Int>(PROPERTY_ID, areaId = 2))
    }

    @Test
    fun `observeProperty with different areaIds receives independent emissions`() = runTest {
        service.observeProperty<Int>(PROPERTY_ID, areaId = 1).test {
            service.emit(PROPERTY_ID, 42, areaId = 1)
            assertEquals(42, awaitItem())
        }
    }

    @Test
    fun `readProperty returns null for unset areaId`() {
        assertNull(service.readProperty<Int>(PROPERTY_ID, areaId = 99))
    }

    companion object {
        private const val PROPERTY_ID = 0x1160_0001
    }
}

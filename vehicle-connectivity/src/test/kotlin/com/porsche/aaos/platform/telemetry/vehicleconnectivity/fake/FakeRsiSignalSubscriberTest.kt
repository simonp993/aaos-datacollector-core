package com.porsche.aaos.platform.telemetry.vehicleconnectivity.fake

import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.SignalQuality
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.SignalValue
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FakeRsiSignalSubscriberTest {
    private val subscriber = FakeRsiSignalSubscriber()

    @Test
    fun `subscribeToSignal emits values`() = runTest {
        subscriber.subscribeToSignal<Int>(SIGNAL_URL).test {
            val signal = SignalValue(value = 42, quality = SignalQuality.VALID, timestamp = 1000L)
            subscriber.emit(SIGNAL_URL, signal)
            assertEquals(signal, awaitItem())
        }
    }

    @Test
    fun `different signal URLs are independent`() = runTest {
        subscriber.subscribeToSignal<Int>(SIGNAL_URL).test {
            val signal = SignalValue(value = 42, quality = SignalQuality.VALID, timestamp = 1000L)
            subscriber.emit(SIGNAL_URL, signal)
            assertEquals(signal, awaitItem())
        }
    }

    @Test
    fun `emits INVALID quality signal`() = runTest {
        subscriber.subscribeToSignal<String>(SIGNAL_URL).test {
            val signal =
                SignalValue(
                    value = "error",
                    quality = SignalQuality.INVALID,
                    timestamp = 2000L,
                )
            subscriber.emit(SIGNAL_URL, signal)
            assertEquals(signal, awaitItem())
        }
    }

    @Test
    fun `emits NOT_AVAILABLE quality signal`() = runTest {
        subscriber.subscribeToSignal<Double>(SIGNAL_URL).test {
            val signal =
                SignalValue(
                    value = 0.0,
                    quality = SignalQuality.NOT_AVAILABLE,
                    timestamp = 3000L,
                )
            subscriber.emit(SIGNAL_URL, signal)
            assertEquals(signal, awaitItem())
        }
    }

    @Test
    fun `emits multiple signals in order`() = runTest {
        subscriber.subscribeToSignal<Int>(SIGNAL_URL).test {
            val s1 = SignalValue(value = 1, quality = SignalQuality.VALID, timestamp = 100L)
            val s2 = SignalValue(value = 2, quality = SignalQuality.VALID, timestamp = 200L)
            subscriber.emit(SIGNAL_URL, s1)
            subscriber.emit(SIGNAL_URL, s2)
            assertEquals(s1, awaitItem())
            assertEquals(s2, awaitItem())
        }
    }

    companion object {
        private const val SIGNAL_URL = "/vehicle/speed"
    }
}

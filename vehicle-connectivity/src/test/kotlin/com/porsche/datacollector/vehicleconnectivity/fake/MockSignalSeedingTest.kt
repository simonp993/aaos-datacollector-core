package com.porsche.datacollector.vehicleconnectivity.fake

import com.porsche.datacollector.vehicleconnectivity.asi.ConnectionState
import com.porsche.datacollector.vehicleconnectivity.rsi.SignalQuality
import com.porsche.datacollector.vehicleconnectivity.rsi.SignalValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that mock signal seeding (per mmtr-mock-data spec) correctly
 * populates FakeRsiSignalSubscriber so late subscribers receive seeded values.
 */
class MockSignalSeedingTest {
    @Test
    fun `seeded temperature current value is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Double>("CCL://mmtrCurrentValue").test {
            val item = awaitItem()
            assertEquals(45.0, item.value)
            assertEquals(SignalQuality.VALID, item.quality)
        }
    }

    @Test
    fun `seeded temperature max value is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Double>("CCL://mmtrMaxValue").test {
            assertEquals(55.0, awaitItem().value)
        }
    }

    @Test
    fun `seeded temperature min value is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Double>("CCL://mmtrMinValue").test {
            assertEquals(25.0, awaitItem().value)
        }
    }

    @Test
    fun `seeded unit value is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Int>("CCL://mmtrUnit").test {
            assertEquals(0, awaitItem().value)
        }
    }

    @Test
    fun `seeded restriction reason is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Int>("CCL://mmtrRestrictionReason").test {
            assertEquals(0, awaitItem().value)
        }
    }

    @Test
    fun `seeded value indication key is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Int>("CCL://mmtrValueIndicationKey").test {
            assertEquals(0, awaitItem().value)
        }
    }

    @Test
    fun `seeded conditioning state is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Int>("CCL://mmtrConditioningState").test {
            assertEquals(0, awaitItem().value)
        }
    }

    @Test
    fun `seeded conditioning progress is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Int>("CCL://mmtrConditioningProgress").test {
            assertEquals(0, awaitItem().value)
        }
    }

    @Test
    fun `seeded race mode state is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Int>("CCL://mmtrRaceModeState").test {
            assertEquals(0, awaitItem().value)
        }
    }

    @Test
    fun `seeded battery SOC is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Double>("CCL://currentBatteryCharge/thev").test {
            assertEquals(80.0, awaitItem().value)
        }
    }

    @Test
    fun `seeded target SOC is replayed to late subscribers`() = runTest {
        val subscriber = createSeededSubscriber()

        subscriber.subscribeToSignal<Double>("CCL://targetSOC").test {
            assertEquals(60.0, awaitItem().value)
        }
    }

    @Test
    fun `all seeded signals have VALID quality`() = runTest {
        val subscriber = createSeededSubscriber()

        val urls =
            listOf(
                "CCL://mmtrCurrentValue",
                "CCL://mmtrMaxValue",
                "CCL://mmtrMinValue",
                "CCL://mmtrUnit",
                "CCL://mmtrRestrictionReason",
                "CCL://mmtrValueIndicationKey",
                "CCL://mmtrConditioningState",
                "CCL://mmtrConditioningProgress",
                "CCL://mmtrRaceModeState",
                "CCL://currentBatteryCharge/thev",
                "CCL://targetSOC",
            )

        for (url in urls) {
            subscriber.subscribeToSignal<Any>(url).test {
                assertEquals(SignalQuality.VALID, awaitItem().quality, "Quality mismatch for $url")
            }
        }
    }

    @Test
    fun `FakeAsiServiceConnector set to CONNECTED matches mock module behavior`() = runTest {
        val connector =
            FakeAsiServiceConnector().apply {
                setConnectionState(ConnectionState.CONNECTED)
            }

        connector.observeConnectionState().test {
            assertEquals(ConnectionState.CONNECTED, awaitItem())
        }
    }

    /**
     * Creates a FakeRsiSignalSubscriber seeded with the same values as
     * MockVehicleConnectivityModule to verify late-subscriber replay.
     */
    private fun createSeededSubscriber(): FakeRsiSignalSubscriber = FakeRsiSignalSubscriber().apply {
        runBlocking {
            val ts = System.currentTimeMillis()
            emit("CCL://mmtrCurrentValue", SignalValue(45.0, SignalQuality.VALID, ts))
            emit("CCL://mmtrMaxValue", SignalValue(55.0, SignalQuality.VALID, ts))
            emit("CCL://mmtrMinValue", SignalValue(25.0, SignalQuality.VALID, ts))
            emit("CCL://mmtrUnit", SignalValue(0, SignalQuality.VALID, ts))
            emit("CCL://mmtrRestrictionReason", SignalValue(0, SignalQuality.VALID, ts))
            emit("CCL://mmtrValueIndicationKey", SignalValue(0, SignalQuality.VALID, ts))
            emit("CCL://mmtrConditioningState", SignalValue(0, SignalQuality.VALID, ts))
            emit("CCL://mmtrConditioningProgress", SignalValue(0, SignalQuality.VALID, ts))
            emit("CCL://mmtrRaceModeState", SignalValue(0, SignalQuality.VALID, ts))
            emit("CCL://currentBatteryCharge/thev", SignalValue(80.0, SignalQuality.VALID, ts))
            emit("CCL://targetSOC", SignalValue(60.0, SignalQuality.VALID, ts))
        }
    }
}

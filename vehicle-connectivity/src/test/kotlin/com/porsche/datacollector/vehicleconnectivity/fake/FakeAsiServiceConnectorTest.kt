package com.porsche.datacollector.vehicleconnectivity.fake

import com.porsche.datacollector.vehicleconnectivity.asi.ConnectionState
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FakeAsiServiceConnectorTest {
    private val connector = FakeAsiServiceConnector()

    @Test
    fun `initial state is DISCONNECTED`() = runTest {
        connector.observeConnectionState().test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())
        }
    }

    @Test
    fun `connect transitions to CONNECTED`() = runTest {
        connector.observeConnectionState().test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            connector.connect()
            assertEquals(ConnectionState.CONNECTED, awaitItem())
        }
    }

    @Test
    fun `disconnect transitions to DISCONNECTED`() = runTest {
        connector.connect()

        connector.observeConnectionState().test {
            assertEquals(ConnectionState.CONNECTED, awaitItem())

            connector.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())
        }
    }

    @Test
    fun `setConnectionState allows arbitrary state transitions`() = runTest {
        connector.observeConnectionState().test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            connector.setConnectionState(ConnectionState.CONNECTING)
            assertEquals(ConnectionState.CONNECTING, awaitItem())

            connector.setConnectionState(ConnectionState.CONNECTED)
            assertEquals(ConnectionState.CONNECTED, awaitItem())
        }
    }
}

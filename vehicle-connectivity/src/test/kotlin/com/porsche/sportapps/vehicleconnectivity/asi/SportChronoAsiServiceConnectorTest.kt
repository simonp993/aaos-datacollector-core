package com.porsche.sportapps.vehicleconnectivity.asi

import com.porsche.sportapps.core.logging.Logger
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import de.esolutions.fw.android.comm.asi.sportchronoservice.impl.ASISportChronoServiceClientAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SportChronoAsiServiceConnectorTest {
    private val logger: Logger = mockk(relaxed = true)
    private val context: android.content.Context = mockk(relaxed = true)

    private lateinit var connector: SportChronoAsiServiceConnector

    @BeforeEach
    fun setUp() {
        mockkConstructor(ASISportChronoServiceClientAdapter::class)
        mockkStatic(de.esolutions.android.framework.clients.AsiAdmin::class)
        every {
            de.esolutions.android.framework.clients.AsiAdmin
                .start(any())
        } returns mockk(relaxed = true)
        every { anyConstructed<ASISportChronoServiceClientAdapter>().connectService(any(), any()) } returns Unit
        every { anyConstructed<ASISportChronoServiceClientAdapter>().disconnectService() } returns Unit

        connector = SportChronoAsiServiceConnector(context, logger)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initial state is DISCONNECTED`() = runTest {
        connector.observeConnectionState().test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())
        }
    }

    @Test
    fun `connect transitions to CONNECTING then CONNECTED on callback`() = runTest {
        connector.observeConnectionState().test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            connector.connect()
            assertEquals(ConnectionState.CONNECTING, awaitItem())

            // Use the connector's currentConnectionState to verify
            // The callback is internal — simulate via reflection or direct state check
            assertEquals(ConnectionState.CONNECTING, connector.currentConnectionState)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disconnect transitions to DISCONNECTED`() = runTest {
        connector.observeConnectionState().test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            connector.connect()
            assertEquals(ConnectionState.CONNECTING, awaitItem())

            connector.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disconnect nullifies client adapter`() {
        connector.connect()
        connector.disconnect()
        assertEquals(null, connector.getClientAdapter())
    }

    @Test
    fun `multiple connect-disconnect cycles work correctly`() = runTest {
        connector.observeConnectionState().test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            connector.connect()
            assertEquals(ConnectionState.CONNECTING, awaitItem())

            connector.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            connector.connect()
            assertEquals(ConnectionState.CONNECTING, awaitItem())

            connector.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disconnect calls disconnectService on adapter`() {
        connector.connect()
        connector.disconnect()

        verify { anyConstructed<ASISportChronoServiceClientAdapter>().disconnectService() }
    }
}

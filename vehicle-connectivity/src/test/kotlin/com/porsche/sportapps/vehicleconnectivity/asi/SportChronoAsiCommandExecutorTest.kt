package com.porsche.sportapps.vehicleconnectivity.asi

import com.porsche.sportapps.core.logging.Logger
import kotlinx.coroutines.test.runTest
import de.esolutions.fw.android.comm.asi.sportchronoservice.IASISportChronoServiceServiceC
import de.esolutions.fw.android.comm.asi.sportchronoservice.impl.ASISportChronoServiceClientAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SportChronoAsiCommandExecutorTest {
    private val logger: Logger = mockk(relaxed = true)
    private val connector: SportChronoAsiServiceConnector = mockk(relaxed = true)
    private val clientAdapter: ASISportChronoServiceClientAdapter = mockk(relaxed = true)
    private val api: IASISportChronoServiceServiceC = mockk(relaxed = true)

    private val executor = SportChronoAsiCommandExecutor(connector, logger)

    @Test
    fun `execute succeeds when connected and command dispatches`() = runTest {
        every { connector.currentConnectionState } returns ConnectionState.CONNECTED
        every { connector.getClientAdapter() } returns clientAdapter
        every { clientAdapter.api() } returns api

        val command =
            AsiCommand(
                serviceId = "SportChronoService",
                operationId = "setRaceConditioning",
                payload = byteArrayOf(1),
            )

        val result = executor.execute(command)
        assertTrue(result.isSuccess)
        verify { api.setRaceConditioning(true) }
    }

    @Test
    fun `execute rejects when not connected`() = runTest {
        every { connector.currentConnectionState } returns ConnectionState.DISCONNECTED

        val command =
            AsiCommand(
                serviceId = "SportChronoService",
                operationId = "setRaceConditioning",
                payload = byteArrayOf(1),
            )

        val result = executor.execute(command)
        assertTrue(result.isFailure)
        assertInstanceOf(CommandRejectedException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `execute rejects when connecting`() = runTest {
        every { connector.currentConnectionState } returns ConnectionState.CONNECTING

        val command =
            AsiCommand(
                serviceId = "SportChronoService",
                operationId = "setRaceConditioning",
            )

        val result = executor.execute(command)
        assertTrue(result.isFailure)
        assertInstanceOf(CommandRejectedException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `execute rejects when adapter is null`() = runTest {
        every { connector.currentConnectionState } returns ConnectionState.CONNECTED
        every { connector.getClientAdapter() } returns null

        val command =
            AsiCommand(
                serviceId = "SportChronoService",
                operationId = "setRaceConditioning",
                payload = byteArrayOf(1),
            )

        val result = executor.execute(command)
        assertTrue(result.isFailure)
        assertInstanceOf(CommandRejectedException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `execute retries once on first failure then succeeds`() = runTest {
        every { connector.currentConnectionState } returns ConnectionState.CONNECTED
        every { connector.getClientAdapter() } returns clientAdapter
        every { clientAdapter.api() } returns api

        var callCount = 0
        every { api.setRaceConditioning(any()) } answers {
            callCount++
            if (callCount == 1) error("Transient error")
        }

        val command =
            AsiCommand(
                serviceId = "SportChronoService",
                operationId = "setRaceConditioning",
                payload = byteArrayOf(0),
            )

        val result = executor.execute(command)
        assertTrue(result.isSuccess)
        verify(exactly = 2) { api.setRaceConditioning(false) }
    }

    @Test
    fun `execute fails after retry exhausted`() = runTest {
        every { connector.currentConnectionState } returns ConnectionState.CONNECTED
        every { connector.getClientAdapter() } returns clientAdapter
        every { clientAdapter.api() } returns api
        every { api.setRaceConditioning(any()) } throws RuntimeException("Persistent error")

        val command =
            AsiCommand(
                serviceId = "SportChronoService",
                operationId = "setRaceConditioning",
                payload = byteArrayOf(1),
            )

        val result = executor.execute(command)
        assertTrue(result.isFailure)
    }

    @Test
    fun `execute fails for unknown operation`() = runTest {
        every { connector.currentConnectionState } returns ConnectionState.CONNECTED
        every { connector.getClientAdapter() } returns clientAdapter
        every { clientAdapter.api() } returns api

        val command =
            AsiCommand(
                serviceId = "SportChronoService",
                operationId = "unknownOperation",
            )

        val result = executor.execute(command)
        assertTrue(result.isFailure)
        assertInstanceOf(IllegalArgumentException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `setRaceConditioning with empty payload sends false`() = runTest {
        every { connector.currentConnectionState } returns ConnectionState.CONNECTED
        every { connector.getClientAdapter() } returns clientAdapter
        every { clientAdapter.api() } returns api

        val command =
            AsiCommand(
                serviceId = "SportChronoService",
                operationId = "setRaceConditioning",
                payload = ByteArray(0),
            )

        executor.execute(command)
        verify { api.setRaceConditioning(false) }
    }
}

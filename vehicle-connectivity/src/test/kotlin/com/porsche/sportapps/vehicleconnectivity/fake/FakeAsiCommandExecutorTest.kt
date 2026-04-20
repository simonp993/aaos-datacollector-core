package com.porsche.sportapps.vehicleconnectivity.fake

import com.porsche.sportapps.vehicleconnectivity.asi.AsiCommand
import com.porsche.sportapps.vehicleconnectivity.asi.CommandRejectedException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeAsiCommandExecutorTest {
    private val executor = FakeAsiCommandExecutor()

    @Test
    fun `execute succeeds by default`() = runTest {
        val command = AsiCommand(serviceId = "svc", operationId = "op")
        val result = executor.execute(command)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `execute records commands`() = runTest {
        val command = AsiCommand(serviceId = "svc", operationId = "op")
        executor.execute(command)
        assertEquals(listOf(command), executor.getExecutedCommands())
    }

    @Test
    fun `setNextResult makes execute fail`() = runTest {
        executor.setNextResult(Result.failure(CommandRejectedException("rejected")))

        val command = AsiCommand(serviceId = "svc", operationId = "op")
        val result = executor.execute(command)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CommandRejectedException)
    }

    @Test
    fun `clear resets state`() = runTest {
        executor.execute(AsiCommand(serviceId = "svc", operationId = "op"))
        executor.setNextResult(Result.failure(RuntimeException()))

        executor.clear()

        assertTrue(executor.getExecutedCommands().isEmpty())
        assertTrue(executor.execute(AsiCommand(serviceId = "svc2", operationId = "op2")).isSuccess)
    }
}

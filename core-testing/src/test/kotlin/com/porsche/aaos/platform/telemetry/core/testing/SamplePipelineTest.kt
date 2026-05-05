package com.porsche.aaos.platform.telemetry.core.testing

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Sample test verifying the JUnit 5 + Turbine + coroutines-test pipeline works.
 */
class SamplePipelineTest {
    @Test
    fun `turbine receives flow emissions`() = runTest {
        val flow = flowOf("a", "b", "c")
        flow.test {
            assertEquals("a", awaitItem())
            assertEquals("b", awaitItem())
            assertEquals("c", awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `assertEmits helper works for single value`() = runTest {
        flowOf(42).assertEmits(42)
    }

    @Test
    fun `assertEmitsInOrder helper works for multiple values`() = runTest {
        flowOf(1, 2, 3).assertEmitsInOrder(1, 2, 3)
    }
}

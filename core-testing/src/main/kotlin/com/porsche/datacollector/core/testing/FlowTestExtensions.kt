package com.porsche.datacollector.core.testing

// import app.cash.turbine.ReceiveTurbine
import kotlinx.coroutines.flow.Flow
import app.cash.turbine.test

/**
 * Convenience extension for asserting that a Flow emits a single expected value,
 * then cancels remaining events.
 */
suspend fun <T> Flow<T>.assertEmits(expected: T) {
    test {
        val item = awaitItem()
        assert(item == expected) { "Expected $expected but got $item" }
        cancelAndIgnoreRemainingEvents()
    }
}

/**
 * Convenience extension for asserting a Flow emits values in the given order,
 * then cancels remaining events.
 */
suspend fun <T> Flow<T>.assertEmitsInOrder(vararg expected: T) {
    test {
        expected.forEach { value ->
            val item = awaitItem()
            assert(item == value) { "Expected $value but got $item" }
        }
        cancelAndIgnoreRemainingEvents()
    }
}

package com.porsche.datacollector.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * JUnit 5 extension that replaces [Dispatchers.Main] with an
 * [UnconfinedTestDispatcher] for the duration of each test.
 *
 * Usage:
 * ```
 * @ExtendWith(CoroutineTestExtension::class)
 * class MyViewModelTest { ... }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineTestExtension :
    BeforeEachCallback,
    AfterEachCallback {
    private val testDispatcher = UnconfinedTestDispatcher()

    override fun beforeEach(context: ExtensionContext?) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun afterEach(context: ExtensionContext?) {
        Dispatchers.resetMain()
    }
}

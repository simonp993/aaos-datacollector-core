package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import de.esolutions.fw.android.rsi.client.stapi.IRsiAdmin
import de.esolutions.fw.android.rsi.client.stapi.IViwiProxy
import de.esolutions.fw.android.rsi.client.stapi.RsiAdminFactory
import de.esolutions.fw.android.rsi.client.stapi.ViwiProxyFactory
import de.esolutions.fw.util.commons.async.IDisposable
import de.esolutions.fw.util.commons.async.IObservable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExlapRsiSignalSubscriberTest {
    private val context: Context = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val proxy: IViwiProxy = mockk(relaxed = true)
    private val rsiAdmin: IRsiAdmin = mockk(relaxed = true)
    private val connectionDisposable: IDisposable = mockk(relaxed = true)
    private val elementDisposable: IDisposable = mockk(relaxed = true)

    private lateinit var subscriber: ExlapRsiSignalSubscriber
    private val connectionObserverSlot = slot<IObservable.IObserver<IViwiProxy.State>>()
    private val elementObserverSlot = slot<IObservable.IObserver<Bundle>>()

    @BeforeEach
    fun setUp() {
        mockkStatic(RsiAdminFactory::class)
        mockkStatic(ViwiProxyFactory::class)
        mockkStatic(Uri::class)

        every { Uri.parse(any()) } returns mockk(relaxed = true)
        every { RsiAdminFactory.createInstance(any()) } returns rsiAdmin
        every { ViwiProxyFactory.createInstance(any(), any()) } returns proxy

        val connectionObservable = mockk<IObservable<IViwiProxy.State>>()
        every { proxy.connectionStateObservable } returns connectionObservable
        every { connectionObservable.observe(capture(connectionObserverSlot)) } returns connectionDisposable

        val elementObservable = mockk<IObservable<Bundle>>()
        every { proxy.subscribeElement(any(), any()) } returns elementObservable
        every { elementObservable.observe(capture(elementObserverSlot)) } returns elementDisposable

        subscriber = ExlapRsiSignalSubscriber(context, logger)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `emits signal value when RSI proxy delivers valid data`() = runTest {
        subscriber.subscribeToSignal<Float>(SIGNAL_URL).test {
            connectionObserverSlot.captured.onNext(IViwiProxy.State.CONNECTED)

            elementObserverSlot.captured.onNext(bundleWithFloat("floatValue", 42.5f, QUALITY_VALID))

            val result = awaitItem()
            assertEquals(42.5f, result.value)
            assertEquals(SignalQuality.VALID, result.quality)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `maps quality VALID correctly`() = runTest {
        subscriber.subscribeToSignal<Int>(SIGNAL_URL).test {
            connectionObserverSlot.captured.onNext(IViwiProxy.State.CONNECTED)

            elementObserverSlot.captured.onNext(bundleWithInt("intValue", 10, QUALITY_VALID))

            assertEquals(SignalQuality.VALID, awaitItem().quality)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `maps quality INVALID correctly`() = runTest {
        subscriber.subscribeToSignal<Int>(SIGNAL_URL).test {
            connectionObserverSlot.captured.onNext(IViwiProxy.State.CONNECTED)

            elementObserverSlot.captured.onNext(bundleWithInt("intValue", 10, QUALITY_INVALID))

            assertEquals(SignalQuality.INVALID, awaitItem().quality)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `maps unknown quality to NOT_AVAILABLE`() = runTest {
        subscriber.subscribeToSignal<Int>(SIGNAL_URL).test {
            connectionObserverSlot.captured.onNext(IViwiProxy.State.CONNECTED)

            elementObserverSlot.captured.onNext(bundleWithInt("intValue", 10, 99))

            assertEquals(SignalQuality.NOT_AVAILABLE, awaitItem().quality)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disposes subscriptions on flow cancellation`() = runTest {
        subscriber.subscribeToSignal<Float>(SIGNAL_URL).test {
            connectionObserverSlot.captured.onNext(IViwiProxy.State.CONNECTED)
            cancelAndIgnoreRemainingEvents()
        }

        verify { elementDisposable.dispose() }
        verify { connectionDisposable.dispose() }
        verify { proxy.stop() }
    }

    @Test
    fun `closes flow with exception on element subscription error`() = runTest {
        subscriber.subscribeToSignal<Float>(SIGNAL_URL).test {
            connectionObserverSlot.captured.onNext(IViwiProxy.State.CONNECTED)

            val error = RuntimeException("RSI element error")
            elementObserverSlot.captured.onError(error)

            awaitError()
        }
    }

    @Test
    fun `closes flow on proxy connection error`() = runTest {
        subscriber.subscribeToSignal<Float>(SIGNAL_URL).test {
            val error = RuntimeException("Connection lost")
            connectionObserverSlot.captured.onError(error)

            awaitError()
        }
    }

    @Test
    fun `parses double signal values`() = runTest {
        subscriber.subscribeToSignal<Double>(SIGNAL_URL).test {
            connectionObserverSlot.captured.onNext(IViwiProxy.State.CONNECTED)

            elementObserverSlot.captured.onNext(bundleWithDouble("doubleValue", 3.14, QUALITY_VALID))

            assertEquals(3.14, awaitItem().value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `parses boolean signal values`() = runTest {
        subscriber.subscribeToSignal<Boolean>(SIGNAL_URL).test {
            connectionObserverSlot.captured.onNext(IViwiProxy.State.CONNECTED)

            elementObserverSlot.captured.onNext(bundleWithBoolean("booleanValue", true, QUALITY_VALID))

            assertEquals(true, awaitItem().value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun bundleWithFloat(
        key: String,
        value: Float,
        quality: Int,
    ): Bundle = mockk {
        every { containsKey(key) } returns true
        every { containsKey(neq(key)) } returns false
        every { getFloat(key) } returns value
        every { getInt("quality", any()) } returns quality
    }

    private fun bundleWithInt(
        key: String,
        value: Int,
        quality: Int,
    ): Bundle = mockk {
        every { containsKey(key) } returns true
        every { containsKey(neq(key)) } returns false
        every { getInt(key) } returns value
        every { getInt("quality", any()) } returns quality
    }

    private fun bundleWithDouble(
        key: String,
        value: Double,
        quality: Int,
    ): Bundle = mockk {
        every { containsKey(key) } returns true
        every { containsKey(neq(key)) } returns false
        every { getDouble(key) } returns value
        every { getInt("quality", any()) } returns quality
    }

    private fun bundleWithBoolean(
        key: String,
        value: Boolean,
        quality: Int,
    ): Bundle = mockk {
        every { containsKey(key) } returns true
        every { containsKey(neq(key)) } returns false
        every { getBoolean(key) } returns value
        every { getInt("quality", any()) } returns quality
    }

    companion object {
        private const val SIGNAL_URL = "ccl://mmtr/temperature/currentValue"
        private const val QUALITY_VALID = 0
        private const val QUALITY_INVALID = 1
    }
}

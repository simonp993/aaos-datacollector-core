package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import de.esolutions.fw.android.rsi.client.stapi.IViwiProxy
import de.esolutions.fw.android.rsi.client.stapi.RsiAdminFactory
import de.esolutions.fw.android.rsi.client.stapi.ViwiProxyFactory
import de.esolutions.fw.util.commons.async.IDisposable
import de.esolutions.fw.util.commons.async.IObservable

/**
 * Real RSI signal subscriber using the RSI STAPI (IViwiProxy) API.
 * Connects to the vehicle RSI service for continuous telemetry streams.
 *
 * Uses callbackFlow + WhileSubscribed lifecycle pattern (ADR-008):
 * ExLAP subscription established when first collector appears,
 * torn down when last collector disappears.
 */
class ExlapRsiSignalSubscriber(
    private val context: Context,
    private val logger: Logger,
) : RsiSignalSubscriber {

    private val rsiAdmin by lazy { RsiAdminFactory.createInstance(context) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> subscribeToSignal(signalUrl: String): Flow<SignalValue<T>> = callbackFlow {
        val signalUri = Uri.parse(signalUrl)

        logger.d(TAG, "Subscribing to RSI signal: $signalUrl")

        var elementDisposable: IDisposable? = null

        val serviceUri = Uri.parse(signalUrl.substringBefore("//") + "//")
        val proxy = ViwiProxyFactory.createInstance(rsiAdmin, serviceUri)

        val connectionDisposable = proxy.connectionStateObservable.observe(
            object : IObservable.IObserver<IViwiProxy.State> {
                override fun onNext(state: IViwiProxy.State) {
                    when (state) {
                        IViwiProxy.State.CONNECTED -> {
                            logger.d(TAG, "RSI proxy connected for $signalUrl")
                            elementDisposable = proxy.subscribeElement(signalUri, null).observe(
                                object : IObservable.IObserver<Bundle> {
                                    override fun onNext(bundle: Bundle) {
                                        val signalValue = parseSignalValue<T>(bundle)
                                        if (signalValue != null) {
                                            trySend(signalValue)
                                        }
                                    }

                                    override fun onError(throwable: Throwable) {
                                        logger.e(TAG, "RSI element subscription error for $signalUrl", throwable)
                                        close(throwable)
                                    }

                                    override fun onComplete() {
                                        logger.d(TAG, "RSI element subscription completed for $signalUrl")
                                        close()
                                    }
                                },
                            )
                        }

                        IViwiProxy.State.DISCONNECTED -> {
                            logger.w(TAG, "RSI proxy disconnected for $signalUrl")
                            elementDisposable?.dispose()
                            elementDisposable = null
                        }

                        IViwiProxy.State.STOPPED -> {
                            logger.d(TAG, "RSI proxy stopped for $signalUrl")
                            close()
                        }

                        IViwiProxy.State.CREATED -> {
                            logger.d(TAG, "RSI proxy created for $signalUrl")
                        }
                    }
                }

                override fun onError(throwable: Throwable) {
                    logger.e(TAG, "RSI proxy connection error for $signalUrl", throwable)
                    close(throwable)
                }

                override fun onComplete() {
                    close()
                }
            },
        )

        proxy.start()

        awaitClose {
            logger.d(TAG, "Unsubscribing from RSI signal: $signalUrl")
            elementDisposable?.dispose()
            connectionDisposable.dispose()
            proxy.stop()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> parseSignalValue(bundle: Bundle): SignalValue<T>? {
        val quality = mapQuality(bundle)
        val timestamp = System.currentTimeMillis()

        val value: Any? = when {
            bundle.containsKey(BUNDLE_KEY_FLOAT) -> {
                val f = bundle.getFloat(BUNDLE_KEY_FLOAT)
                // Widen Float to Double when caller expects Double
                (f.toDouble() as? T) ?: (f as? T)
            }

            bundle.containsKey(BUNDLE_KEY_DOUBLE) -> {
                val d = bundle.getDouble(BUNDLE_KEY_DOUBLE)
                (d as? T) ?: (d.toFloat() as? T)
            }

            bundle.containsKey(BUNDLE_KEY_INT) -> bundle.getInt(BUNDLE_KEY_INT) as? T

            bundle.containsKey(BUNDLE_KEY_BOOLEAN) -> bundle.getBoolean(BUNDLE_KEY_BOOLEAN) as? T

            bundle.containsKey(BUNDLE_KEY_STRING) -> bundle.getString(BUNDLE_KEY_STRING) as? T

            bundle.containsKey(BUNDLE_KEY_VALUE) -> {
                @Suppress("DEPRECATION")
                val raw = bundle.get(BUNDLE_KEY_VALUE)
                raw as? T
            }

            else -> null
        }

        return if (value != null) {
            @Suppress("UNCHECKED_CAST")
            SignalValue(value as T, quality, timestamp)
        } else {
            null
        }
    }

    private fun mapQuality(bundle: Bundle): SignalQuality {
        val qualityInt = bundle.getInt(BUNDLE_KEY_QUALITY, QUALITY_UNKNOWN)
        return when (qualityInt) {
            QUALITY_VALID -> SignalQuality.VALID
            QUALITY_INVALID -> SignalQuality.INVALID
            else -> SignalQuality.NOT_AVAILABLE
        }
    }

    companion object {
        private const val TAG = "ExlapRsiSignalSubscriber"
        private const val BUNDLE_KEY_FLOAT = "floatValue"
        private const val BUNDLE_KEY_DOUBLE = "doubleValue"
        private const val BUNDLE_KEY_INT = "intValue"
        private const val BUNDLE_KEY_BOOLEAN = "booleanValue"
        private const val BUNDLE_KEY_STRING = "stringValue"
        private const val BUNDLE_KEY_VALUE = "value"
        private const val BUNDLE_KEY_QUALITY = "quality"
        private const val QUALITY_VALID = 0
        private const val QUALITY_INVALID = 1
        private const val QUALITY_UNKNOWN = -1
    }
}

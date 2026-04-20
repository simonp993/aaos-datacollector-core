# Keep RSI STAPI callback interfaces (invoked reflectively by the platform SDK)
-keep class com.porsche.sportapps.vehicleconnectivity.rsi.ExlapRsiSignalSubscriber { *; }
-keep class de.esolutions.fw.util.commons.async.IObservable$IObserver { *; }

# Keep ASI callback interfaces (invoked reflectively by the platform SDK)
-keep class com.porsche.sportapps.vehicleconnectivity.asi.SportChronoAsiServiceConnector { *; }
-keep class de.esolutions.android.framework.clients.IServiceCallback { *; }

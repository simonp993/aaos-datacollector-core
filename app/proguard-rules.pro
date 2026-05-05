# Keep Hilt-generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep collector classes (used via Hilt multibinding)
-keep class com.porsche.aaos.platform.telemetry.collector.** { *; }

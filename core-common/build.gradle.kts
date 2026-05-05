plugins {
    id("datacollector.android-library")
}

android {
    namespace = "com.porsche.aaos.platform.telemetry.core.common"
}

dependencies {
    api(libs.coroutines.core)
}

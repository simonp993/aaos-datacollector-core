plugins {
    id("datacollector.android-library")
}

android {
    namespace = "com.porsche.aaos.platform.telemetry.core.logging"
}

dependencies {
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
}

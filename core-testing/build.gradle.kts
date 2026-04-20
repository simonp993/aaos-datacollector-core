plugins {
    id("datacollector.android-library")
}

android {
    namespace = "com.porsche.datacollector.core.testing"
}

dependencies {
    // Core modules
    api(project(":core-logging"))

    // Expose test dependencies to consumers as API
    api(libs.junit5.api)
    api(libs.junit5.params)
    api(libs.turbine)
    api(libs.mockk)
    api(libs.coroutines.test)

    // Compose testing
    api(platform(libs.compose.bom))
    api(libs.compose.ui.test.junit4)
    api(libs.compose.ui.test.manifest)

    // JUnit 5 engine for runtime
    runtimeOnly(libs.junit5.engine)
    runtimeOnly(libs.junit.platform.launcher)
}

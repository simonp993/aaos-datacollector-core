plugins {
    id("sportapps.android-library")
}

android {
    namespace = "com.porsche.sportapps.core.logging"
}

dependencies {
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
}

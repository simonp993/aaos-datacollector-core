plugins {
    id("datacollector.android-library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.porsche.datacollector.vehicleconnectivity"
}

dependencies {
    // Modules
    implementation(project(":vehicle-platform"))
    implementation(project(":core-logging"))

    // Vehicle platform SDK JARs — compileOnly: available at compile time,
    // bundled only by app module's real variant via runtimeOnly
    compileOnly(fileTree("libs") { include("*.jar") })

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // RxJava (required by RSI RUDI proxy layer)
    implementation(libs.rxjava3)
    implementation(libs.reactive.streams)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Testing
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    // SDK JARs on test classpath for mockk to mock platform SDK classes
    testImplementation(fileTree("libs") { include("*.jar") })
}

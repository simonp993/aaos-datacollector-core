plugins {
    id("datacollector.android-library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.porsche.datacollector.vehicleplatform"
}

dependencies {
    // Porsche vendor VHAL property IDs (constants only — packaged into APK)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("vendor.porsche.*.jar"))))

    // Android Car API (provided by AAOS system at runtime — compile only)
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("car-*.jar"))))

    // Logging
    implementation(project(":core-logging"))

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

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

    // Instrumented testing
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}

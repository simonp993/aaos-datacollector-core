plugins {
    id("datacollector.android-app")
}

android {
    namespace = "com.porsche.datacollector"

    defaultConfig {
        applicationId = "com.porsche.datacollector"
        versionCode = 1
        versionName = "0.1.0"
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/proguard/proguard-fw-additions.pro"
        }
    }
}

dependencies {
    // Modules
    implementation(project(":core-logging"))
    implementation(project(":core-common"))
    implementation(project(":vehicle-platform"))
    implementation(project(":vehicle-connectivity"))

    // Hidden API stubs for InputMonitor / InputChannel — compile-only, resolved at runtime
    compileOnly(files("libs/hidden-api-stubs.jar"))
    // Car API — provided by AAOS system at runtime
    compileOnly(files("${rootProject.projectDir}/vehicle-platform/libs/car-35.0.0.jar"))

    // Vehicle platform SDK JARs — compileOnly in :vehicle-connectivity.
    // RSI STAPI + utility classes are provided by the MIB4 system framework at runtime.
    // ASI SportChrono client/interface JARs must be included at runtime for the real variant.
    "realRuntimeOnly"(
        files(
            "${rootProject.projectDir}/vehicle-connectivity/libs/fw_android_asi_sportchronoservice_client.jar",
            "${rootProject.projectDir}/vehicle-connectivity/libs/fw_android_asi_sportchronoservice_interface.jar",
            "${rootProject.projectDir}/vehicle-connectivity/libs/fw_comm_android_support.jar",
            "${rootProject.projectDir}/vehicle-connectivity/libs/mib_rsi_android.jar",
            "${rootProject.projectDir}/vehicle-connectivity/libs/fw_rudi_android_support.jar",
            "${rootProject.projectDir}/vehicle-connectivity/libs/fw_rsi_rx2_android_support.jar",
        ),
    )

    // AndroidX
    implementation(libs.core.ktx)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Testing
    testImplementation(project(":core-testing"))
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}

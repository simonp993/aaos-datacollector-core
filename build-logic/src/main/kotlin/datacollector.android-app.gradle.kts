import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("io.gitlab.arturbosch.detekt")
    jacoco
}

android {
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += listOf("platform", "datasource")

    productFlavors {
        create("mib4") {
            dimension = "platform"
        }
        // hcp3: vanilla AAOS 13 emulator (aosp-platform.jks)
        create("hcp3") {
            dimension = "platform"
        }
        // hcp3Hw: real HCP3 hardware (hcp3-platform.jks, see hcp3-deployment.md)
        create("hcp3Hw") {
            dimension = "platform"
        }
        create("mock") {
            dimension = "datasource"
        }
        create("real") {
            dimension = "datasource"
        }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties =
        Properties().apply {
            if (keystorePropertiesFile.exists()) {
                keystorePropertiesFile.inputStream().use { load(it) }
            }
        }

    signingConfigs {
        create("platform") {
            storeFile =
                file(
                    keystoreProperties.getProperty(
                        "storeFile",
                        "${project.projectDir}/keystores/aosp-platform.jks",
                    ),
                )
            storePassword = keystoreProperties.getProperty("storePassword", "android")
            keyAlias = keystoreProperties.getProperty("keyAlias", "platform")
            keyPassword = keystoreProperties.getProperty("keyPassword", "android")
        }
        // HCP3 real hardware platform key — configure via keystore.properties (hcp3.* keys).
        // Falls back to aosp-platform.jks if hcp3.storeFile is not set (build succeeds;
        // resulting APK will not install as privileged on real HCP3 hardware).
        create("hcp3Platform") {
            storeFile =
                file(
                    keystoreProperties.getProperty(
                        "hcp3.storeFile",
                        "${project.projectDir}/keystores/aosp-platform.jks",
                    ),
                )
            storePassword = keystoreProperties.getProperty("hcp3.storePassword", "android")
            keyAlias = keystoreProperties.getProperty("hcp3.keyAlias", "platform")
            keyPassword = keystoreProperties.getProperty("hcp3.keyPassword", "android")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("platform")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("platform")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

// Apply the HCP3 hardware signing config to all hcp3Hw variants.
// The hcp3Platform signing config reads from hcp3.* keys in keystore.properties.
val hcp3PlatformSigning = android.signingConfigs.getByName("hcp3Platform")
androidComponents {
    onVariants(selector().withFlavor("platform" to "hcp3Hw")) { variant ->
        variant.signingConfig.setConfig(hcp3PlatformSigning)
    }
}

detekt {
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}

val libs = versionCatalogs.named("libs")
dependencies {
    "detektPlugins"(libs.findLibrary("detekt-formatting").get())
}

tasks.withType<JacocoReport> {
    dependsOn(tasks.matching { it.name.contains("testMib4MockDebugUnitTest") })
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.withType<JacocoReport>())
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

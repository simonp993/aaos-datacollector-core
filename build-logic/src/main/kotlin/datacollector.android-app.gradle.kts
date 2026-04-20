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
        create("nextgen") {
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

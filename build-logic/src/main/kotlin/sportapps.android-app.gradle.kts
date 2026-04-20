import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties
import org.gradle.api.JavaVersion

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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
        compose = true
        buildConfig = true
    }

    flavorDimensions += listOf("stage", "datasource")

    productFlavors {
        create("dev") {
            dimension = "stage"
        }
        create("production") {
            dimension = "stage"
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
                    keystoreProperties.getProperty("storeFile", "${project.projectDir}/keystores/aosp-platform.jks"),
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
    "detektPlugins"(libs.findLibrary("detekt-compose-rules").get())
}

// JaCoCo coverage enforcement: >=80% line coverage (consistent with library modules)
tasks.withType<JacocoReport> {
    dependsOn(tasks.matching { it.name.contains("testDevMockDebugUnitTest") })
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

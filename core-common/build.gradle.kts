plugins {
    id("datacollector.android-library")
}

android {
    namespace = "com.porsche.datacollector.core.common"
}

dependencies {
    api(libs.coroutines.core)
}

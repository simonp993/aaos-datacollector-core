plugins {
    id("sportapps.android-library")
}

android {
    namespace = "com.porsche.sportapps.core.common"
}

dependencies {
    api(libs.coroutines.core)
}

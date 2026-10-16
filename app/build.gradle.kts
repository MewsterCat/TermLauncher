plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mewstercat.termlauncher"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mewstercat.termlauncher"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

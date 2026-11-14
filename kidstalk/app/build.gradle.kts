plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.terriblevikinggames.kidstalk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.terriblevikinggames.kidstalk"
        // API 31 is the floor: setCommunicationDevice() and the BLUETOOTH_CONNECT
        // runtime permission both start there.
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
}

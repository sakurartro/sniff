plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.sniff"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.sniff"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        // CI decodes the key from GitHub Secrets to android/app/release.jks; without it we fall back to the default debug key.
        if (file("release.jks").exists()) create("ci") {
            storeFile = file("release.jks")
            storePassword = System.getenv("SIGN_PASSWORD"); keyAlias = "tgkiku"; keyPassword = System.getenv("SIGN_PASSWORD")
        }
    }
    buildTypes { getByName("debug") { signingConfigs.findByName("ci")?.let { signingConfig = it } } }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("net.java.dev.jna:jna:5.14.0@aar") // UniFFI bindings load the Rust .so through JNA
    implementation("com.google.zxing:core:3.5.3")
}

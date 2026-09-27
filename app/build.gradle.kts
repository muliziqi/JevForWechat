plugins {
    id("com.android.application")
}

android {
    namespace = "dev.jev.wechat"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.jev.wechat"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-a1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Provided by LSPatch / Vector at runtime; do not package it into the APK.
    compileOnly("io.github.libxposed:api:102.0.0")
}

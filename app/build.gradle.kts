plugins {
    id("com.android.application")
}

android {
    namespace = "com.rideledger"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.rideledger"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.2.2"
    }

    signingConfigs {
        create("testing") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            if (rootProject.file("signing/debug.keystore").isFile) {
                signingConfig = signingConfigs.getByName("testing")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.4")
}


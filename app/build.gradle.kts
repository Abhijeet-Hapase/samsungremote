plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.abhijeet.tvremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abhijeet.tvremote"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // One shared key so every build (local or CI) installs as an update over the previous one.
    signingConfigs {
        create("shared") {
            storeFile = file("remote.keystore")
            storePassword = "android"
            keyAlias = "remote"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

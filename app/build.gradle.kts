plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.janick.cyclus"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.janick.cyclus"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // Vaste sleutel: updates gaan over de oude versie heen en de gegevens blijven staan.
    signingConfigs {
        create("vast") {
            storeFile = file("cyclus.jks")
            storePassword = "cycluscyclus"
            keyAlias = "cyclus"
            keyPassword = "cycluscyclus"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("vast")
        }
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
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core:1.13.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.droidstream.host"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "org.droidstream.host"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-m0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { getByName("debug") { isMinifyEnabled = false } }
}

dependencies { implementation(kotlin("stdlib")) }
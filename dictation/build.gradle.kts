plugins {
    id("com.android.library")
}

android {
    namespace = "app.reventor.dictation"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        ndk {
            // the needle engine ships as a static lib for arm64-v8a; other ABIs
            // simply have no dictation (NeedleNative.available == false → legacy fallback)
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // prebuilt libneedle.a references symbols (e.g. stderr) that only exist
                // at API 23+; on older arm64 devices lib load fails and is caught
                arguments += "-DANDROID_PLATFORM=android-24"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = File("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.jetinnowakeword"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.jetinnowakeword"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        ndk {
            // O Vosk 0.3.47 fornece exatamente estas quatro ABIs.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.alphacephei:vosk-android:0.3.47")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

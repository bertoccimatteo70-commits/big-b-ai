plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bigbai.llm"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // One native engine per shipped ABI: 64-bit ARM, 32-bit ARM and the
            // x86_64 Android emulator. A missing ABI would make the app crash on
            // that architecture with UnsatisfiedLinkError.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// Release signing is optional. When no keystore is configured the release
// variant falls back to the debug key so that a verifiable, installable APK can
// always be produced; the build script and the workflow state this explicitly.
val signingStoreFile = System.getenv("SIGNING_STORE_FILE")
    ?: keystoreProperties.getProperty("storeFile")
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    ?: keystoreProperties.getProperty("storePassword")
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    ?: keystoreProperties.getProperty("keyAlias")
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    ?: keystoreProperties.getProperty("keyPassword")

val hasReleaseSigning = signingStoreFile != null &&
    signingStorePassword != null &&
    signingKeyAlias != null &&
    signingKeyPassword != null &&
    file(signingStoreFile).exists()

android {
    namespace = "com.bigbai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bigbai.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "it")
        // Universal debug APK: every supported architecture gets its own native
        // engine. arm64-v8a is the primary target, armeabi-v7a covers 32-bit ARM
        // devices and x86_64 makes the app usable in the Android emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false // v1: no unverifiable R8 passes on a device-less build host
            isShrinkResources = false
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
            "META-INF/*.kotlin_module",
        )
        jniLibs.useLegacyPackaging = false
    }
    androidResources {
        generateLocaleConfig = false
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.room.runtime)
    implementation(project(":llm"))
    implementation(project(":code"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

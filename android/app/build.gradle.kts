// =============================================================================
// MedTrack Android – app/build.gradle.kts
// Package: com.joechrist.medtrack
// =============================================================================

import java.util.Properties as JavaProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace   = "com.joechrist.medtrack"
    compileSdk  = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.joechrist.medtrack"
        minSdk        = libs.versions.minSdk.get().toInt()
        targetSdk     = libs.versions.targetSdk.get().toInt()
        versionCode   = 1
        versionName   = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject backend URL from local.properties or environment
        val localProperties = JavaProperties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.inputStream().use { stream ->
                localProperties.load(stream)
            }
        }

        val apiBaseUrl = localProperties.getProperty("ANDROID_API_BASE_URL=https://medtrack-api.onrender.com")
            ?: System.getenv("ANDROID_API_BASE_URL")
            ?: ""

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "APP_VERSION",  "\"$versionName\"")
    }

    buildTypes {
        debug {
            isDebuggable   = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled  = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // java.time backport for API 26+
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // ── Compose ────────────────────────────────────────────────────────────────
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.splashscreen)
    debugImplementation(libs.compose.ui.tooling)

    // ── Hilt ──────────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // ── Firebase ──────────────────────────────────────────────────────────────
    implementation(platform(libs.firebase.bom))
    implementation(libs.bundles.firebase)
    implementation(libs.google.services.auth)

    // ── Network ───────────────────────────────────────────────────────────────
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // ── Room ──────────────────────────────────────────────────────────────────
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)

    // ── Camera ────────────────────────────────────────────────────────────────
    implementation(libs.bundles.camera)

    // ── WorkManager + Hilt ────────────────────────────────────────────────────
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // ── Coil ──────────────────────────────────────────────────────────────────
    implementation(libs.coil.compose)

    // ── DataStore ────────────────────────────────────────────────────────────
    implementation(libs.datastore.preferences)

    // ── Accompanist ────────────────────────────────────────────────────────────
    implementation(libs.accompanist.permissions)

    // ── Tests ─────────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

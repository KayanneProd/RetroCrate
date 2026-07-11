import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Read keys from local.properties (gitignored). Empty string fallback so the project still
// builds on a fresh clone without the key — runtime calls just won't enrich until set.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val theGamesDbApiKey: String = localProps.getProperty("THEGAMESDB_API_KEY", "")

android {
    namespace = "com.kayanne.retrocrate"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.kayanne.retrocrate"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "2.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "THEGAMESDB_API_KEY", "\"$theGamesDbApiKey\"")
    }

    buildTypes {
        release {
            // Personal app, never on the Play Store. Sign release with the debug key so it installs
            // as an in-place update over the debug builds already on the device (same signature).
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.sizes)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // DataStore + SAF helpers
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    // WebView hardening (document-start script injection) for the DDL ad-gate flow
    implementation(libs.androidx.webkit)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // Scraping
    implementation(libs.jsoup)

    // Archive extraction (unpack ROMs from .tar.gz / .zip / .7z downloads)
    implementation(libs.commons.compress)
    implementation(libs.xz) // LZMA/LZMA2 codec for 7z (Vimm's serves disc games as .7z)
    implementation(libs.sevenzipjbinding) // native 7-Zip engine for .rar (RAR5; Switch/Wii U scene releases)

    // Imaging
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

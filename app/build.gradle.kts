// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "au.kidfin.tv"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "au.kidfin.tv"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    // Release signing comes from Gradle properties (for example ~/.gradle/gradle.properties) or
    // environment variables, never from files in the repository.
    fun signingValue(name: String): String? =
        providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull
    val releaseKeystore = signingValue("KIDFIN_KEYSTORE")

    signingConfigs {
        if (releaseKeystore != null) {
            create("kidfinRelease") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("KIDFIN_KEYSTORE_PASSWORD")
                keyAlias = signingValue("KIDFIN_KEY_ALIAS")
                keyPassword = signingValue("KIDFIN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("kidfinRelease")
            } else {
                // LOCAL BUILDS ONLY: with no release key configured, fall back to the debug key so
                // `assembleRelease` works and installs over debug builds. Never distribute such
                // an APK; configure KIDFIN_KEYSTORE (see README) for anything you share.
                logger.warn("KIDFIN_KEYSTORE not set: signing the release build with the debug key. Do not distribute it.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.jellyfin.core)
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    implementation(libs.palette)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.boophis.player"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core-model"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    api(libs.media3.exoplayer)
    api(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg)
    implementation(libs.androidx.lifecycle.service)
    testImplementation(libs.junit)
}

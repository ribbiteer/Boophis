// SPDX-License-Identifier: GPL-3.0-or-later
// Legacy Android Auto projection entry point. Links Google's proprietary Android Auto SDK
// (libs/aauto.aar, not committed — see tools/fetch-aauto.sh). The app's Android Auto entry point.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.boophis.car.projection"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":car"))
    implementation(files("libs/aauto.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
}

// This module is only ever consumed by :app inside this build; it is never published as an AAR.
// AGP refuses to bundle an AAR around a local .aar, so the bundle tasks (run by a root `assemble`)
// (bundleDebugAar / bundleReleaseAar) are switched off. The lint bundles (bundle*LocalLintAar) stay on:
// :app's release lint reads them.
tasks.matching { it.name.matches(Regex("bundle(Debug|Release)Aar")) }.configureEach { enabled = false }

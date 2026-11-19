// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Local release signing: keystore.properties (never committed) next to this file's parent.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.boophis"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.ribbiteer.boophis"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // Same key as release so debug/release builds can replace each other in place.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Screenshots with the fixture library, beside the installed app and without touching
            // its library or watch state: ./gradlew assembleDebug -Pboophis.idSuffix=.shots
            (project.findProperty("boophis.idSuffix") as String?)?.let { applicationIdSuffix = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-library"))
    implementation(project(":core-player"))
    implementation(project(":ui"))
    implementation(project(":car"))
    // The Android Auto entry point. Needs Google's proprietary aauto.aar (tools/fetch-aauto.sh);
    // without it the app builds and runs on the phone but has no car entry.
    if (rootProject.findProject(":car-projection") != null) {
        implementation(project(":car-projection"))
    }
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

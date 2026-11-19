// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "boophis"
include(":app", ":core-model", ":core-library", ":core-player", ":ui", ":car")
// Android Auto entry point (legacy projection): needs Google's proprietary aauto.aar (tools/fetch-aauto.sh).
if (file("car-projection/libs/aauto.aar").exists()) include(":car-projection")

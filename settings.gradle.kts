pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.2.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
        id("org.jetbrains.kotlin.android") version "2.2.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AETHRA"
include(":app")
include(":core")

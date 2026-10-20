pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google() // androidx artifacts referenced by the upstream library POM
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "cloud-stream-web"

include(":plugin-runtime")
include(":server")

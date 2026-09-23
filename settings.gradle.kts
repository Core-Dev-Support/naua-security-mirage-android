pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven { url = java.net.URI("https://jitpack.io") }
        maven { url = java.net.URI("https://nexus-external.rustore.ru/repository/maven-rustore-exposed") }
    }
}

rootProject.name = "NAUA Security Mirage"
include(":app")
include(":desktop")

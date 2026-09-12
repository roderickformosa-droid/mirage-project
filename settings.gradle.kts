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
        // OpenCV for Android is published here (org.opencv:opencv:4.x)
        maven { url = uri("https://maven.google.com") }
    }
}

rootProject.name = "MirageFieldTester"
include(":app")

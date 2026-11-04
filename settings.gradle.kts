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
        // Tesseract4Android, the on-device text reader. Nothing else may come from here.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.adaptech-cz.Tesseract4Android") }
        }
    }
}
rootProject.name = "zella-android"
include(":app", ":zella-core", ":zella-ui", ":zella-render", ":zella-ai", ":zella-whisper")

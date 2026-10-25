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
        // sherpa-onnx (auto captions' speech engine) is published there only.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.k2-fsa.*") }
        }
    }
}

rootProject.name = "Falak"
include(":app")

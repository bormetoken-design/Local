pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "AlphaNew"

include(":core-model")
include(":core-database")
include(":core-supervisor")
include(":core-projects")
include(":core-proxy")
include(":core-packages")
include(":core-backup")
include(":core-antikill")
include(":ui")
include(":app")

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
        if (providers.gradleProperty("useGoogleMirror").orNull == "true") {
            maven("https://maven.aliyun.com/repository/central") {
                content {
                    includeGroupByRegex("com\\.squareup\\..*")
                    includeGroupByRegex("org\\.jetbrains.*")
                    includeGroup("org.json")
                }
            }
            maven("https://maven.aliyun.com/repository/google") {
                content {
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\.testing\\.platform.*")
                }
            }
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "AIAccounting"
include(":app")

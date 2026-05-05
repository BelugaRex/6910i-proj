val metaGithubToken = (
    providers.gradleProperty("github_token").orNull
        ?: providers.environmentVariable("GITHUB_TOKEN").orNull
        ?: ""
).trim()
val hasMetaDatAccess = metaGithubToken.isNotEmpty()

pluginManagement {
    // Provide Universal Glasses build logic (RayNeo host generator + wiring plugin)
    // Replaced by xg-glass init: ../xg-glass-sdk/build-logic
    includeBuild("../xg-glass-sdk/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Generates + includes :ug_rayneo_glass_host under build/ (no manual glass_app module needed)
    id("com.universalglasses.rayneo.settings")
}

dependencyResolutionManagement {
    // Prefer settings repositories so Flutter/Rokid/Android deps resolve consistently.
    // (Flutter's plugin may add project-level repos; Gradle will warn but settings repos win.)
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
        // Keep Rokid repo scoped; it does not necessarily proxy all AndroidX artifacts.
        exclusiveContent {
            forRepository {
                maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
            }
            filter {
                includeGroupByRegex("com\\.rokid(\\..+)?")
            }
        }
        if (hasMetaDatAccess) {
            exclusiveContent {
                forRepository {
                    maven {
                        url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                        credentials {
                            username = ""
                            password = metaGithubToken
                        }
                    }
                }
                filter {
                    includeGroupByRegex("com\\.meta\\.wearable(\\..+)?")
                }
            }
        }
    }
}

rootProject.name = "xg-glass-app"
include(":app")
include(":ug_app_logic")

// Use universal_glasses as a composite build (no publishing step required).
// Replaced by xg-glass init: ../xg-glass-sdk
includeBuild("../xg-glass-sdk")

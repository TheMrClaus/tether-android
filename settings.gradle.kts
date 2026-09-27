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

rootProject.name = "Tether"
include(":app")

// PLAN D7 modules. Package names stay `com.tether.app.*` everywhere; each
// Android module has its own `namespace` (and therefore its own R class).
include(":core:protocol")
include(":core:reducer")
include(":core:net")
include(":core:data")
include(":core:designsystem")
include(":feature:auth")
include(":feature:chat")
include(":feature:files")
include(":feature:sidebar")
include(":feature:shell")

// PLAN D9 / T3.1: design-token generator (pure JVM; drives :core:designsystem's
// generateDesignTokens / verifyDesignTokens tasks).
include(":tools:design-tokens")

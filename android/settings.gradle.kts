pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://build-artifacts.signal.org/libraries/maven/") }
            filter { includeGroup("org.signal") }
        }
    }
}
rootProject.name = "SecretChat"
include(":app")

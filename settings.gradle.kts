pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        providers.gradleProperty("sdkRepository").orNull?.let { maven { url = uri(it) } }
    }
}
rootProject.name = "WaveNoteDemo"
include(":app")

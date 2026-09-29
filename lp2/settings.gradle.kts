pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// A Gradle build of its own, deliberately not part of the SDK build above it.
// The Light SDK's plugin restricts what a consumer module may declare - it
// bans custom source directories outright - and this project needs exactly
// that to share the core with the LP3 tool. Keeping the builds separate means
// the plugin never sees this one.
rootProject.name = "book-light-lp2"

include(":app")

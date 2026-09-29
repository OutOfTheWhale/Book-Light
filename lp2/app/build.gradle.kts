plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * The core, shared with the LP3 tool.
 *
 * The files still live once, under `tool/src/main/kotlin`. They cannot move
 * somewhere neutral, because the Light SDK's plugin forbids a consumer module
 * from declaring custom source directories - so the tool must keep its sources
 * where they are, and this build reaches across to them.
 *
 * Copied rather than referenced because the screen files import
 * `com.thelightphone.*`, which does not exist here, and they have to be left
 * behind. A Sync task filters properly; an Android source set has no filter
 * that can be scoped to a single source directory.
 */
val sharedCoreDir = layout.buildDirectory.dir("sharedCore")

val syncSharedCore = tasks.register<Sync>("syncSharedCore") {
    description = "Copies the SDK-free core out of the LP3 tool."
    from("../../tool/src/main/kotlin") {
        exclude(
            "com/outofthewhale/booklight/*Screen.kt",
            "com/outofthewhale/booklight/ToolEntryPoint.kt",
        )
    }
    into(sharedCoreDir)
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncSharedCore) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(syncSharedCore)
}

android {
    namespace = "com.outofthewhale.booklight.lp2"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.outofthewhale.booklight.lp2"
        // The LP3 tool targets minSdk 34, which no Light Phone 2 can install.
        // 26 covers Android 8.0 and up, comfortably above what the shared core
        // needs - DataStore wants 21.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // The debug key keeps a fresh clone building and sideloading. It is
            // not a distribution key: anyone holding it can forge an update.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // The core arrives via syncSharedCore above rather than by pointing
            // straight at the tool's directory: that would drag in its
            // SDK-bound screens too.
            kotlin.srcDirs("src/main/kotlin", sharedCoreDir)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.allan.workoutapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.allan.workoutapp"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "0.7.0"

        // Spotify App Remote credentials. The client id is per-developer (register the app
        // at developer.spotify.com with this applicationId + your signing SHA1), so it lives
        // in local.properties (gitignored), NOT in the repo. Blank id = feature disabled,
        // app builds and runs exactly as before. See docs/MAINTENANCE.md.
        val spotifyClientId = providers.gradleProperty("SPOTIFY_CLIENT_ID").orNull
            ?: rootProject.file("local.properties")
                .takeIf { it.exists() }
                ?.readLines()
                ?.firstOrNull { it.startsWith("SPOTIFY_CLIENT_ID=") }
                ?.substringAfter("=")
                ?.trim()
            ?: ""
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"workoutapp://spotify-callback\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // MigrationTestHelper reads the exported schemas from the test APK's assets, so the
    // migration tests can open a real v9 database and step it forward. Without this the
    // only way to prove a migration was to install the old APK by hand (02/08 session).
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    signingConfigs {
        // Keystore lives outside the repo; credentials come from ~/.gradle/gradle.properties.
        // See docs/MAINTENANCE.md — never regenerate the keystore.
        create("release") {
            val storeFilePath = providers.gradleProperty("WORKOUT_STORE_FILE").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("WORKOUT_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("WORKOUT_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("WORKOUT_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (providers.gradleProperty("WORKOUT_STORE_FILE").orNull != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The LLM plan-generator instructions ship inside the app (Settings → share the .md
    // with any chatbot). Single source of truth stays docs/WORKOUT_PLAN_GENERATOR.md.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/generatorDoc"))
}

val copyGeneratorDoc = tasks.register<Copy>("copyGeneratorDoc") {
    from(rootProject.file("docs/WORKOUT_PLAN_GENERATOR.md"))
    into(layout.buildDirectory.dir("generated/generatorDoc"))
    rename { "workout_plan_generator.md" }
}
tasks.named("preBuild") { dependsOn(copyGeneratorDoc) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.reorderable)
    // On-device translation for exercise names/descriptions (en -> app language).
    implementation("com.google.mlkit:translate:17.0.2")
    // Language identification, so the translate action only offers itself when the description
    // is actually in another language. Bundled model — nothing to download (Allan, 26/07).
    implementation("com.google.mlkit:language-id:17.0.6")
    // Spotify App Remote: control playback and (the point of it) heart the current track
    // from the session screen. Spotify never published this to Maven — the AAR comes from
    // github.com/spotify/android-sdk releases and is vendored in app/libs. It needs gson.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}

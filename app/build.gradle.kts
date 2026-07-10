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
        versionCode = 4
        versionName = "0.4.0"
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
    testImplementation(libs.junit)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore and passwords live outside the repository, as Gradle properties in
// ~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* environment variables (see README).
// Without the keystore path and its password the release APK is unsigned.
val releaseStoreFile: String? = providers.gradleProperty("DLNATVCAST_RELEASE_STORE_FILE").orNull
val releaseStorePassword: String? = providers.gradleProperty("DLNATVCAST_RELEASE_STORE_PASSWORD").orNull

android {
    namespace = "io.github.nezdelion.dlnatvcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nezdelion.dlnatvcast"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = providers.gradleProperty("DLNATVCAST_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("DLNATVCAST_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // The dependency metadata block is encrypted for Google: F-Droid and IzzyOnDroid reject APKs
    // carrying it. Play receives it through the bundle.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.media)
}

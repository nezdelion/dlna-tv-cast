plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore and passwords live outside the repository, in
// ~/.gradle/gradle.properties (see README). Without them the release APK is unsigned.
val releaseStoreFile: String? = providers.gradleProperty("DLNATVCAST_RELEASE_STORE_FILE").orNull

android {
    namespace = "io.github.nezdelion.dlnatvcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nezdelion.dlnatvcast"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("DLNATVCAST_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("DLNATVCAST_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("DLNATVCAST_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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

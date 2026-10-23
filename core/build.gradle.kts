import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// kotlinx-coroutines-test's virtual-time API is still marked experimental.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Opt-in integration test against a real renderer:
//   ./gradlew :core:test -PwithRenderer=http://192.168.50.23:49494/description.xml
val rendererLocation: String? = providers.gradleProperty("withRenderer").orNull

tasks.test {
    useJUnitPlatform {
        if (rendererLocation == null) excludeTags("renderer")
    }
    if (rendererLocation != null) {
        systemProperty("tvcast.renderer", rendererLocation)
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events("passed", "failed", "skipped")
        }
    }
}

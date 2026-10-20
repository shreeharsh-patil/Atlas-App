import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

val localReleaseSigning = Properties().apply {
    val propertiesFile = rootProject.file(".release/signing.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}
val localReleaseKeystore = localReleaseSigning.getProperty("storeFile")?.let { rootProject.file(it) }
val localReleaseSigningReady = localReleaseKeystore?.isFile == true &&
    listOf("storePassword", "keyAlias", "keyPassword").all { !localReleaseSigning.getProperty(it).isNullOrBlank() }
val allowUnsignedRelease = providers.gradleProperty("allowUnsignedRelease").orNull == "true"
if (!localReleaseSigningReady && !allowUnsignedRelease) {
    logger.warn(
        "No release signing key found at .release/signing.properties. The release variant will be " +
            "UNSIGNED. Pass -PallowUnsignedRelease=true to acknowledge this deliberately."
    )
}

android {
    namespace = "com.meetingatlas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meetingatlas"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // x86/x86_64 are emulator-only; x86_64 is kept for ChromeOS.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("releaseLocal") {
            if (localReleaseSigningReady) {
                storeFile = localReleaseKeystore
                storePassword = localReleaseSigning.getProperty("storePassword")
                keyAlias = localReleaseSigning.getProperty("keyAlias")
                keyPassword = localReleaseSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (localReleaseSigningReady) signingConfigs.getByName("releaseLocal") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Escape hatch: -PnoShrink=true produces an unminified release for R8 bisection.
        create("releaseNoShrink") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".noshrink"
            versionNameSuffix = "-noshrink"
        }
    }
    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
        htmlReport = true
        xmlReport = true
        textReport = true
        disable += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            "OldTargetApi",
            // kapt still works and is the only processor setup this project uses today.
            "KaptUsageInsteadOfKsp",
            // The launcher bitmap is the supplied brand artwork.
            "IconLauncherShape",
        )
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                it.useJUnit()
                it.testLogging { events("passed", "skipped", "failed") }
            }
        }
        animationsDisabled = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "DebugProbesKt.bin",
            )
        }
        jniLibs { useLegacyPackaging = false }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

kapt {
    correctErrorTypes = true
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    kapt(libs.androidx.room.compiler)
    kapt(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.room.testing)
}

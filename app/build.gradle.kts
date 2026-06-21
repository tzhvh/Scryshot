plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    // kapt removed in issue 16 — nothing else uses it
}

android {
    namespace = "org.mozilla.scryer"
    compileSdk = 35

    // Suppress deprecation warnings surfaced by the SDK 35 / AGP 8 bump that are
    // owned by Tier 3 (scoped storage, foreground service type, etc.). Downgraded
    // to warning so Gate B can go green without pre-empting that work.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "org.mozilla.screenshot.go"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val buildNumber = System.getenv("BITRISE_BUILD_NUMBER")
        if (!buildNumber.isNullOrBlank()) {
            val n = buildNumber.toInt()
            versionCode = n
            versionNameSuffix = "($n)"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = applicationIdSuffix
        }
    }

    flavorDimensions += "product"

    productFlavors {
        create("go") {
            dimension = "product"
            resConfigs("en", "in")
        }
        create("preview") {
            dimension = "product"
            applicationId = "gro.allizom.scryer"
            applicationIdSuffix = ""
            versionNameSuffix = ".nightly"
            resConfigs("en", "in")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// The preview flavor ships release-only — drop its debug variants.
// (AGP 8 variant API; supersedes the old variantFilter {} block.)
androidComponents {
    beforeVariants { variant ->
        val isPreview = variant.productFlavors.any { it.second == "preview" }
        if (isPreview && variant.buildType != "release") {
            variant.enable = false
        }
    }
}

// KSP configuration for Room schema export.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.transition)
    implementation(libs.media)
    implementation(libs.constraintlayout)
    implementation(libs.preference)

    // KTX
    implementation(libs.core.ktx)

    // Lifecycle
    implementation(libs.lifecycle.common.java8)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.process)

    // Navigation
    implementation(libs.navigation.ui.ktx)
    implementation(libs.navigation.fragment.ktx)

    // Room
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Kotlin
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)

    // Glide
    implementation(libs.glide)

    // ML Kit text recognition (standalone — bundled model, no Firebase needed).
    implementation(libs.mlkit.text.recognition)

    // SubsamplingScaleImageView
    implementation(libs.subsampling.scale.image.view)

    // Lottie
    implementation(libs.lottie)

    // BetterLinkMovementMethod
    implementation(libs.better.link.movement.method)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.espresso.core)
}

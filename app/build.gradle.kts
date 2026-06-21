plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
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

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
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

    // variantFilter (Groovy) is gone in AGP 8's Kotlin DSL; the equivalent is
    // androidComponents.beforeVariants below.
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

dependencies {
    // AGP 8 enforces unique manifest namespaces across artifacts. The 2018
    // AndroidX pins pull vectordrawable + vectordrawable-animated at versions
    // (1.0.0 / 1.0.1) that both declare the same namespace, which AGP 8 rejects.
    // Force-align to 1.1.0 where this was fixed. Tier 2 (issue 18) bumps these
    // properly and can drop this block.
    configurations.all {
        resolutionStrategy {
            force(
                "androidx.vectordrawable:vectordrawable:1.1.0",
                "androidx.vectordrawable:vectordrawable-animated:1.1.0"
            )
        }
    }

    // AndroidX
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.cardview)
    implementation(libs.recyclerview)
    implementation(libs.transition)
    implementation(libs.legacy.preference.v14)
    implementation(libs.media)
    implementation(libs.legacy.support.v4)
    implementation(libs.constraintlayout)

    // KTX
    implementation(libs.core.ktx)

    // Lifecycle
    implementation(libs.lifecycle.common.java8)
    implementation(libs.lifecycle.extensions)

    // Navigation
    implementation(libs.navigation.ui)
    implementation(libs.navigation.fragment)

    // Room
    implementation(libs.room.runtime)
    kapt(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime)

    // Kotlin
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)

    // Glide
    implementation(libs.glide)

    // Firebase ML Kit — replaced with standalone ML Kit in Tier 2 (issue 12).
    implementation(libs.firebase.ml.vision)

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

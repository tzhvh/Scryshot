plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.tzhvh.scryernext.zvec"
    compileSdk = 35
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String", "ZVEC_VERSION",
            "\"${rootProject.file("zvec-android/ZVEC_VERSION").readText().trim()}\""
        )

        externalNativeBuild { cmake { cppFlags("-std=c++17", "-fexceptions") } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { buildConfig = true }

    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt") } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // runBlocking to drive the suspend SDK methods from instrumentation tests.
    // main-scoped only — the SDK itself adds no dispatcher hop (ADR 0007), so it
    // depends on kotlinx-coroutines-core, not the android artifact.
    androidTestImplementation(libs.kotlinx.coroutines.android)

    // JVM unit tests for the pure-Kotlin seams (SchemaDescriptor encoder). The .so
    // can't run on the JVM, so anything that touches JNI is instrumentation-only;
    // these tests cover the flat-array flattening logic that doesn't.
    testImplementation(libs.junit)
}

val checkAarPackaging by tasks.registering {
    dependsOn("bundleDebugAar")
    doLast {
        val aarFile = file("build/outputs/aar/zvec-android-debug.aar")
        if (!aarFile.exists()) {
            throw GradleException("AAR output not found at ${aarFile.absolutePath}")
        }
        val expectedFiles = setOf(
            "jni/arm64-v8a/libzvec_c_api.so",
            "jni/arm64-v8a/libzvec_jni.so",
            "jni/x86_64/libzvec_c_api.so",
            "jni/x86_64/libzvec_jni.so"
        )
        val packagedFiles = mutableSetOf<String>()
        zipTree(aarFile).visit {
            if (!isDirectory) {
                packagedFiles.add(path)
            }
        }
        val missingFiles = expectedFiles - packagedFiles
        if (missingFiles.isNotEmpty()) {
            throw GradleException("Missing files in AAR: $missingFiles")
        }
        println("AAR packaging check passed! All expected native libraries are present.")
    }
}

tasks.named("check") {
    dependsOn(checkAarPackaging)
}

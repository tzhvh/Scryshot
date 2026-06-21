// Top-level build file — plugin versions are declared here and applied in modules.
// Dependency versions live in gradle/libs.versions.toml (the version catalog).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}

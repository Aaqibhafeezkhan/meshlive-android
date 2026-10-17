// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Plugin versions resolved here are locked in buildscript-gradle.lockfile (docs/ARCHITECTURE.md "Build notes", toolchain pins).
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
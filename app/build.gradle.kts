import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Optional 32-bit ARM build (default OFF, the shipped ABI set is unchanged): -PenableArmeabiV7a=true
// adds armeabi-v7a so phones whose ro.product.cpu.abilist lacks arm64-v8a (32-bit userspace such as
// some Android Go builds) can install. Cost and caveats: see NativeCompat.kt (rule native-abi-coverage).
val enableArmeabiV7a = (project.findProperty("enableArmeabiV7a") as String?)?.toBoolean() == true

// Per-ABI release APKs (tools/make_release.py passes -PsplitAbis=true -PenableArmeabiV7a=true; default OFF, so
// debug and benchmark builds stay one APK). Splits produce arm64-v8a, armeabi-v7a and a universal APK, each with
// its own versionCode = versionCode * 10 + ABI digit (the F-Droid/Signal convention; README "Distribution").
val splitAbis = (project.findProperty("splitAbis") as String?)?.toBoolean() == true
val universalApk = (project.findProperty("universalApk") as String?)?.toBoolean() != false
val abiVersionDigits = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)

// RENAME POINT (one line): the installed application id. The Kotlin package and `namespace` below stay
// com.example.bluetoothtest on purpose (renaming them is not needed for a new id). Override for a trial build
// with -PappId=<id>. A new id is a different app to Android: it cannot update an install of the old one.
val appId = (project.findProperty("appId") as String?) ?: "io.github.meshlive.app"

// Optional native-build overrides, so a Linux or F-Droid build needs no machine-specific path:
// -PaomSourceDir=<libaom checkout at the pinned commit>, -PperlExecutable=<perl>.
// Without them: reference-repos/libaom, and Git for Windows' perl only when it exists (Windows hosts).
val aomSourceDir = project.findProperty("aomSourceDir") as String?
val perlExecutable = (project.findProperty("perlExecutable") as String?)
    ?: file("C:/Program Files/Git/usr/bin/perl.exe").takeIf { it.isFile }?.path

android {
    ndkVersion = "28.2.13676358"

    buildFeatures {
        buildConfig = true
    }

    namespace = "com.example.bluetoothtest"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-beta"
        // versionCode/versionName are literals: no date, git hash or counter, so a rebuild of a commit gets
        // the same version (the make_release.py and fastlane changelog readers parse these two lines).

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AGP rejects ndk.abiFilters together with splits.abi; the split configuration below picks the ABIs.
        if (!splitAbis) {
            ndk {
                abiFilters += "arm64-v8a"
                if (enableArmeabiV7a) abiFilters += "armeabi-v7a"
            }
        }
        // Read by NativeCompat for its BENCHMARK native_compat line: the ABIs this build configuration
        // produces (a split APK carries only its own).
        buildConfigField(
            "String",
            "NATIVE_ABIS",
            if (enableArmeabiV7a) "\"arm64-v8a,armeabi-v7a\"" else "\"arm64-v8a\"",
        )

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                perlExecutable?.let { arguments += "-DPERL_EXECUTABLE=$it" }
                aomSourceDir?.let { arguments += "-DAOM_SOURCE_DIR=$it" }
            }
        }
    }

    if (splitAbis) {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a")
                isUniversalApk = universalApk
            }
        }
    }

    // F-Droid and reproducible builds: no Google-encrypted dependency block in the APK signing block
    // (AGP writes no META-INF/version-control-info.textproto unless vcsInfo.include is set; it is not).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release signing key lives outside the repo (never commit it). Path override:
    // -PreleaseKeystoreProperties=<file>; default ~/.bluetoothtest-release/keystore.properties.
    val releaseKeystoreProperties = Properties().apply {
        val file = (project.findProperty("releaseKeystoreProperties") as String?)?.let(::file)
            ?: file("${System.getProperty("user.home")}/.bluetoothtest-release/keystore.properties")
        if (file.isFile) file.inputStream().use { stream -> load(stream) }
    }
    signingConfigs {
        if (releaseKeystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                // Release only: strips Log.v/d/i. Debug is not minified and keeps every log line.
                "proguard-release-logs.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

androidComponents {
    onVariants { variant ->
        if (!splitAbis) return@onVariants
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val base = output.versionCode.get() ?: return@forEach
            output.versionCode.set(base * 10 + (abiVersionDigits[abi] ?: 0))
        }
    }
}

// Every resolved configuration is locked in app/gradle.lockfile (README "Build", toolchain pins).
dependencyLocking {
    lockAllConfigurations()
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.camera.viewfinder.view)
    // QR verification codes (Apache-2.0; bundles ZXing core). Runs fully offline.
    implementation(libs.zxing.android.embedded)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

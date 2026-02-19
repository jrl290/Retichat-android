plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.retichat.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.retichat.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // The Rust build places .so files here
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Activity + Navigation
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // CameraX (QR scanning)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)

    // QR generation
    implementation(libs.zxing.core)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
}

// ---- Rust NDK build task ----
// Runs `cargo ndk` to produce libretichat_jni.so for each ABI.
// Requires: cargo-ndk (`cargo install cargo-ndk`)
//           Android NDK installed via SDK Manager
//           Rust targets: `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`

tasks.register<Exec>("buildRustNdk") {
    workingDir = file("${rootDir}/rust/retichat-jni")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", "${projectDir}/src/main/jniLibs",
        "build", "--release"
    )
}

tasks.named("preBuild") {
    // Uncomment to auto-build Rust on every Gradle build:
    // dependsOn("buildRustNdk")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.stockfishmobile"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.stockfishmobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Stockfish is built for exactly these ABIs (see scripts/build_stockfish.sh).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // Release builds are signed with the local debug key so the APK can be
        // installed directly (side-loading). Replace with your own key for store uploads.
        getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Stockfish is shipped as an executable named libstockfish*.so. It must be
        // extracted to nativeLibraryDir on install so that it can be executed.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.logging", "stdout")
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
            // Pass-through for the optional real-engine tests.
            listOf("SF_HOST_BINARY", "SF_NNUE", "SCREENSHOT_DIR").forEach { k -> System.getenv(k)?.let { v -> it.environment(k, v) } }
        }
    }

    androidResources {
        // NNUE network files are already compressed binary data; keep them as-is.
        noCompress += "nnue"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// Stockfish: build the engine from official sources with the NDK and download
// the NNUE networks. Runs automatically before the first build.
// ---------------------------------------------------------------------------
val stockfishScript = rootProject.file("scripts/build_stockfish.sh")
val jniLibsDir = file("src/main/jniLibs")
val nnueDir = file("src/main/assets/nnue")

val buildStockfish by tasks.registering(Exec::class) {
    group = "stockfish"
    description = "Builds Stockfish for all Android ABIs and fetches NNUE networks."
    onlyIf { !file("src/main/jniLibs/arm64-v8a/libstockfish.so").exists() || nnueDir.listFiles().isNullOrEmpty() }
    val sdkDir = android.sdkDirectory
    val ndkDir = android.ndkDirectory
    doFirst {
        environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        environment("ANDROID_HOME", sdkDir.absolutePath)
    }
    commandLine("bash", stockfishScript.absolutePath)
}

tasks.named("preBuild") { dependsOn(buildStockfish) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

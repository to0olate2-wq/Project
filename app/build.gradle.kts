import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.falak.player"
    // Google Play requires apps to target Android 16 (API 36) since 31 August 2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "app.falak.player"
        minSdk = 24
        targetSdk = 36
        // CI passes the GitHub run number, so every published build is a newer version.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "4.2"
        buildConfigField("String", "UPDATE_REPO", "\"to0olate2-wq/Project\"")
        // TVs and phones are ARM; leaving out x86 keeps the download much smaller now that VLC is built in.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Fixed signing key so each new build installs as an update over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Google Play upload key: kept out of the repo, supplied by CI from the repository's
        // secrets (PLAY_KEYSTORE_B64 / PLAY_KEYSTORE_PASSWORD). Without it the Play bundle is unsigned.
        val uploadStore = System.getenv("PLAY_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
        if (uploadStore != null) {
            create("upload") {
                storeFile = uploadStore
                storePassword = System.getenv("PLAY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PLAY_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("PLAY_KEY_PASSWORD") ?: System.getenv("PLAY_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 optimises the code (Compose runs noticeably smoother) and drops unused parts.
            // Names are kept (see proguard-rules.pro) so crash reports stay readable.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signing is chosen per store below (a build type's key would override the flavours').
        }
    }

    // "github" = sideloaded builds with one-tap updates; "play" = Google Play (no self-update allowed).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            // Play rejects bundles signed with a debug key: use the upload key when CI has it.
            signingConfig = signingConfigs.findByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // VLC and the FFmpeg audio decoder both bring the C++ runtime.
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            // Compress the native libraries (VLC is large): less than half the download size.
            useLegacyPackaging = true
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    // Screenshot tests (app/src/test/.../screens): Robolectric draws the real screens with sample
    // data, so layout changes can be checked without a device. CI publishes them to the
    // "screenshots" branch.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "4g"
                // Compose's frame clock doesn't survive Robolectric's reset between tests: one JVM per test.
                it.forkEvery = 1
                it.systemProperty("screens.dir", layout.buildDirectory.dir("screens").get().asFile.path)
                // Name each test as it starts, so a stuck one shows up in the CI log.
                it.testLogging { events("started", "passed", "failed", "skipped") }
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // FFmpeg audio decoder (Dolby AC-3/E-AC-3, DTS, MP2, TrueHD…) for devices without hardware decoders.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    // VLC engine: plays streams ExoPlayer can't, and shows teletext subtitles.
    implementation("org.videolan.android:libvlc-all:3.6.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // QR code for "Your devices" (scan with a phone to open the sharing page).
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
}

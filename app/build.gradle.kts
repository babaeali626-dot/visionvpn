plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseKeystorePath: String? = System.getenv("VISION_KEYSTORE_PATH")
    ?: project.findProperty("vision.keystore.path") as? String
val releaseKeystorePassword: String? = System.getenv("VISION_KEYSTORE_PASSWORD")
    ?: project.findProperty("vision.keystore.password") as? String
val releaseKeyAlias: String? = System.getenv("VISION_KEY_ALIAS")
    ?: project.findProperty("vision.key.alias") as? String
val releaseKeyPassword: String? = System.getenv("VISION_KEY_PASSWORD")
    ?: project.findProperty("vision.key.password") as? String
val allowUnsignedRelease: Boolean = (project.findProperty("vision.allowUnsignedRelease") as? String)
    ?.toBooleanStrictOrNull() ?: false

val hasCompleteReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.visionvpn.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.visionvpn.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        buildConfigField("String", "SING_BOX_PINNED_VERSION", "\"1.10.7\"")
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
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
            signingConfig = if (hasCompleteReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Fail fast if a release build is requested without production signing credentials
gradle.taskGraph.whenReady {
    val isReleaseTask = allTasks.any { task ->
        task.name.contains("Release", ignoreCase = true) &&
            (task.name.startsWith("assemble") || task.name.startsWith("bundle"))
    }
    if (isReleaseTask && !hasCompleteReleaseSigning && !allowUnsignedRelease) {
        error(
            "Release signing credentials are missing. Set VISION_KEYSTORE_PATH, " +
                "VISION_KEYSTORE_PASSWORD, VISION_KEY_ALIAS, and VISION_KEY_PASSWORD " +
                "(or pass -Pvision.allowUnsignedRelease=true for unsigned CI verification)."
        )
    }
}

dependencies {
    // Native Sing-box libbox.aar (v1.10.7 built/fetched by CI into app/libs/)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Embedded Tor Android binary & service library (Guardian Project)
    implementation("info.guardianproject:tor-android:0.4.9.17")

    // Core Android & Lifecycle
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Jetpack Compose BOM & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // DataStore for encrypted settings, profiles, WARP keys, and Tor bridges
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking (Subscriptions, Cloudflare WARP registration, Clash API WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX + ZXing for QR code profile scanning
    val cameraXVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.zxing:core:3.5.3")

    // Debug & Unit Testing
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

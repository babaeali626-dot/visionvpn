import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

// VisionVPN launches libtor.so directly; it does not consume Tor Android's AAR classes.
// Resolve the AAR as a native-only artifact so its compileSdk 37 metadata does not become
// an Android compile-classpath requirement for this API 36 / AGP 8.13.2 application.
val torAndroidVersion = "0.4.9.17"
val torAndroidNativeAar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Native-only Tor Android AAR used to extract libtor.so"
}

android {
    namespace = "com.visionvpn.app"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.visionvpn.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        buildConfigField("String", "HIDDIFY_CORE_VERSION", "\"5.9.15\"")
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

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/torAndroidJniLibs"))
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

// Kotlin 2.4.x requires the typed compilerOptions DSL; retain JVM 17 bytecode.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
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
    // Native Hiddify Core hiddify-core.aar (v5.9.15 built/fetched by CI into app/libs/)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Tor Android is resolved as a native-only AAR, not an Android compile/runtime
    // dependency. VisionVPN invokes libtor.so directly via ProcessBuilder and does not
    // use the AAR's Kotlin/Java classes, resources, or manifest.
    add(torAndroidNativeAar.name, "info.guardianproject:tor-android:$torAndroidVersion@aar") {
        isTransitive = false
    }

    // Core Android & Lifecycle
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")

    // Jetpack Compose BOM & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    // DataStore for encrypted settings, profiles, WARP keys, and Tor bridges
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    // Networking (Subscriptions, Cloudflare WARP registration, Clash API WebSocket)
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // CameraX + ZXing for QR code profile scanning
    val cameraXVersion = "1.7.0"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.zxing:core:3.5.3")

    // Debug & Unit Testing
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}


// Extract only the Tor daemon's native libraries from the official Guardian Project AAR.
// Keeping the AAR off the Android compile classpath avoids importing its minCompileSdk=37
// metadata into this API 35 app. The extracted .so files are still checked in the final APK
// by scripts/verify_16kb_alignment.py; this is not a relaxation of the 16 KiB gate.
val extractTorAndroidNativeLibraries by tasks.registering(Copy::class) {
    from({
        torAndroidNativeAar.files.map { aarFile ->
            zipTree(aarFile).matching { include("jni/**/*.so") }
        }
    }) {
        eachFile {
            path = path.removePrefix("jni/")
        }
    }
    into(layout.buildDirectory.dir("generated/torAndroidJniLibs"))
    includeEmptyDirs = false

    doLast {
        val outputDir = layout.buildDirectory.dir("generated/torAndroidJniLibs").get().asFile
        val requiredAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val missing = requiredAbis.filterNot { abi ->
            outputDir.resolve("$abi/libtor.so").isFile
        }
        check(missing.isEmpty()) {
            "Guardian Project tor-android $torAndroidVersion AAR is missing libtor.so for: ${missing.joinToString()}"
        }
        logger.lifecycle("Extracted tor-android $torAndroidVersion libtor.so for ${requiredAbis.joinToString()}")
    }
}

tasks.named("preBuild").configure {
    dependsOn(extractTorAndroidNativeLibraries)
}

import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                // Backed by a 4 GB swapfile, so the test JVM can hold a full
                // Robolectric runtime without the machine thrashing.
                it.maxHeapSize = "1200m"
                // Still recycle the JVM periodically: Robolectric loads a fresh
                // Android runtime per SDK level and never frees the old ones.
                it.setForkEvery(4L)
                it.maxParallelForks = 1
            }
        }
    }
    namespace = "com.nyra.comic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nyra.comic"
        minSdk = 24
        targetSdk = 34
        versionCode = 24
        versionName = "2.7.2"
    }

    // Kredensial penandatanganan TIDAK ditulis di sini.
    //
    // Sebelumnya kata sandi keystore ada sebagai literal di berkas ini, dan
    // berkas ini masuk git - siapa pun yang mengkloning repo bisa menerbitkan
    // APK yang terlihat sah sebagai NYRA. Sekarang nilainya dibaca dari
    // keystore.properties (di-gitignore) atau variabel lingkungan, dan bila
    // keduanya tidak ada build release memakai kunci debug bawaan Android
    // supaya kontributor tetap bisa membangun tanpa memegang kunci rilis.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) FileInputStream(f).use { load(it) }
    }
    fun rahasia(kunci: String, env: String): String? =
        (keystoreProps.getProperty(kunci) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

    val storePath = rahasia("storeFile", "NYRA_KEYSTORE") ?: "../nyra-release.jks"
    val storePass = rahasia("storePassword", "NYRA_STORE_PASSWORD")
    val aliasName = rahasia("keyAlias", "NYRA_KEY_ALIAS")
    val keyPass = rahasia("keyPassword", "NYRA_KEY_PASSWORD")
    val bisaTandaTangan = storePass != null && aliasName != null && keyPass != null &&
        file(storePath).exists()

    signingConfigs {
        if (bisaTandaTangan) {
            create("release") {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = aliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // R8 menyala: memangkas kelas mati dari okhttp/onnxruntime/material
            // dan mengaburkan nama, sehingga APK lebih kecil dan pipeline tidak
            // lagi terbaca sebagai peta jalan di dekompiler.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (bisaTandaTangan) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // ONNX Runtime ships a ~20 MB .so per ABI; split so each device downloads one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures { viewBinding = true }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }

    androidResources {
        noCompress += listOf("onnx", "ttf")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("com.github.junrar:junrar:7.5.5")
    // Handles ZIPs that java.util.zip refuses: bzip2 / LZMA / XZ compression
    // methods, which ZArchiver and other Android packers can produce.
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}

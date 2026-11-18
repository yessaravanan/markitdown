import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.markitdown.local"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.markitdown.local"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            // Real phones are ARM. Add "x86_64" via -Pabis=... to run in an emulator.
            abiFilters += ((findProperty("abis") as String?) ?: "arm64-v8a,armeabi-v7a").split(',').map { it.trim() }
        }
    }

    buildTypes {
        release {
            // Not minified: Tesseract's JNI layer calls back into Java classes by name.
            isMinifyEnabled = false
            // Signed with the local debug key so the APK installs directly by sideloading.
            // Replace with your own signingConfig for distribution.
            signingConfig = signingConfigs.getByName("debug")
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
    sourceSets {
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/tessdata-assets"))
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    androidResources {
        // Keep models uncompressed so their size can be checked cheaply via openFd().
        noCompress += "traineddata"
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            // Post-quantum crypto tables from BouncyCastle; PDFBox never uses them.
            "org/bouncycastle/pqc/**",
        )
    }
}

// Downloads the Tesseract language models at BUILD time and packs them into the APK,
// so the app never needs network access at runtime.
val tessdataVariant = (findProperty("tessdataVariant") as String?) ?: "fast"
val tessdataLanguages = ((findProperty("tessdataLanguages") as String?) ?: "eng")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

val downloadTessdata by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/tessdata-assets/tessdata")
    inputs.property("variant", tessdataVariant)
    inputs.property("languages", tessdataLanguages)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        for (lang in tessdataLanguages) {
            val target = dir.resolve("$lang.traineddata")
            if (target.exists() && target.length() > 0) continue
            val url = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_$tessdataVariant/main/$lang.traineddata"
            logger.lifecycle("Downloading $url")
            val tmp = dir.resolve("$lang.traineddata.part")
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "Could not write $target" }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadTessdata) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // PDF text-layer extraction (pure Java port of Apache PDFBox, runs fully on-device).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Offline OCR (Tesseract 5 + Leptonica compiled for Android). Multi-threaded variant.
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")
    // Markdown preview rendering.
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

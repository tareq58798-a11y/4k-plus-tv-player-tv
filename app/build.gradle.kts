import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing details, read from keystore.properties in the project root. That file holds the
 * keystore's location and passwords and is deliberately gitignored - the signing key is what proves
 * an update genuinely comes from this developer, so it must never enter the repository.
 *
 * When the file is absent (any machine that is not the release machine, or a fresh clone) the
 * release build simply has no signing config and produces an unsigned APK, exactly as before.
 * Debug builds are unaffected either way.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

kotlin {
    jvmToolchain(17)
}

android {
    useLibrary("android.test.runner", false)
    useLibrary("android.test.base", false)
    namespace = "com.fourkplus.tvplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fourkplus.tvplayer.tv"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        minSdk = 26
        targetSdk = 35
        // versionName is what people see and must match the GitHub release tag and the APK
        // filename exactly, so there is only ever one number to reason about. The 1.2 - 2.3 range
        // was internal churn during development and was never published; the last public build was
        // 1.1, so this is 2.0.
        //
        // versionCode is what Android compares to decide whether something is an update. It must
        // only ever increase, and is deliberately not reset to match versionName.
        versionCode = 127
        versionName = "7.6"
    }

    // Two builds from one source tree, and never one build pretending to be both. They carry
    // different application ids, so a television and a phone are separate apps with separate Play
    // listings and can sit on the same device at once; and they take their form-factor manifests
    // from src/tv and src/phone, so phone work cannot alter what the TV app declares.
    //
    // The source itself is deliberately shared. Every D-pad rule, every provider call and all eight
    // translations are the same programme, and splitting them into two copies would mean fixing
    // everything twice. What differs between a remote and a fingertip is decided at runtime, from
    // isTvDevice() and the size of the screen.
    flavorDimensions += "formFactor"
    productFlavors {
        create("tv") {
            dimension = "formFactor"
            // Unchanged: this is the id the published TV app already uses and must keep.
            applicationId = "com.fourkplus.tvplayer.tv"
            // Which build this is, answered when it is compiled rather than when it runs. Shared
            // code that is only for a fingertip sits behind this, so the television keeps the
            // behaviour it already has no matter what the phone work does next. Declared here
            // rather than as a source file under src/tv, so no file of the television's is touched.
            buildConfigField("boolean", "TOUCH_BUILD", "false")
        }
        create("phone") {
            dimension = "formFactor"
            applicationId = "com.fourkplus.tvplayer.phone"
            buildConfigField("boolean", "TOUCH_BUILD", "true")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Null without keystore.properties, which leaves the APK unsigned rather than failing
            // the build - a machine without the key can still compile and check the release variant.
            signingConfig = signingConfigs.findByName("release")
            // Left off deliberately for now: R8 needs keep rules verified against Compose and
            // media3 reflection before it can be trusted on a release users install.
            isMinifyEnabled = false
        }
    }

    lint {
        // lintVitalAnalyzeRelease currently dies inside the lint tool itself rather than on any
        // finding in this project, which blocks assembleRelease entirely. Lint still runs on
        // demand with `gradlew :app:lint`; only the release-blocking pass is skipped.
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        // Generates BuildConfig, which carries TOUCH_BUILD above. Off by default since AGP 8.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

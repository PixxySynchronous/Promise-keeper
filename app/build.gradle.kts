import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

// Release signing key lives outside the repository. Path can be overridden with PROMISE_KEEPER_KEYSTORE.
val releaseKeyFile = file(System.getenv("PROMISE_KEEPER_KEYSTORE") ?: "${System.getProperty("user.home")}/.promise-keeper/keystore.properties")
val releaseKey = Properties().apply { if (releaseKeyFile.exists()) releaseKeyFile.inputStream().use(::load) }

android {
    namespace = "com.satyam.whatsappbot"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.satyam.whatsappbot"
        minSdk = 33
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.3"
    }

    signingConfigs {
        if (releaseKeyFile.exists()) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Not debuggable (the default for release), so `adb run-as` cannot read reminders or messages.
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            // Phones are ARM; leaving out x86 emulator libraries saves ~40 MB.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    // Test-only JSON reader for the tokenizer fixtures; not packaged into the app.
    testImplementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.room:room-runtime:2.7.0")
    kapt("androidx.room:room-compiler:2.7.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rafeeq.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rafeeq.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"

        // Injected from CI (GitHub Actions secret -> environment variable). Reading the key
        // from the environment rather than local.properties means a CI runner never needs a
        // file that is absent from the checkout, so assembleDebug can actually sign & build.
        buildConfigField(
            "String",
            "GROQ_API_KEY",
            "\"${System.getenv("GROQ_API_KEY") ?: ""}\""
        )
    }

    signingConfigs {
        create("release") {
            storeType = "PKCS12"
            storeFile = file("../release.p12")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "hasan3522"
            keyAlias = System.getenv("KEY_ALIAS") ?: "rafeeq"
            // A PKCS12 keystore stores the private key under the same password as the
            // keystore itself, so this must match storePassword above.
            keyPassword = System.getenv("KEY_PASSWORD") ?: "hasan3522"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
}

dependencies {
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX core + Activity
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Lifecycle (ViewModel + Compose integration)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Groq (OpenAI-compatible HTTP API)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Material Components: supplies the Material 3 XML theme (Theme.Material3.*)
    // that Theme.Rafeeq inherits from. Jetpack Compose ships no XML themes itself.
    implementation("com.google.android.material:material:1.12.0")
}

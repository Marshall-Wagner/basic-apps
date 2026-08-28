plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.montb.basickeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.montb.basickeyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        // ROG Phone 6 is arm64 only.
        ndk { abiFilters += "arm64-v8a" }
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
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Compose is only used for the setup/help Activity; the keyboard view itself is
    // plain Android Views (the reliable approach for an InputMethodService).
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Local JVM unit tests (pure keyboard-layout data). Run: ./gradlew test
    testImplementation("junit:junit:4.13.2")
}

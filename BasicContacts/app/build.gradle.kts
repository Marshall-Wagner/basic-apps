plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.montb.basiccontacts"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.montb.basiccontacts"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Your ROG Phone 6 is arm64 (Snapdragon 8+ Gen 1). Building ONLY arm64-v8a
        // keeps the APK small and skips emitting code for ABIs you'll never run.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // The real performance levers: shrink + optimize the bytecode (R8) and
            // strip unused resources. This is what actually makes a release build fast.
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
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Installs Baseline Profiles so hot paths are AOT-compiled -> no scroll jank.
    implementation(libs.androidx.profileinstaller)

    implementation(libs.kotlinx.coroutines.android)

    // Local JVM unit tests (vCard parse/escape helpers). Run: ./gradlew test
    testImplementation("junit:junit:4.13.2")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.montb.basiccamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.montb.basiccamera"
        // minSdk 29 (vs 26 elsewhere): lets us save straight into the MediaStore with
        // scoped storage (RELATIVE_PATH / IS_PENDING) and no WRITE_EXTERNAL_STORAGE. The
        // ROG Phone 6 is Android 14, so this loses nothing on the target device.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // AOT-compile hot paths (smooth preview/capture on first run).
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX: preview + still capture + video, with a controller that wires
    // tap-to-focus and pinch-to-zoom onto the PreviewView for us.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    // HEIF/HEIC still encoder for the "High efficiency" capture path.
    implementation(libs.androidx.heifwriter)
}

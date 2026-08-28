plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.montb.basicsms"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.montb.basicsms"
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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Installs Baseline Profiles so hot paths are AOT-compiled -> no scroll jank.
    implementation(libs.androidx.profileinstaller)

    implementation(libs.kotlinx.coroutines.android)

    // Local JVM unit tests (backup NDJSON parsing). org.json ships a real impl for the
    // JVM (Android's android.jar stubs it out in unit tests). Run: ./gradlew test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

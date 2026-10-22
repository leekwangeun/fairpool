plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The repository lives under a non-ASCII folder and aapt2 cannot open such paths on Windows,
// so build outputs go to an ASCII folder and the app keeps no res/ directory of its own.
layout.buildDirectory.set(file("C:/dev/build/fairpool-car/app"))

android {
    namespace = "kr.fairpool.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.fairpool.car"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SERVER_URL", "\"http://10.0.2.2:54321\"")
        buildConfigField("String", "SITE_URL", "\"http://10.0.2.2:5173\"")
        buildConfigField("String", "ANON_KEY", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Contest build: signed with the local debug key so the APK installs anywhere for judging.
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
    useLibrary("android.car")
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.21")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}

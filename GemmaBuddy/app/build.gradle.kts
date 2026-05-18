plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.gemmabuddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gemmabuddy"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
    assetPacks += setOf(":gemmamodel")
    // MediaPipeのネイティブライブラリ用
    packaging {
        jniLibs {
            pickFirsts += setOf("**/*.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.lifecycle.service)
    implementation(libs.preference.ktx)
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.gif.drawable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.asset.delivery)
}

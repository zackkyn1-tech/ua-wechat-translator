plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ua.guitar.archive"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.guitar.archive"
        minSdk = 24
        targetSdk = 35
        // CI задає VERSION_CODE = номер збірки; локальна збірка — 2.
        val code = (System.getenv("VERSION_CODE") ?: "2").toInt()
        versionCode = code
        versionName = "1.$code"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/release.jks")
            storePassword = "guitar-archive"
            keyAlias = "guitar"
            keyPassword = "guitar-archive"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}

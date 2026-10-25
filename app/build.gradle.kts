plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.sportclock"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sportclock"   // same id and key as the web-app APK, so this installs as an update
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "2.1"
    }

    signingConfigs {
        // Passwords come from the environment, typed by Jose in build-native.ps1; never stored in the project.
        create("release") {
            storeFile = file("../../sportclock-android/android.keystore")
            storePassword = System.getenv("SC_KS_PASS")
            keyAlias = "android"
            keyPassword = System.getenv("SC_KEY_PASS")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // -PtestSign builds the full release pipeline with the debug key, to check it without Jose's passwords
            signingConfig = signingConfigs.getByName(if (project.hasProperty("testSign")) "debug" else "release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

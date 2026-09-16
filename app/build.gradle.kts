import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing values live in local.properties (git-ignored) so the keystore path and
// passwords never reach the repo. Without them, release builds just come out unsigned.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.kjt.cutmoa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kjt.cutmoa"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        localProps.getProperty("RELEASE_STORE_FILE")?.let { storePath ->
            create("release") {
                storeFile = file(storePath)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    val cameraxVersion = "1.4.1"
    val media3Version = "1.5.0"
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // CameraX
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Media3 (merge/transform + overlay rendering, later steps)
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    // Editor preview playback (already pulled in by media3-transformer; declared since we use it directly)
    implementation("androidx.media3:media3-exoplayer:$media3Version")

    // Runtime permission handling in Compose
    implementation("com.google.accompanist:accompanist-permissions:0.37.0")

    // Screen navigation (record -> merge -> overlay editor)
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Background merge/export work, survives process death + reports progress
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Compose util for gesture-driven overlay drag/scale
    implementation("androidx.compose.foundation:foundation")

    testImplementation("junit:junit:4.13.2")
}

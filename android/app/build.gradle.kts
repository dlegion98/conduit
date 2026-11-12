plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.conduit"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.conduit"
        minSdk = 31
        targetSdk = 36
        // CI passes the commit count, so every release has a higher build number than the last.
        val build = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.0.$build"
    }

    signingConfigs {
        create("conduit") {
            // Stable key from CI secrets so in-app updates install over the old version.
            val ks = System.getenv("CONDUIT_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("CONDUIT_KEYSTORE_PASSWORD")
                keyAlias = "conduit"
                keyPassword = System.getenv("CONDUIT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("CONDUIT_KEYSTORE") != null) signingConfigs.getByName("conduit")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Foldables: hinge position and posture (tabletop / book)
    implementation("androidx.window:window:1.4.0")
    // Google code scanner: QR scanning without a camera permission (needs Play services)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Screenshot tests (Robolectric renders the real Compose UI on the JVM)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

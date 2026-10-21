plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Every CI build gets a higher version number, so the phone accepts it as an update.
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1

android {
    namespace = "com.jarvis.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // Modern phones are all 64-bit ARM; skipping other CPU types keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("jarvis") {
            // CI uses your private key from GitHub secrets when you add one (see docs/lessons/phase-1.md).
            // Otherwise it falls back to the shared development key, so each new APK installs over the old one.
            val storePath = providers.environmentVariable("JARVIS_KEYSTORE_FILE").orNull
            storeFile = if (storePath != null) file(storePath) else file("jarvis-dev.keystore")
            storePassword = providers.environmentVariable("JARVIS_KEYSTORE_PASSWORD").orNull ?: "jarvis-dev"
            keyAlias = providers.environmentVariable("JARVIS_KEY_ALIAS").orNull ?: "jarvis"
            keyPassword = providers.environmentVariable("JARVIS_KEY_PASSWORD").orNull ?: "jarvis-dev"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("jarvis")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/INDEX.LIST",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Google's on-device LLM runtime (offline brain).
    implementation(libs.litertlm.android)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets GITHUB_RUN_NUMBER so every build installs over the previous one.
val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.casa.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.casa.tv"
        minSdk = 22
        targetSdk = 34
        versionCode = 200 + build
        versionName = "2.0.$build"
    }

    // The signing key comes from the repo's Actions secrets (see .github/workflows/tv-app.yml), so
    // every build updates the installed app. Without it the build falls back to a throwaway debug
    // key, and installing it means uninstalling the previous one first.
    val keystore = System.getenv("TV_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("TV_KEYSTORE_PASSWORD")
            keyAlias = "casatv"
            keyPassword = System.getenv("TV_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**") }
    }
}

dependencies {
    implementation("dev.mobile:dadb:1.2.10")
    implementation("com.google.zxing:core:3.5.3")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ehoeve.canlens"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ehoeve.canlens"
        minSdk = 29
        targetSdk = 36
        // CI passes the run number, so every CI build installs over the previous one; a local build is 1 unless
        // given -PversionCode, so it installs over a CI build only with a higher number
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1." + versionCode
    }

    // The release key, when CI has one in its secrets (RELEASE_KEYSTORE is the path of the keystore file it writes).
    val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE").orNull
    signingConfigs {
        // A key kept in the repository: debug builds from CI and from any PC are signed alike and update each other.
        // Anyone with the repository can sign with it, so it is not for builds handed out to others.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        // with the release key when there is one; without it (a local build, a fork) with the debug key as before
        release { signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,LICENSE*,NOTICE*}" } }
}

// AGP 9 compiles Kotlin itself; its JVM target follows compileOptions
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    implementation(libs.commons.compress)
    // reading a number off the car's screen: the camera, and Google's on-device text reader (its model comes through Play services)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition)
}

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release key: from environment variables (CI secrets) or a local, untracked keystore.properties.
val releaseKey: Map<String, String> = run {
    val file = rootProject.file("keystore.properties")
    val local = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    mapOf(
        "storeFile" to "RELEASE_STORE_FILE",
        "storePassword" to "RELEASE_STORE_PASSWORD",
        "keyAlias" to "RELEASE_KEY_ALIAS",
        "keyPassword" to "RELEASE_KEY_PASSWORD",
    ).mapNotNull { (name, env) ->
        (System.getenv(env)?.takeIf { it.isNotBlank() } ?: local.getProperty(name))?.let { name to it }
    }.toMap()
}
val hasReleaseKey = releaseKey.size == 4 && rootProject.file(releaseKey.getValue("storeFile")).exists()

android {
    namespace = "com.ytbg.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ytbg.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
    }

    signingConfigs {
        // Fixed debug key, committed on purpose: every CI build gets the same
        // signature, so a new APK installs over the old one without uninstalling.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getValue("storeFile"))
                storePassword = releaseKey.getValue("storePassword")
                keyAlias = releaseKey.getValue("keyAlias")
                keyPassword = releaseKey.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key the APK is still installable, just signed with the public debug key.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    // The Google-encrypted dependency list is only read by Google Play; other stores reject or ignore it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
}

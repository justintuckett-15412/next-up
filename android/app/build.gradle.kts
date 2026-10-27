plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Filled in by the GitHub Action; defaults let it build anywhere.
fun prop(name: String, default: String): String =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

val repoSlug = prop("repoSlug", "justintuckett-15412/next-up")
val buildNumber = prop("buildNumber", "1").toInt()
val dataFile = prop("calFile", "episodes.ics").replace(Regex("\\.ics$", RegexOption.IGNORE_CASE), "") + ".json"
val pagesUrl = "https://${repoSlug.substringBefore('/').lowercase()}.github.io/${repoSlug.substringAfter('/')}"

android {
    namespace = "io.github.justintuckett.nextup"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.justintuckett.nextup"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        buildConfigField("String", "DATA_URL", "\"$pagesUrl/$dataFile\"")
        buildConfigField("String", "REPO", "\"$repoSlug\"")
    }

    // The release key comes from GitHub secrets (decoded to release.jks by the workflow),
    // so each new APK installs over the last one. Without it, falls back to a debug key.
    val keyPass = System.getenv("ANDROID_KEYSTORE_PASSWORD").orEmpty()
    val hasReleaseKey = file("release.jks").exists() && keyPass.isNotEmpty()
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file("release.jks")
                storePassword = keyPass
                keyAlias = "nextup"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}

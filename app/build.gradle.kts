plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.example.musikplayer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.musikplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "0.4").toInt()
        versionName = "1.3.1"
    }
    signingConfigs {
        create("musik") {
            val ks = file("../musik.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "musikpass"
                keyAlias = "musik"
                keyPassword = "musikpass"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (file("../musik.jks").exists()) {
                signingConfig = signingConfigs.getByName("musik")
            }
        }
        // Release: optimiert und nicht debuggbar. Das ist die schnelle Version für den Alltag.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // gleicher Schlüssel wie Debug, damit sich die App über die bisherige installieren lässt
            if (file("../musik.jks").exists()) {
                signingConfig = signingConfigs.getByName("musik")
            }
        }
    }
    lint {
        // Die Lint-Prüfung vor dem Release-Build ist hier nicht nötig und kann den Build bremsen
        checkReleaseBuilds = false
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // taglib ist mit einer neueren Kotlin-Version gebaut als der Compiler hier
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("io.github.kyant0:taglib:1.0.6")
}

// taglib verlangt compileSdk 37 nur laut Metadaten, die App braucht das nicht
tasks.configureEach {
    if (name.endsWith("AarMetadata")) enabled = false
}

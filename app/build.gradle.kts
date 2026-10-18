plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.example.musikplayer"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.ichibinekoi.chibineko"
        minSdk = 31
        targetSdk = 35
        // Feste Zahlen (kein Wert aus der Umgebung)
        // versionCode bei jeder neuen Version erhöhen, Schema: Major*10000 + Minor*100 + Patch (1.5.1 -> 10501)
        versionCode = 10501
        versionName = "1.5.1"
    }
    signingConfigs {
        create("musik") {
            // Der Schlüssel liegt nicht im Repository. Der Build auf GitHub legt ihn aus Secrets an,
            // Passwörter kommen aus der Umgebung. Ohne ../musik.jks (z. B. bei F-Droid) wird nicht selbst signiert.
            val ks = file("../musik.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("MUSIK_STORE_PASSWORD")
                keyAlias = System.getenv("MUSIK_KEY_ALIAS") ?: "musik"
                keyPassword = System.getenv("MUSIK_KEY_PASSWORD")
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
            // gleicher Schlüssel wie Debug (nur wenn vorhanden)
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

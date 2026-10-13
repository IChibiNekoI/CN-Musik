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
        versionName = "0.4.2"
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
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
}

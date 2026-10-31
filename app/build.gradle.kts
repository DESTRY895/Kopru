plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI her derlemeye artan numara verir; böylece telefondaki eski sürümün üzerine kurulur.
val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.xdestrys.kopru"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.xdestrys.kopru"
        minSdk = 29
        targetSdk = 36
        versionCode = buildNo
        versionName = "1.$buildNo"
        // Derlemede Actions secret'ından gelir; kaynakta token yok.
        buildConfigField("String", "KOPRU_TOKEN", "\"${System.getenv("KOPRU_TOKEN") ?: ""}\"")
    }

    // Sabit anahtar: CI'da her derleme aynı imzayı taşır, güncelleme veri silmeden kurulur.
    signingConfigs {
        create("kopru") {
            storeFile = file("kopru.jks")
            storePassword = "kopru123"
            keyAlias = "kopru"
            keyPassword = "kopru123"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("kopru")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        debug {
            signingConfig = signingConfigs.getByName("kopru")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.zxing:core:3.5.3")
}

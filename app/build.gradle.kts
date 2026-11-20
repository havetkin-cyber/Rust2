plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlin.compose)
}

// číslo verzie dáva GitHub Actions (-PversionCode=…), lokálne 1
val kodVerzie = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "sk.alexia.ai"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "sk.alexia.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = kodVerzie
        versionName = "1.0.$kodVerzie"
        ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }

    // Kľúč na podpis je v repozitári, aby GitHub vedel podpísať každú verziu rovnako (aktualizácie sa dajú
    // nainštalovať cez starú verziu). Pre súkromnú appku stačí; na zverejnenie ho treba presunúť do GitHub Secrets.
    signingConfigs {
        create("alexia") {
            storeFile = file("alexia-ai.jks")
            storePassword = "alexiaai2026"
            keyAlias = "alexia-ai"
            keyPassword = "alexiaai2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("alexia")
        }
        debug {
            signingConfig = signingConfigs.getByName("alexia")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
}

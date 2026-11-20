plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlinAndroid)
}

// Tvorba obrázkov (stable-diffusion.cpp). Samostatný modul = samostatné CMake zostavenie,
// lebo stable-diffusion.cpp má vlastnú upravenú knižnicu ggml (inú ako llama.cpp).
android {
    namespace = "sk.alexia.ai.obrazky"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters.addAll(listOf("arm64-v8a")) }
        externalNativeBuild {
            cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static") }
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
}

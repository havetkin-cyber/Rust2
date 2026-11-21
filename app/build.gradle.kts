import java.net.URI
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
        ndk { abiFilters.addAll(listOf("arm64-v8a")) }
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
    // sherpa-onnx (Apache 2.0) – hlasy Supertonic: Kotlin API + natívne knižnice (arm64), pripraví úloha sherpaPriprav
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("sherpa/kotlin").get().asFile)
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("sherpa/jni").get().asFile)
}

val sherpaPriprav by tasks.registering {
    val out = layout.buildDirectory.dir("sherpa").get().asFile
    outputs.dir(out)
    doLast {
        val verzia = "v2.0.2"
        val kt = File(out, "kotlin/com/k2fsa/sherpa/onnx/Tts.kt")
        if (!kt.exists()) {
            kt.parentFile.mkdirs()
            URI("https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/$verzia/sherpa-onnx/kotlin-api/Tts.kt").toURL().openStream().use { i -> kt.outputStream().use { i.copyTo(it) } }
        }
        val jni = File(out, "jni/arm64-v8a")
        if (!File(jni, "libsherpa-onnx-jni.so").exists()) {
            val arch = File(temporaryDir, "sherpa-android.tar.bz2")
            URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/$verzia/sherpa-onnx-$verzia-android.tar.bz2").toURL().openStream().use { i -> arch.outputStream().use { i.copyTo(it) } }
            project.copy {
                from(tarTree(resources.bzip2(arch))) {
                    include("**/jniLibs/arm64-v8a/*.so")
                    eachFile { path = name }
                    includeEmptyDirs = false
                }
                into(jni)
            }
            arch.delete()
        }
    }
}
tasks.named("preBuild") { dependsOn(sherpaPriprav) }

dependencies {
    implementation(project(":obrazky"))
    implementation("org.apache.commons:commons-compress:1.27.1")   // rozbalenie hlasov (.tar.bz2)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
}

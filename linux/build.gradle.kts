import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

val verzia = (project.findProperty("verzia") as String?) ?: "1.0.0"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "sk.alexia.ai.MainKt"
        jvmArgs += listOf("-Xmx4g", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "AlexiaAI"
            packageVersion = verzia
            description = "Alexia AI – lokálny AI chat a obrázky (offline)"
            vendor = "havetkin-cyber"
            // natívne knižnice (llama.cpp a stable-diffusion.cpp s Vulkanom) – resources/linux-x64
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.desktop", "java.prefs", "jdk.httpserver")
            linux { iconFile.set(project.file("ikona.png")) }
        }
    }
}

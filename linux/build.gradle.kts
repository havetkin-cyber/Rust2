import java.net.URI
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
    implementation("org.apache.commons:commons-compress:1.27.1")   // rozbalenie hlasov (.tar.bz2)
}

// Java API sherpa-onnx (Apache 2.0) – hlasy Supertonic; zdrojáky sa stiahnu pri zostavení, natívne knižnice si appka stiahne sama
val sherpaJava = layout.buildDirectory.dir("sherpa-java")
val sherpaZdroje by tasks.registering {
    outputs.dir(sherpaJava)
    doLast {
        val out = sherpaJava.get().asFile
        if (File(out, "com/k2fsa/sherpa/onnx/OfflineTts.java").exists()) return@doLast
        val tgz = File(temporaryDir, "sherpa.tgz")
        URI("https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v2.0.2").toURL().openStream().use { i -> tgz.outputStream().use { i.copyTo(it) } }
        project.copy {
            from(tarTree(resources.gzip(tgz))) {
                include("*/sherpa-onnx/java-api/src/main/java/**")
                eachFile { path = path.substringAfter("src/main/java/") }
                includeEmptyDirs = false
            }
            into(out)
        }
        tgz.delete()
    }
}
sourceSets.main { java.srcDir(sherpaZdroje) }
kotlin.sourceSets.named("main") { kotlin.srcDir(sherpaZdroje) }

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

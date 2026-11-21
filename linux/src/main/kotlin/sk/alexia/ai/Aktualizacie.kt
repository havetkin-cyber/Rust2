package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Aktualizácie pre Linux. AppImage sa vymení sám (stiahne nový súbor, nahradí sa, reštartuje).
 * Verzia zo ZIP otvorí stiahnutie v prehliadači.
 */
object Aktualizacie {
    /** stály kanál (vydanie „aktualizacie“) – Windows a Linux sa tam nahrávajú vždy, nezávisle od vydaní APK */
    private const val BASE = "https://github.com/havetkin-cyber/Rust2/releases/download/aktualizacie/"
    private val appImage: String? = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() && File(it).exists() }
    val mojaVerzia: String = System.getProperty("jpackage.app-version") ?: "0"

    var novaVerzia by mutableStateOf<String?>(null)
    var priebeh by mutableStateOf<Float?>(null)
        private set
    var chyba by mutableStateOf("")
        private set
    val samaSaVymeni get() = appImage != null

    private fun cislo(v: String) = v.trim().split('.').mapNotNull { it.toIntOrNull() }.let { it + List(3) { 0 } }.take(3)
    private fun novsia(a: String, b: String): Boolean {
        val x = cislo(a); val y = cislo(b)
        for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }

    private fun otvor(url: String): HttpURLConnection {
        var u = URL(url); var skoky = 0
        while (true) {
            val k = u.openConnection() as HttpURLConnection
            k.instanceFollowRedirects = false; k.connectTimeout = 15_000; k.readTimeout = 60_000
            k.setRequestProperty("User-Agent", "AlexiaAI (Linux)")
            val kod = k.responseCode
            if (kod in 300..399 && skoky++ < 8) { u = URL(u, k.getHeaderField("Location")); k.disconnect(); continue }
            if (kod != 200) throw IllegalStateException("Server vrátil $kod")
            return k
        }
    }

    var kontroluje by mutableStateOf(false)
        private set

    /** rucne = z Nastavení: ukáže aj preskočenú verziu a napíše, keď je appka aktuálna */
    fun skontroluj(rucne: Boolean = false) {
        if (kontroluje) return
        kontroluje = true
        thread(isDaemon = true) {
            val r = runCatching {
                val v = otvor(BASE + if (Platforma.jeWindows) "windows-verzia.txt" else "linux-verzia.txt").inputStream.bufferedReader().readText().trim()
                val preskocena = File(Platforma.priecinok, "preskocit-verziu").takeIf { it.exists() }?.readText()?.trim()
                val nova = novsia(v, mojaVerzia) && (rucne || v != preskocena)
                Platforma.naHlavnom {
                    if (nova && ChatJadro.nastavenia.autoAktualizacie && !rucne && moze()) stiahniTicho(v)
                    else if (nova) { chyba = ""; novaVerzia = v }
                    else if (rucne) Platforma.oznam(T("akt_najnovsia", mojaVerzia))
                }
            }
            Platforma.naHlavnom {
                kontroluje = false
                if (rucne) r.onFailure { Platforma.oznam(T("akt_nepodarilo")) }
            }
        }
    }

    // ------------------------------------------------ automatická aktualizácia

    /** verzia, ktorá je už stiahnutá a čaká na reštart */
    var pripravena by mutableStateOf<String?>(null)
    private val docasny get() = File(Platforma.priecinok, "aktualizacia")
    private val zipKoren: String = if (Platforma.jeWindows) "AlexiaAI-Windows" else "AlexiaAI-Linux"

    /** priečinok, kam je appka rozbalená zo ZIP (podľa cesty spúšťača) */
    private fun instalacia(): File? {
        val app = System.getProperty("jpackage.app-path")?.let { File(it) } ?: return null
        var d: File? = app.parentFile
        while (d != null) { if (d.name == zipKoren && d.canWrite()) return d; d = d.parentFile }
        return null
    }

    /** dá sa aktualizovať samo? (AppImage alebo rozbalený ZIP s pôvodným názvom priečinka) */
    fun moze() = appImage != null || instalacia() != null

    private fun stiahniTicho(v: String) {
        if (priebeh != null || pripravena != null) return
        thread(isDaemon = true) {
            val r = runCatching {
                val ciel = appImage
                if (ciel != null) {
                    val k = otvor(BASE + "AlexiaAI-x86_64.AppImage"); val spolu = k.contentLengthLong
                    val tmp = File("$ciel.novy")
                    k.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } }
                    if (spolu > 0 && tmp.length() != spolu) { tmp.delete(); throw IllegalStateException("neúplné") }
                    tmp.setExecutable(true, false)
                    // bežiaci AppImage sa dá prepísať – nová verzia sa spustí pri ďalšom štarte
                    if (!tmp.renameTo(File(ciel))) { tmp.delete(); throw IllegalStateException("nedá sa prepísať") }
                } else {
                    val d = docasny.apply { deleteRecursively(); mkdirs() }
                    val zip = File(d, "nova.zip")
                    val k = otvor(BASE + "$zipKoren.zip"); val spolu = k.contentLengthLong
                    k.inputStream.use { i -> zip.outputStream().use { i.copyTo(it, 1 shl 16) } }
                    if (spolu > 0 && zip.length() != spolu) throw IllegalStateException("neúplné")
                    val von = File(d, "nova")
                    java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            val f = File(von, e.name)
                            if (!f.canonicalPath.startsWith(von.canonicalPath)) continue
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                        }
                    }
                    zip.delete()
                    if (!File(von, zipKoren).isDirectory) throw IllegalStateException("zlý ZIP")
                    File(d, "verzia.txt").writeText(v)
                }
            }
            Platforma.naHlavnom { r.onSuccess { pripravena = v }; r.onFailure { novaVerzia = v } }
        }
    }

    /** pri štarte: ZIP aktualizácia stiahnutá minule, ešte nenainštalovaná */
    fun skontrolujPripravenu() {
        val v = runCatching { File(docasny, "verzia.txt").readText().trim() }.getOrNull() ?: return
        if (novsia(v, mojaVerzia) && File(docasny, "nova/$zipKoren").isDirectory && instalacia() != null) pripravena = v
        else docasny.deleteRecursively()
    }

    /** reštart do novej verzie */
    fun restartuj() {
        val ciel = appImage
        if (ciel != null) { runCatching { ProcessBuilder(ciel).start() }; exitProcess(0) }
        val inst = instalacia() ?: return
        val zdroj = File(docasny, "nova/$zipKoren")
        if (Platforma.jeWindows) {
            val bat = File(docasny, "aktualizuj.bat")
            bat.writeText("@echo off\r\ntimeout /t 3 /nobreak >nul\r\nrobocopy \"${zdroj.path}\" \"${inst.path}\" /E /NFL /NDL /NJH /NJS /NP >nul\r\n" +
                "start \"\" \"${File(inst, "AlexiaAI.exe").path}\"\r\nrmdir /s /q \"${File(docasny, "nova").path}\"\r\ndel \"${File(docasny, "verzia.txt").path}\"\r\n")
            ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", bat.path).start()
        } else {
            val sh = File(docasny, "aktualizuj.sh")
            sh.writeText("#!/bin/sh\nsleep 2\ncp -a \"${zdroj.path}/.\" \"${inst.path}/\"\nrm -rf \"${File(docasny, "nova").path}\" \"${File(docasny, "verzia.txt").path}\"\n" +
                "nohup \"${File(inst, "AlexiaAI/bin/AlexiaAI").path}\" >/dev/null 2>&1 &\n")
            sh.setExecutable(true)
            ProcessBuilder("setsid", "sh", sh.path).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }
        exitProcess(0)
    }

    fun preskoc() {
        novaVerzia?.let { runCatching { File(Platforma.priecinok, "preskocit-verziu").writeText(it) } }
        novaVerzia = null
    }

    fun aktualizuj() {
        val ciel = appImage
        if (ciel == null) {
            // verzia zo ZIP – nový ZIP stiahne prehliadač
            Platforma.otvorOdkaz(BASE + if (Platforma.jeWindows) "AlexiaAI-Windows.zip" else "AlexiaAI-Linux.zip")
            novaVerzia = null
            return
        }
        if (priebeh != null) return
        priebeh = 0f; chyba = ""
        thread {
            val r = runCatching {
                val k = otvor(BASE + "AlexiaAI-x86_64.AppImage")
                val spolu = k.contentLengthLong
                val tmp = File("$ciel.novy")
                var h = 0L
                k.inputStream.use { i -> tmp.outputStream().use { o ->
                    val b = ByteArray(1 shl 16); var n: Int
                    while (i.read(b).also { n = it } > 0) {
                        o.write(b, 0, n); h += n
                        if (spolu > 0) { val p = h.toFloat() / spolu; Platforma.naHlavnom { priebeh = p } }
                    }
                } }
                if (spolu > 0 && tmp.length() != spolu) { tmp.delete(); throw IllegalStateException("Súbor sa nestiahol celý") }
                tmp.setExecutable(true, false)
                // bežiaci AppImage sa dá nahradiť (beží z pripojenej kópie), potom sa spustí nová verzia
                if (!tmp.renameTo(File(ciel))) { tmp.delete(); throw IllegalStateException("Nedá sa prepísať $ciel") }
            }
            Platforma.naHlavnom {
                r.onSuccess {
                    runCatching { ProcessBuilder(ciel).start() }
                    exitProcess(0)
                }
                r.onFailure { priebeh = null; chyba = it.message ?: "Aktualizácia zlyhala" }
            }
        }
    }
}

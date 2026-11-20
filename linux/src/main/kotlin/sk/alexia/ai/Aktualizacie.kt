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
    private const val BASE = "https://github.com/havetkin-cyber/rust2/releases/latest/download/"
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
                    if (nova) { chyba = ""; novaVerzia = v }
                    else if (rucne) Platforma.oznam(T("akt_najnovsia", mojaVerzia))
                }
            }
            Platforma.naHlavnom {
                kontroluje = false
                if (rucne) r.onFailure { Platforma.oznam(T("akt_nepodarilo")) }
            }
        }
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

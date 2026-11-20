package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Hlas: odpovede nahlas, offline, slovenský ženský hlas „Lili“ (Piper, MIT; hlas z rhasspy/piper-voices).
 * Piper sa stiahne raz do priečinka appky a spúšťa sa ako samostatný program.
 */
object Hlas {
    private const val PIPER_LINUX = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz"
    private const val PIPER_WINDOWS = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip"
    private const val HLAS = "https://huggingface.co/rhasspy/piper-voices/resolve/main/sk/sk_SK/lili/medium/sk_SK-lili-medium.onnx"
    const val MB = 90
    private const val VZORKOVANIE = 22050f   // sk_SK-lili-medium

    var priebeh by mutableStateOf<Float?>(null)
        private set
    var chyba by mutableStateOf("")
        private set
    var hovori by mutableStateOf<String?>(null)   // text, ktorý práve číta (na tlačidlo stop)
        private set
    @Volatile private var proces: Process? = null
    @Volatile private var linka: SourceDataLine? = null

    private fun priecinok() = File(Platforma.priecinok, "hlas").apply { mkdirs() }
    private fun program() = File(priecinok(), if (Platforma.jeWindows) "piper/piper.exe" else "piper/piper")
    private fun model() = File(priecinok(), "sk_SK-lili-medium.onnx")
    fun mam() = program().exists() && model().length() > 10_000_000 && File(model().path + ".json").exists()

    fun stiahni() {
        if (priebeh != null) return
        priebeh = 0f; chyba = ""
        Thread {
            val r = runCatching {
                val d = priecinok()
                if (!program().exists()) {
                    val arch = File(d, if (Platforma.jeWindows) "piper.zip" else "piper.tar.gz")
                    stiahniSubor(if (Platforma.jeWindows) PIPER_WINDOWS else PIPER_LINUX, arch) { Platforma.naHlavnom { priebeh = it * 0.25f } }
                    if (Platforma.jeWindows) rozbalZip(arch, d)
                    else { val p = ProcessBuilder("tar", "-xzf", arch.path, "-C", d.path).redirectErrorStream(true).start(); p.waitFor() }
                    arch.delete()
                    program().setExecutable(true)
                    if (!program().exists()) throw IllegalStateException("Piper sa nepodarilo rozbaliť")
                }
                if (!File(model().path + ".json").exists()) stiahniSubor("$HLAS.json", File(model().path + ".json")) {}
                if (model().length() < 10_000_000) stiahniSubor(HLAS, model()) { Platforma.naHlavnom { priebeh = 0.25f + it * 0.75f } }
            }
            Platforma.naHlavnom { priebeh = null; r.exceptionOrNull()?.let { chyba = it.message ?: "Hlas sa nepodarilo stiahnuť" } }
        }.start()
    }

    private fun stiahniSubor(odkaz: String, ciel: File, p: (Float) -> Unit) {
        var url = URL(odkaz); var k: HttpURLConnection; var skoky = 0
        while (true) {
            k = url.openConnection() as HttpURLConnection
            k.instanceFollowRedirects = false; k.connectTimeout = 20_000; k.readTimeout = 60_000
            k.setRequestProperty("User-Agent", "AlexiaAI")
            val kod = k.responseCode
            if (kod in 300..399 && skoky++ < 8) { url = URL(url, k.getHeaderField("Location")); k.disconnect(); continue }
            if (kod != 200) throw IllegalStateException("Server vrátil $kod")
            break
        }
        val spolu = k.contentLengthLong; var h = 0L
        val tmp = File(ciel.path + ".part")
        k.inputStream.use { i -> tmp.outputStream().use { o ->
            val b = ByteArray(1 shl 16); var n: Int
            while (i.read(b).also { n = it } > 0) { o.write(b, 0, n); h += n; if (spolu > 0) p(h.toFloat() / spolu) }
        } }
        if (spolu > 0 && tmp.length() != spolu) { tmp.delete(); throw IllegalStateException("Súbor sa nestiahol celý") }
        ciel.delete(); tmp.renameTo(ciel)
    }

    private fun rozbalZip(zip: File, kam: File) {
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(kam, e.name)
                if (!f.canonicalPath.startsWith(kam.canonicalPath)) continue
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
    }

    /** text na čítanie: bez kódu, odkazov, premýšľania a značiek */
    fun naCitanie(t: String): String {
        var s = t.replace(Regex("<think>[\\s\\S]*?(</think>|$)"), " ")
        s = s.replace(Regex("```[\\s\\S]*?(```|$)"), ". ")
        s = s.replace(Regex("https?://\\S+"), " ")
        s = s.replace(Regex("[*#_`>|~]"), " ").replace(Regex("[ \\t]+"), " ")
        return s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun hovor(text: String) {
        zastav()
        if (!mam()) { Platforma.oznam(T("hlas_najprv")); return }
        val t = naCitanie(text); if (t.isBlank()) return
        hovori = text
        Thread {
            runCatching {
                val pb = ProcessBuilder(program().path, "--model", model().path, "--output_raw", "--espeak_data", File(program().parentFile, "espeak-ng-data").path)
                    .directory(program().parentFile)
                if (!Platforma.jeWindows) pb.environment()["LD_LIBRARY_PATH"] = program().parentFile.path
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                val p = pb.start(); proces = p
                Thread { runCatching { p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(t); it.newLine() } } }.start()
                val f = AudioFormat(VZORKOVANIE, 16, 1, true, false)
                val l = AudioSystem.getSourceDataLine(f); linka = l
                l.open(f); l.start()
                p.inputStream.use { i ->
                    val b = ByteArray(4096); var n: Int
                    while (i.read(b).also { n = it } > 0) l.write(b, 0, n - n % 2)
                }
                l.drain(); l.close()
            }.onFailure { e -> if (hovori != null) Platforma.oznam(T("hlas_chyba", e.message ?: "")) }
            Platforma.naHlavnom { if (hovori == text) hovori = null }
        }.start()
    }

    fun zastav() {
        hovori = null
        proces?.destroy(); proces = null
        linka?.let { runCatching { it.stop(); it.flush(); it.close() } }; linka = null
    }

    /** po odpovedi v chate – prečíta ju, ak je to zapnuté */
    fun poOdpovedi(text: String) { if (ChatJadro.nastavenia.citatNahlas && mam() && text.isNotBlank() && !text.startsWith("⚠")) hovor(text) }
}

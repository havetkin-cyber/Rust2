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
 * Hlas: odpovede nahlas, offline.
 *  - „Lili“ – Piper (MIT; hlas z rhasspy/piper-voices)
 *  - Ema, Nina, Sofia, Zara, Hana – Supertonic 3 (OpenRAIL-M) cez sherpa-onnx (Apache 2.0), hovoriaci 0–4, jazyk „sk“
 * Programy a hlasy sa stiahnu raz do priečinka appky a spúšťajú sa ako samostatné programy.
 * Výška hlasu: zvuk sa prehrá rýchlejšie/pomalšie (vyššie/nižšie) a rýchlosť reči sa o to isté vyrovná pri tvorbe.
 */
object Hlas {
    /** id hlasu → meno; „stN“ = hovoriaci N zo Supertonic 3 */
    val HLASY = listOf("lili" to "Lili", "st0" to "Ema", "st1" to "Nina", "st2" to "Sofia", "st3" to "Zara", "st4" to "Hana")
    fun meno(id: String) = HLASY.firstOrNull { it.first == id }?.second ?: "Lili"
    const val ST_MB = 140
    private const val SHERPA = "v2.0.2"
    // natívne knižnice pre Java API sherpa-onnx (triedy com.k2fsa.sherpa.onnx sú v appke, pribalia sa pri zostavení)
    private const val SHERPA_LINUX = "https://github.com/k2-fsa/sherpa-onnx/releases/download/$SHERPA/sherpa-onnx-native-lib-linux-x64-2.0.2.jar"
    private const val SHERPA_WINDOWS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/$SHERPA/sherpa-onnx-native-lib-win-x64-2.0.2.jar"
    private const val ST_MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"

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
    private fun jeSt(id: String) = id.startsWith("st")
    internal fun sherpaDir() = File(priecinok(), "sherpa")
    private fun sherpa() = File(sherpaDir(), System.mapLibraryName("sherpa-onnx-jni"))
    private fun stDir() = File(priecinok(), "supertonic")
    private fun mamSt() = sherpa().exists() && File(stDir(), "vector_estimator.int8.onnx").length() > 50_000_000 && File(stDir(), "voice.bin").exists()
    private fun mamLili() = program().exists() && model().length() > 10_000_000 && File(model().path + ".json").exists()
    fun mam(id: String = ChatJadro.nastavenia.hlas) = if (jeSt(id)) mamSt() else mamLili()

    fun stiahni(id: String = ChatJadro.nastavenia.hlas) {
        if (jeSt(id)) { stiahniSt(); return }
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

    private fun stiahniSt() {
        if (priebeh != null) return
        priebeh = 0f; chyba = ""
        Thread {
            val r = runCatching {
                pripravSherpa { Platforma.naHlavnom { priebeh = it * 0.1f } }
                if (!mamSt()) {
                    val arch = File(priecinok(), "supertonic.tar.bz2")
                    stiahniSubor(ST_MODEL, arch) { Platforma.naHlavnom { priebeh = 0.1f + it * 0.85f } }
                    Platforma.naHlavnom { priebeh = 0.97f }
                    rozbalTarBz2(arch, stDir()) { true }
                    arch.delete()
                    if (!mamSt()) throw IllegalStateException("Hlasy sa nepodarilo rozbaliť")
                }
            }
            Platforma.naHlavnom { priebeh = null; r.exceptionOrNull()?.let { chyba = it.message ?: "Hlas sa nepodarilo stiahnuť" } }
        }.start()
    }

    internal fun mamSherpa() = sherpa().exists()

    /** natívne knižnice sherpa-onnx (spoločné pre hlasy aj rozpoznávanie reči); blokuje */
    internal fun pripravSherpa(p: (Float) -> Unit) {
        if (!sherpa().exists()) {
            val arch = File(priecinok(), "sherpa.jar")
            stiahniSubor(if (Platforma.jeWindows) SHERPA_WINDOWS else SHERPA_LINUX, arch, p)
            // z jar-u len knižnice (sherpa-onnx/native/<os>/…), naplocho
            sherpaDir().mkdirs()
            ZipInputStream(arch.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory || !e.name.startsWith("sherpa-onnx/native/")) continue
                    File(sherpaDir(), e.name.substringAfterLast('/')).outputStream().use { z.copyTo(it) }
                }
            }
            arch.delete()
            if (!sherpa().exists()) throw IllegalStateException("sherpa-onnx sa nepodarilo rozbaliť")
        }
        System.setProperty("sherpa_onnx.native.path", sherpaDir().path)
    }

    /** rozbalí .tar.bz2 bez prvého priečinka; ber(cesta) rozhodne, či súbor treba */
    internal fun rozbalTarBz2(arch: File, kam: File, ber: (String) -> Boolean) {
        kam.mkdirs()
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(arch.inputStream().buffered(1 shl 16))
        ).use { t ->
            while (true) {
                val e = t.nextEntry ?: break
                val cesta = e.name.substringAfter('/', "")
                if (cesta.isEmpty() || e.isDirectory || !ber(cesta)) continue
                val f = File(kam, cesta)
                if (!f.canonicalPath.startsWith(kam.canonicalPath)) continue
                f.parentFile?.mkdirs()
                if (e.isSymbolicLink) { runCatching { java.nio.file.Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(e.linkName)) }; continue }
                f.outputStream().use { t.copyTo(it) }
                if (e.mode and 0b001_000_000 != 0) f.setExecutable(true)
            }
        }
    }

    internal fun stiahniSubor(odkaz: String, ciel: File, p: (Float) -> Unit) {
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

    /**
     * Novšie súbory hlasov (piper-voices) majú v phoneme_id_map aj viacznakové fonémy („aɪ“…),
     * s ktorými starší Piper spadne („Phonemes must be one codepoint“). Tieto položky odstránime – Piper ich aj tak nepoužíva.
     */
    private fun opravKonfig() = runCatching {
        val f = File(model().path + ".json")
        val j = org.json.JSONObject(f.readText())
        val m = j.getJSONObject("phoneme_id_map")
        val zle = m.keys().asSequence().filter { it.codePointCount(0, it.length) != 1 }.toList()
        if (zle.isNotEmpty()) { zle.forEach { m.remove(it) }; f.writeText(j.toString()) }
    }

    /** po vetách do kúskov (~250 znakov), aby prvý zvuk začal rýchlo */
    private fun kusky(t: String): List<String> {
        val vety = t.replace("\n", " ").split(Regex("(?<=[.!?…:;])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>(); val b = StringBuilder()
        for (v in vety) { if (b.isNotEmpty() && b.length + v.length > 250) { out += b.toString(); b.clear() }; if (b.isNotEmpty()) b.append(' '); b.append(v) }
        if (b.isNotEmpty()) out += b.toString()
        return out
    }

    fun hovor(text: String, id: String = ChatJadro.nastavenia.hlas, poKonci: (() -> Unit)? = null) {
        zastav()
        if (!mam(id)) { Platforma.oznam(T("hlas_najprv")); return }
        val t = naCitanie(text); if (t.isBlank()) { poKonci?.invoke(); return }
        hovori = text; stChyba = false
        val n = ChatJadro.nastavenia
        val vyska = n.hlasVyska.coerceIn(0.5f, 2f); val rychlost = n.hlasRychlost.coerceIn(0.3f, 3f)
        Thread {
            val ok = runCatching {
                if (jeSt(id)) hovorSt(text, t, id.drop(2).toIntOrNull() ?: 0, rychlost / vyska, vyska)
                else hovorLili(t, vyska / rychlost, vyska)
            }.onFailure { e -> if (hovori != null) Platforma.oznam(T("hlas_chyba", e.message ?: "")) }.isSuccess
            val dohovoril = ok && hovori == text && !stChyba
            Platforma.naHlavnom { if (hovori == text) hovori = null; if (dohovoril) poKonci?.invoke() }
        }.start()
    }

    private fun otvorLinku(sr: Float): SourceDataLine {
        val f = AudioFormat(sr, 16, 1, true, false)
        val l = AudioSystem.getSourceDataLine(f); linka = l
        l.open(f); l.start(); return l
    }

    private fun hovorLili(t: String, dlzka: Float, vyska: Float) {
        opravKonfig()
        val pb = ProcessBuilder(program().path, "--model", model().path, "--output_raw", "--length_scale", "%.3f".format(java.util.Locale.ROOT, dlzka),
            "--espeak_data", File(program().parentFile, "espeak-ng-data").path)
            .directory(program().parentFile)
        if (!Platforma.jeWindows) pb.environment()["LD_LIBRARY_PATH"] = program().parentFile.path
        pb.redirectError(File(priecinok(), "piper-chyba.log"))
        val p = pb.start(); proces = p
        Thread { runCatching { p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(t); it.newLine() } } }.start()
        val l = otvorLinku(VZORKOVANIE * vyska)
        p.inputStream.use { i ->
            val b = ByteArray(4096); var n: Int
            while (i.read(b).also { n = it } > 0) l.write(b, 0, n - n % 2)
        }
        l.drain(); l.close()
    }

    @Volatile private var stChyba = false
    @Volatile private var st: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private val stZamok = Any()

    /** model sa načíta raz (~1–2 s) a ostane v pamäti */
    private fun stModel(): com.k2fsa.sherpa.onnx.OfflineTts = synchronized(stZamok) {
        st?.let { return it }
        pripravSherpa {}
        val d = stDir()
        val sc = com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig.builder()
            .setDurationPredictor(File(d, "duration_predictor.int8.onnx").path)
            .setTextEncoder(File(d, "text_encoder.int8.onnx").path)
            .setVectorEstimator(File(d, "vector_estimator.int8.onnx").path)
            .setVocoder(File(d, "vocoder.int8.onnx").path)
            .setTtsJson(File(d, "tts.json").path)
            .setUnicodeIndexer(File(d, "unicode_indexer.bin").path)
            .setVoiceStyle(File(d, "voice.bin").path)
            .build()
        val mc = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig.builder()
            .setSupertonic(sc)
            .setNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))
            .build()
        val t = com.k2fsa.sherpa.onnx.OfflineTts(com.k2fsa.sherpa.onnx.OfflineTtsConfig.builder().setModel(mc).build())
        st = t; t
    }

    private fun naPcm(f: FloatArray): ByteArray {
        val b = ByteArray(f.size * 2)
        for (i in f.indices) { val v = (f[i].coerceIn(-1f, 1f) * 32767).toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
        return b
    }

    /** Supertonic: kúsky sa tvoria v pozadí, prehrávajú sa za sebou (ďalší sa robí, kým hrá predchádzajúci) */
    private fun hovorSt(povodny: String, t: String, sid: Int, speed: Float, vyska: Float) {
        val rad = java.util.concurrent.LinkedBlockingQueue<Pair<ByteArray, Float>>(3)
        val KONIEC = ByteArray(0) to 0f
        val tvorca = Thread {
            try {
                val m = stModel()
                for (kus in kusky(t)) {
                    if (hovori != povodny) break
                    val g = com.k2fsa.sherpa.onnx.GenerationConfig()
                    g.sid = sid; g.speed = speed; g.numSteps = 8
                    g.extra = mapOf("lang" to (if (ChatJadro.nastavenia.jazykOdpovedi == "en") "en" else "sk"))
                    val a = m.generateWithConfig(kus, g)
                    rad.put(naPcm(a.samples) to a.sampleRate.toFloat())
                }
            } catch (e: Throwable) {
                stChyba = true
                runCatching { File(priecinok(), "sherpa-chyba.log").writeText(e.stackTraceToString()) }
                if (hovori == povodny) Platforma.oznam(T("hlas_chyba", e.message ?: e.toString()))
            } finally { rad.offer(KONIEC) }
        }
        tvorca.isDaemon = true; tvorca.start()
        var l: SourceDataLine? = null
        while (hovori == povodny) {
            val (pcm, sr) = rad.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (pcm.isEmpty()) break
            if (l == null) l = otvorLinku(sr * vyska)
            var o = 0
            while (o < pcm.size && hovori == povodny) { val n = minOf(8192, pcm.size - o); l.write(pcm, o, n - n % 2); o += n }
        }
        l?.let { runCatching { if (hovori == povodny) it.drain(); it.close() } }
    }

    fun zastav() {
        hovori = null
        proces?.destroy(); proces = null
        linka?.let { runCatching { it.stop(); it.flush(); it.close() } }; linka = null
    }

    /** po odpovedi v chate – prečíta ju, ak je to zapnuté */
    fun poOdpovedi(text: String) { if (ChatJadro.nastavenia.citatNahlas && mam() && text.isNotBlank() && !text.startsWith("⚠")) hovor(text) }
}

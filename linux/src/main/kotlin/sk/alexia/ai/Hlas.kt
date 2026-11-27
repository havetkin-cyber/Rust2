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
    val HLASY = listOf("lili" to "Lili", "st0" to "Ema", "st1" to "Nina", "st2" to "Sofia", "st3" to "Zara", "st4" to "Hana", "mix" to "Mix")
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
    private fun jeSt(id: String) = id.startsWith("st") || id == "mix"
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
    private fun opravKonfig(mdl: File = model()) = runCatching {
        val f = File(mdl.path + ".json")
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
        val u = Uprava(n.hlasVyska.coerceIn(0.5f, 2f), n.hlasHrubka, n.hlasPlnost)
        // dĺžku posunutú prehrávaním (farba) vyrovná rýchlosť pri tvorbe reči
        val speed = n.hlasRychlost.coerceIn(0.3f, 3f) / u.f
        val jazyk = if (n.jazykOdpovedi == "en") "en" else "sk"
        Thread {
            val ok = runCatching {
                if (jeSt(id)) {
                    val sid = if (id == "mix") -1 else id.drop(2).toIntOrNull() ?: 0
                    prehrajKusy(text, t, u) { kus ->
                        val g = com.k2fsa.sherpa.onnx.GenerationConfig()
                        val m = stModel()
                        g.sid = if (sid < 0) mixSid else sid; g.speed = speed; g.numSteps = 8; g.extra = mapOf("lang" to jazyk)
                        val a = m.generateWithConfig(kus, g)
                        a.samples to a.sampleRate
                    }
                } else { opravKonfig(); prehrajKusy(text, t, u) { kus -> lili(kus, 1f / speed) } }
            }.onFailure { e -> if (hovori != null) Platforma.oznam(T("hlas_chyba", e.message ?: "")) }.isSuccess
            val dohovoril = ok && hovori == text && !stChyba
            Platforma.naHlavnom { if (hovori == text) hovori = null; if (dohovoril) poKonci?.invoke() }
        }.start()
    }

    // ---------------- nahovoriť text do súboru (WAV / MP3) ----------------
    var nahrava by mutableStateOf<Float?>(null)
        private set

    /** text → zvolený hlas s úpravami → WAV alebo MP3 do priečinka Hudba/Alexia AI; [hotovo] dostane súbor alebo null */
    fun nahovorDoSuboru(text: String, mp3: Boolean, id: String = ChatJadro.nastavenia.hlas, hotovo: (File?, String) -> Unit) {
        if (nahrava != null) return
        if (!mam(id)) { hotovo(null, T("hlas_najprv")); return }
        val t = naCitanie(text); if (t.isBlank()) { hotovo(null, T("nahovor_prazdny")); return }
        val n = ChatJadro.nastavenia
        val u = Uprava(n.hlasVyska.coerceIn(0.5f, 2f), n.hlasHrubka, n.hlasPlnost)
        val speed = n.hlasRychlost.coerceIn(0.3f, 3f) / u.f
        val jazyk = if (n.jazykOdpovedi == "en") "en" else "sk"
        nahrava = 0f
        Thread {
            val r = runCatching {
                if (!jeSt(id)) opravKonfig()
                val kusy = kusky(t)
                val spolu = java.io.ByteArrayOutputStream(); var sr = 22050
                kusy.forEachIndexed { i, kus ->
                    val (x, s0) = if (jeSt(id)) {
                        val sid = if (id == "mix") -1 else id.drop(2).toIntOrNull() ?: 0
                        val m = stModel()
                        val g = com.k2fsa.sherpa.onnx.GenerationConfig()
                        g.sid = if (sid < 0) mixSid else sid; g.speed = speed; g.numSteps = 8; g.extra = mapOf("lang" to jazyk)
                        val a = m.generateWithConfig(kus, g); a.samples to a.sampleRate
                    } else lili(kus, 1f / speed)
                    sr = s0
                    spolu.write(naPcm(Zvuk.uprav(x, s0, u.vyska, u.hrubka, u.plnost)))
                    // krátka pauza medzi vetami
                    spolu.write(ByteArray((s0 * 0.15).toInt() * 2))
                    val pr = (i + 1f) / kusy.size; Platforma.naHlavnom { nahrava = pr * 0.95f }
                }
                val pcm = spolu.toByteArray()
                // farba (hrúbka) sa dosiahne inou vzorkovacou frekvenciou – zapíše sa priamo do súboru
                val frek = (sr * u.f).toInt()
                val dir = Platforma.hlasy
                val meno = "alexia_" + meno(id).lowercase() + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(java.util.Date())
                val wav = File(if (mp3) File(priecinok(), "tmp").apply { mkdirs() } else dir, "$meno.wav")
                zapisWav(wav, pcm, frek)
                if (!mp3) wav
                else {
                    val ff = VideoJadro.ffmpeg() ?: throw IllegalStateException(T("nahovor_ffmpeg"))
                    val out = File(dir, "$meno.mp3")
                    val p = ProcessBuilder(ff, "-y", "-v", "error", "-i", wav.path, "-ar", "44100", "-ac", "1", "-b:a", "192k", out.path).redirectErrorStream(true).start()
                    p.inputStream.readBytes()
                    if (p.waitFor() != 0) throw IllegalStateException("ffmpeg zlyhal")
                    wav.delete(); out
                }
            }
            Platforma.naHlavnom {
                nahrava = null
                hotovo(r.getOrNull(), r.exceptionOrNull()?.let { it.message ?: it.toString() } ?: "")
            }
        }.start()
    }

    private fun zapisWav(f: File, pcm: ByteArray, sr: Int) {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        f.outputStream().use { it.write(h.array()); it.write(pcm) }
    }

    /** úprava hlasu: výška tónu, hrúbka (farba) a plnosť; f = koeficient prehrávania */
    private class Uprava(val vyska: Float, val hrubka: Float, val plnost: Float) { val f = Zvuk.formant(hrubka) }

    private fun otvorLinku(sr: Float): SourceDataLine {
        val f = AudioFormat(sr, 16, 1, true, false)
        val l = AudioSystem.getSourceDataLine(f); linka = l
        l.open(f); l.start(); return l
    }

    /** Lili: jeden kúsok cez Piper (surový 16-bit zvuk) */
    private fun lili(kus: String, dlzka: Float, mdl: File = model()): Pair<FloatArray, Int> {
        val pb = ProcessBuilder(program().path, "--model", mdl.path, "--output_raw", "--length_scale", "%.3f".format(java.util.Locale.ROOT, dlzka),
            "--espeak_data", File(program().parentFile, "espeak-ng-data").path)
            .directory(program().parentFile)
        if (!Platforma.jeWindows) pb.environment()["LD_LIBRARY_PATH"] = program().parentFile.path
        pb.redirectError(File(Platforma.logy, "piper-chyba.log"))
        val p = pb.start(); proces = p
        Thread { runCatching { p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(kus); it.newLine() } } }.start()
        val b = p.inputStream.use { it.readBytes() }
        p.waitFor()
        val x = FloatArray(b.size / 2) { ((b[2 * it + 1].toInt() shl 8) or (b[2 * it].toInt() and 0xff)).toShort() / 32768f }
        val sr = runCatching { org.json.JSONObject(File(mdl.path + ".json").readText()).getJSONObject("audio").getInt("sample_rate") }.getOrDefault(VZORKOVANIE.toInt())
        return x to sr
    }

    /** kúsky sa tvoria v pozadí a prehrávajú za sebou (ďalší sa robí, kým hrá predchádzajúci) */
    private fun prehrajKusy(povodny: String, t: String, u: Uprava, tvor: (String) -> Pair<FloatArray, Int>) {
        val rad = java.util.concurrent.LinkedBlockingQueue<Pair<ByteArray, Float>>(3)
        val KONIEC = ByteArray(0) to 0f
        val tvorca = Thread {
            try {
                for (kus in kusky(t)) {
                    if (hovori != povodny) break
                    val (x, sr) = tvor(kus)
                    if (hovori != povodny) break
                    val y = Zvuk.uprav(x, sr, u.vyska, u.hrubka, u.plnost)
                    rad.put(naPcm(y) to sr.toFloat())
                }
            } catch (e: Throwable) {
                stChyba = true
                runCatching { File(Platforma.logy, "sherpa-chyba.log").writeText(e.stackTraceToString()) }
                if (hovori == povodny) Platforma.oznam(T("hlas_chyba", e.message ?: e.toString()))
            } finally { rad.offer(KONIEC) }
        }
        tvorca.isDaemon = true; tvorca.start()
        var l: SourceDataLine? = null
        while (hovori == povodny) {
            val (pcm, sr) = rad.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (pcm.isEmpty()) break
            if (l == null) l = otvorLinku(sr * u.f)
            var o = 0
            while (o < pcm.size && hovori == povodny) { val k = minOf(8192, pcm.size - o); l.write(pcm, o, k - k % 2); o += k }
        }
        l?.let { runCatching { if (hovori == povodny) it.drain(); it.close() } }
    }

    @Volatile private var stChyba = false
    @Volatile private var st: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private val stZamok = Any()

    /** model sa načíta raz (~1–2 s) a ostane v pamäti */
    @Volatile private var mixSid = 10
    private var stKluc = ""

    private fun stModel(): com.k2fsa.sherpa.onnx.OfflineTts = synchronized(stZamok) {
        // voice-mix.bin = 10 hlasov + mix podľa Nastavení; pri zmene mixu sa model načíta znova
        val n = ChatJadro.nastavenia
        val kluc = "${n.mixA},${n.mixB},${n.mixPomer}"
        st?.let { if (kluc == stKluc) return it; runCatching { it.release() }; st = null }
        pripravSherpa {}
        val d = stDir()
        val hlasy = File(d, "voice-mix.bin")
        mixSid = MixHlasov.vytvor(File(d, "voice.bin"), hlasy, n.mixA, n.mixB, n.mixPomer)
        stKluc = kluc
        val sc = com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig.builder()
            .setDurationPredictor(File(d, "duration_predictor.int8.onnx").path)
            .setTextEncoder(File(d, "text_encoder.int8.onnx").path)
            .setVectorEstimator(File(d, "vector_estimator.int8.onnx").path)
            .setVocoder(File(d, "vocoder.int8.onnx").path)
            .setTtsJson(File(d, "tts.json").path)
            .setUnicodeIndexer(File(d, "unicode_indexer.bin").path)
            .setVoiceStyle(hlasy.path)
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

    // ---------------- dabing: hlasy podľa jazyka ----------------
    /** Piper hlasy pre dabing: id → (meno, jazyk, odkaz) */
    val DABING_PIPER = listOf(
        Triple("jirka", "Jirka (CZ, muž)", "https://huggingface.co/rhasspy/piper-voices/resolve/main/cs/cs_CZ/jirka/medium/cs_CZ-jirka-medium.onnx"),
        Triple("lili", "Lili (SK, žena)", HLAS),
    )
    private fun piperModel(id: String) = if (id == "lili") model() else File(priecinok(), "cs_CZ-$id-medium.onnx")
    fun mamDabingHlas(id: String) = when {
        jeSt(id) -> mamSt()
        else -> program().exists() && piperModel(id).length() > 10_000_000 && File(piperModel(id).path + ".json").exists()
    }

    /** stiahne Piper aj zvolený hlas (blokuje – volať z vlákna) */
    fun stiahniDabingHlas(id: String, p: (Float) -> Unit) {
        if (jeSt(id)) { if (!mamSt()) throw IllegalStateException(T("dab_st_najprv")); return }
        val d = priecinok()
        if (!program().exists()) {
            val arch = File(d, if (Platforma.jeWindows) "piper.zip" else "piper.tar.gz")
            stiahniSubor(if (Platforma.jeWindows) PIPER_WINDOWS else PIPER_LINUX, arch) { p(it * 0.3f) }
            if (Platforma.jeWindows) rozbalZip(arch, d)
            else { val pr = ProcessBuilder("tar", "-xzf", arch.path, "-C", d.path).redirectErrorStream(true).start(); pr.waitFor() }
            arch.delete(); program().setExecutable(true)
            if (!program().exists()) throw IllegalStateException("Piper sa nepodarilo rozbaliť")
        }
        val url = DABING_PIPER.first { it.first == id }.third
        val m = piperModel(id)
        if (!File(m.path + ".json").exists()) stiahniSubor("$url.json", File(m.path + ".json")) {}
        if (m.length() < 10_000_000) stiahniSubor(url, m) { p(0.3f + it * 0.7f) }
        opravKonfig(m)
    }

    /** jedna replika dabingu → zvuk; [rychlost] > 1 = rýchlejšie */
    fun syntetizuj(text: String, id: String, rychlost: Float, jazyk: String): Pair<FloatArray, Int> =
        if (jeSt(id)) {
            val m = stModel()
            val g = com.k2fsa.sherpa.onnx.GenerationConfig()
            g.sid = id.drop(2).toIntOrNull() ?: 0; g.speed = rychlost; g.numSteps = 8; g.extra = mapOf("lang" to jazyk)
            val a = m.generateWithConfig(text, g); a.samples to a.sampleRate
        } else lili(text, 1f / rychlost, piperModel(id))

    fun zastav() {
        hovori = null
        proces?.destroy(); proces = null
        linka?.let { runCatching { it.stop(); it.flush(); it.close() } }; linka = null
    }

    /** po odpovedi v chate – prečíta ju, ak je to zapnuté */
    fun poOdpovedi(text: String) { if (ChatJadro.nastavenia.citatNahlas && mam() && text.isNotBlank() && !text.startsWith("⚠")) hovor(text) }
}

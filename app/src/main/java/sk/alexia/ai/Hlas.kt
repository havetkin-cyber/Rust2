package sk.alexia.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Hlas v mobile:
 *  - „system“ – systémový prevod textu na reč (Google…)
 *  - Ema, Nina, Sofia, Zara, Hana – Supertonic 3 (OpenRAIL-M) cez sherpa-onnx (Apache 2.0), offline, hovoriaci 0–4
 * Výška: zvuk sa prehrá s inou vzorkovacou frekvenciou a rýchlosť reči sa o to isté vyrovná pri tvorbe.
 */
object Hlas {
    val HLASY = listOf("system" to "", "lili" to "Lili", "st0" to "Ema", "st1" to "Nina", "st2" to "Sofia", "st3" to "Zara", "st4" to "Hana")
    fun meno(id: String) = HLASY.firstOrNull { it.first == id }?.second?.ifEmpty { "Alexia" } ?: "Alexia"
    const val ST_MB = 130
    const val LILI_MB = 25
    private const val LILI_MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-sk_SK-lili-medium-int8.tar.bz2"
    private const val ST_MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"

    private var tts: TextToSpeech? = null
    private var pripraveny = false
    var hovori by mutableStateOf<String?>(null)
        private set
    var priebeh by mutableStateOf<Float?>(null)
        private set
    var chyba by mutableStateOf("")
        private set
    private var cakajuci: String? = null
    private var cakajuciKoniec: (() -> Unit)? = null
    private var poKonciSys: (() -> Unit)? = null
    @Volatile private var stopa: AudioTrack? = null

    private fun jazyk(): Locale = if (ChatJadro.nastavenia.jazykOdpovedi == "en") Locale.ENGLISH else Locale("sk", "SK")
    /** hlasy cez sherpa-onnx (Supertonic aj Lili) */
    private fun jeSt(id: String) = id.startsWith("st") || id == "lili"
    private fun liliDir() = File(Platforma.priecinok, "hlas/lili")
    private fun mamLili() = File(liliDir(), "sk_SK-lili-medium.onnx").length() > 5_000_000 && File(liliDir(), "espeak-ng-data/phontab").exists()
    private fun stDir() = File(Platforma.priecinok, "hlas/supertonic")
    private fun mamSt() = File(stDir(), "vector_estimator.int8.onnx").length() > 50_000_000 && File(stDir(), "voice.bin").exists()
    fun mam(id: String = ChatJadro.nastavenia.hlas) = when { id == "lili" -> mamLili(); id.startsWith("st") -> mamSt(); else -> true }

    // ---------- sťahovanie ----------
    fun stiahni(id: String = ChatJadro.nastavenia.hlas) {
        if (priebeh != null) return
        priebeh = 0f; chyba = ""
        if (id == "lili") {
            Thread {
                val r = runCatching {
                    val arch = File(Platforma.priecinok, "hlas/lili.tar.bz2").apply { parentFile?.mkdirs() }
                    stiahniSubor(LILI_MODEL, arch) { Platforma.naHlavnom { priebeh = it * 0.9f } }
                    rozbalTarBz2(arch, liliDir()) { true }
                    arch.delete()
                    if (!mamLili()) throw IllegalStateException("Hlas sa nepodarilo rozbaliť")
                }
                Platforma.naHlavnom { priebeh = null; r.exceptionOrNull()?.let { chyba = it.message ?: "Hlas sa nepodarilo stiahnuť" } }
            }.start()
            return
        }
        Thread {
            val r = runCatching {
                val arch = File(Platforma.priecinok, "hlas/supertonic.tar.bz2").apply { parentFile?.mkdirs() }
                stiahniSubor(ST_MODEL, arch) { Platforma.naHlavnom { priebeh = it * 0.95f } }
                Platforma.naHlavnom { priebeh = 0.97f }
                rozbalTarBz2(arch, stDir()) { true }
                arch.delete()
                if (!mamSt()) throw IllegalStateException("Hlasy sa nepodarilo rozbaliť")
            }
            Platforma.naHlavnom { priebeh = null; r.exceptionOrNull()?.let { chyba = it.message ?: "Hlas sa nepodarilo stiahnuť" } }
        }.start()
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
                f.parentFile?.mkdirs(); f.outputStream().use { t.copyTo(it) }
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

    // ---------- systémový hlas ----------
    private fun priprav() {
        if (tts != null) return
        tts = TextToSpeech(Platforma.app) { st ->
            pripraveny = st == TextToSpeech.SUCCESS
            if (pripraveny) {
                val t = tts!!
                t.language = jazyk()
                // ženský hlas, ak ho systém označí (Google má v názve často „female“ alebo „#female“)
                runCatching { t.voices?.filter { it.locale.language == jazyk().language && !it.isNetworkConnectionRequired }
                    ?.firstOrNull { it.name.contains("female", true) || it.features.any { f -> f.contains("female", true) } }?.let { t.voice = it } }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "koniec") Platforma.naHlavnom { hovori = null; val k = poKonciSys; poKonciSys = null; k?.invoke() } }
                    @Deprecated("") override fun onError(id: String?) { Platforma.naHlavnom { hovori = null } }
                })
                cakajuci?.let { c -> cakajuci = null; hovor(c, cakajuciKoniec); cakajuciKoniec = null }
            } else Platforma.oznam(T("hlas_chyba", "TTS"))
        }
    }

    fun naCitanie(t: String): String {
        var s = t.replace(Regex("<think>[\\s\\S]*?(</think>|$)"), " ")
        s = s.replace(Regex("```[\\s\\S]*?(```|$)"), ". ")
        s = s.replace(Regex("https?://\\S+"), " ")
        return s.replace(Regex("[*#_`>|~]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun hovor(text: String, poKonci: (() -> Unit)? = null) {
        val n = ChatJadro.nastavenia
        if (jeSt(n.hlas)) { hovorSt(text, n.hlas, poKonci); return }
        if (tts == null || !pripraveny) { cakajuci = text; cakajuciKoniec = poKonci; priprav(); return }
        zastavSt()
        poKonciSys = poKonci
        val t = tts!!; t.stop(); t.language = jazyk()
        t.setSpeechRate(n.hlasRychlost); t.setPitch(n.hlasVyska)
        hovori = text
        // TTS má limit dĺžky – čítame po vetách
        val vety = naCitanie(text).split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        vety.forEachIndexed { i, v ->
            t.speak(v.take(3900), if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == vety.lastIndex) "koniec" else "v$i")
        }
        if (vety.isEmpty()) { hovori = null; poKonciSys = null; poKonci?.invoke() }
    }

    // ---------- Supertonic ----------
    @Volatile private var st: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private val zamok = Any()

    private fun stModel(): com.k2fsa.sherpa.onnx.OfflineTts = synchronized(zamok) {
        st?.let { return it }
        val d = stDir()
        val cfg = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
            model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                supertonic = com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(d, "duration_predictor.int8.onnx").path,
                    textEncoder = File(d, "text_encoder.int8.onnx").path,
                    vectorEstimator = File(d, "vector_estimator.int8.onnx").path,
                    vocoder = File(d, "vocoder.int8.onnx").path,
                    ttsJson = File(d, "tts.json").path,
                    unicodeIndexer = File(d, "unicode_indexer.bin").path,
                    voiceStyle = File(d, "voice.bin").path,
                ),
                numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
            )
        )
        val t = com.k2fsa.sherpa.onnx.OfflineTts(assetManager = null, config = cfg)
        st = t; t
    }

    @Volatile private var lili: com.k2fsa.sherpa.onnx.OfflineTts? = null

    private fun liliModel(): com.k2fsa.sherpa.onnx.OfflineTts = synchronized(zamok) {
        lili?.let { return it }
        val d = liliDir()
        val cfg = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
            model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
                    model = File(d, "sk_SK-lili-medium.onnx").path,
                    tokens = File(d, "tokens.txt").path,
                    dataDir = File(d, "espeak-ng-data").path,
                ),
                numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
            )
        )
        val t = com.k2fsa.sherpa.onnx.OfflineTts(assetManager = null, config = cfg)
        lili = t; t
    }

    private fun kusky(t: String): List<String> {
        val vety = t.split(Regex("(?<=[.!?…:;])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>(); val b = StringBuilder()
        for (v in vety) { if (b.isNotEmpty() && b.length + v.length > 250) { out += b.toString(); b.clear() }; if (b.isNotEmpty()) b.append(' '); b.append(v) }
        if (b.isNotEmpty()) out += b.toString()
        return out
    }

    @Volatile private var stChyba = false

    private fun hovorSt(text: String, id: String, poKonci: (() -> Unit)?) {
        zastav()
        if (!mam(id)) { Platforma.oznam(T("hlas_najprv")); return }
        val jeLili = id == "lili"
        val sid = if (jeLili) 0 else id.drop(2).toIntOrNull() ?: 0
        val t = naCitanie(text); if (t.isBlank()) { poKonci?.invoke(); return }
        stChyba = false
        hovori = text
        val n = ChatJadro.nastavenia
        val vyska = n.hlasVyska.coerceIn(0.5f, 2f); val speed = n.hlasRychlost.coerceIn(0.3f, 3f) / vyska
        val lang = if (n.jazykOdpovedi == "en") "en" else "sk"
        val rad = java.util.concurrent.LinkedBlockingQueue<Pair<ShortArray, Int>>(3)
        val koniec = ShortArray(0) to 0
        Thread {
            try {
                val m = if (jeLili) liliModel() else stModel()
                for (kus in kusky(t)) {
                    if (hovori != text) break
                    val a = m.generateWithConfig(kus, com.k2fsa.sherpa.onnx.GenerationConfig(sid = sid, speed = speed, numSteps = 8, extra = mapOf("lang" to lang)))
                    val s = ShortArray(a.samples.size) { (a.samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                    rad.put(s to a.sampleRate)
                }
            } catch (e: Throwable) {
                stChyba = true
                if (hovori == text) Platforma.oznam(T("hlas_chyba", e.message ?: e.toString()))
            } finally { rad.offer(koniec) }
        }.start()
        Thread {
            var a: AudioTrack? = null
            try {
                while (hovori == text) {
                    val (pcm, sr) = rad.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    if (pcm.isEmpty()) break
                    if (a == null) {
                        val f = (sr * vyska).toInt()
                        val min = AudioTrack.getMinBufferSize(f, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        a = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(f).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(maxOf(min, f / 2))
                            .setTransferMode(AudioTrack.MODE_STREAM).build()
                        stopa = a; a.play()
                    }
                    var o = 0
                    while (o < pcm.size && hovori == text) { val k = minOf(4096, pcm.size - o); a.write(pcm, o, k); o += k }
                }
                // dohrať zvyšok vyrovnávacej pamäte
                if (hovori == text) Thread.sleep(400)
            } catch (_: Throwable) {
            } finally {
                runCatching { a?.stop(); a?.release() }
                if (stopa === a) stopa = null
                val dohovoril = hovori == text && !stChyba
                Platforma.naHlavnom { if (hovori == text) hovori = null; if (dohovoril) poKonci?.invoke() }
            }
        }.start()
    }

    private fun zastavSt() { stopa?.let { runCatching { it.pause(); it.flush() } } }

    fun zastav() { tts?.stop(); zastavSt(); hovori = null; cakajuci = null; cakajuciKoniec = null; poKonciSys = null }

    fun poOdpovedi(text: String) { if (ChatJadro.nastavenia.citatNahlas && text.isNotBlank() && !text.startsWith("⚠") && mam()) hovor(text) }
}

package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/**
 * Rozprávanie: mikrofón → text (Whisper small, offline, cez sherpa-onnx).
 * Nahráva sa, kým nestlačíš tlačidlo znova (najviac 30 s – Whisper spracuje 30 s naraz).
 */
object Rec {
    private const val MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2"
    const val MB = 380
    private const val SR = 16000

    var priebeh by mutableStateOf<Float?>(null)
        private set
    var chyba by mutableStateOf("")
        private set
    var nahrava by mutableStateOf(false)
        private set
    var spracuva by mutableStateOf(false)
        private set
    var hlasitost by mutableStateOf(0f)
        private set

    private fun dir() = File(Platforma.priecinok, "hlas/whisper")
    fun mam() = File(dir(), "small-encoder.int8.onnx").length() > 50_000_000 && File(dir(), "small-decoder.int8.onnx").length() > 50_000_000 &&
        File(dir(), "small-tokens.txt").exists()

    fun stiahni() {
        if (priebeh != null) return
        priebeh = 0f; chyba = ""
        Thread {
            val r = runCatching {
                Hlas.pripravSherpa { Platforma.naHlavnom { priebeh = it * 0.05f } }
                val arch = File(Platforma.priecinok, "hlas/whisper.tar.bz2").apply { parentFile?.mkdirs() }
                Hlas.stiahniSubor(MODEL, arch) { Platforma.naHlavnom { priebeh = 0.05f + it * 0.9f } }
                Platforma.naHlavnom { priebeh = 0.97f }
                // len int8 verzia (menšia, rýchlejšia) a tokeny
                Hlas.rozbalTarBz2(arch, dir()) { it.endsWith(".int8.onnx") || it.endsWith("tokens.txt") }
                arch.delete()
                if (!mam()) throw IllegalStateException("Model sa nepodarilo rozbaliť")
            }
            Platforma.naHlavnom { priebeh = null; r.exceptionOrNull()?.let { chyba = it.message ?: "Nepodarilo sa stiahnuť" } }
        }.start()
    }

    @Volatile private var rozpoznavac: com.k2fsa.sherpa.onnx.OfflineRecognizer? = null
    private var jazykModelu = ""

    private fun model(jazyk: String): com.k2fsa.sherpa.onnx.OfflineRecognizer = synchronized(this) {
        rozpoznavac?.takeIf { jazykModelu == jazyk }?.let { return it }
        rozpoznavac?.release(); rozpoznavac = null
        Hlas.pripravSherpa {}
        val d = dir()
        val w = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig.builder()
            .setEncoder(File(d, "small-encoder.int8.onnx").path)
            .setDecoder(File(d, "small-decoder.int8.onnx").path)
            .setLanguage(jazyk)
            .setTask("transcribe")
            .build()
        val mc = com.k2fsa.sherpa.onnx.OfflineModelConfig.builder()
            .setWhisper(w)
            .setTokens(File(d, "small-tokens.txt").path)
            .setNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 6))
            .build()
        val r = com.k2fsa.sherpa.onnx.OfflineRecognizer(com.k2fsa.sherpa.onnx.OfflineRecognizerConfig.builder().setOfflineModelConfig(mc).build())
        rozpoznavac = r; jazykModelu = jazyk; r
    }

    @Volatile private var linka: TargetDataLine? = null

    /** prvé stlačenie začne nahrávať, druhé zastaví a text pošle do [hotovo] */
    fun prepni(hotovo: (String) -> Unit) {
        if (spracuva) return
        if (nahrava) { linka?.stop(); return }
        if (!mam()) { Platforma.oznam(T("rec_najprv")); return }
        Hlas.zastav()
        val f = AudioFormat(SR.toFloat(), 16, 1, true, false)
        val l = runCatching { AudioSystem.getTargetDataLine(f).also { it.open(f); it.start() } }.getOrElse {
            Platforma.oznam(T("rec_mikrofon", it.message ?: "")); return
        }
        linka = l; nahrava = true; chyba = ""
        Thread {
            val buf = ByteArrayOutputStream()
            val b = ByteArray(3200)
            val max = SR * 2 * 30
            while (l.isActive || l.available() > 0) {
                val n = l.read(b, 0, b.size)
                if (n <= 0) break
                buf.write(b, 0, n)
                var s = 0.0; var i = 0
                while (i + 1 < n) { val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort() / 32768.0; s += v * v; i += 2 }
                val h = Math.sqrt(s / (n / 2).coerceAtLeast(1)).toFloat()
                Platforma.naHlavnom { hlasitost = (h * 6).coerceIn(0f, 1f) }
                if (buf.size() >= max) break
            }
            runCatching { l.stop(); l.close() }
            linka = null
            Platforma.naHlavnom { nahrava = false; spracuva = true; hlasitost = 0f }
            val pcm = buf.toByteArray()
            val text = runCatching {
                if (pcm.size < SR / 2) return@runCatching ""   // menej ako 0,25 s
                val x = FloatArray(pcm.size / 2) { ((pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xff)).toShort() / 32768f }
                val jazyk = if (ChatJadro.nastavenia.jazykOdpovedi == "en") "en" else "sk"
                val r = model(jazyk)
                val st = r.createStream()
                st.acceptWaveform(x, SR)
                r.decode(st)
                val t = r.getResult(st).text.trim()
                st.release()
                t
            }.onFailure { e ->
                runCatching { File(Platforma.logy, "whisper-chyba.log").writeText(e.stackTraceToString()) }
                Platforma.oznam(T("rec_chyba", e.message ?: e.toString()))
            }.getOrDefault("")
            Platforma.naHlavnom { spracuva = false; if (text.isNotBlank()) hotovo(text) }
        }.start()
    }
}

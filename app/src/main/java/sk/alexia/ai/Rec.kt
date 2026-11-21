package sk.alexia.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Rozprávanie: mikrofón → text (Whisper small, offline, cez sherpa-onnx).
 * Nahráva sa, kým nestlačíš tlačidlo znova (najviac 30 s).
 */
object Rec {
    private const val MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2"
    const val MB = 380
    private const val SR = 16000

    var priebeh by mutableStateOf<Float?>(null)
        private set
    var nahrava by mutableStateOf(false)
        private set
    var spracuva by mutableStateOf(false)
        private set
    var hlasitost by mutableStateOf(0f)
        private set
    @Volatile private var zastavit = false

    private fun dir() = File(Platforma.priecinok, "hlas/whisper")
    fun mam() = File(dir(), "small-encoder.int8.onnx").length() > 50_000_000 && File(dir(), "small-decoder.int8.onnx").length() > 50_000_000 &&
        File(dir(), "small-tokens.txt").exists()

    fun stiahni() {
        if (priebeh != null) return
        priebeh = 0f
        Thread {
            val r = runCatching {
                val arch = File(Platforma.priecinok, "hlas/whisper.tar.bz2").apply { parentFile?.mkdirs() }
                Hlas.stiahniSubor(MODEL, arch) { Platforma.naHlavnom { priebeh = it * 0.95f } }
                Platforma.naHlavnom { priebeh = 0.97f }
                Hlas.rozbalTarBz2(arch, dir()) { it.endsWith(".int8.onnx") || it.endsWith("tokens.txt") }
                arch.delete()
                if (!mam()) throw IllegalStateException("Model sa nepodarilo rozbaliť")
            }
            Platforma.naHlavnom { priebeh = null; r.exceptionOrNull()?.let { Platforma.oznam("⚠ " + (it.message ?: "")) } }
        }.start()
    }

    @Volatile private var rozpoznavac: com.k2fsa.sherpa.onnx.OfflineRecognizer? = null
    private var jazykModelu = ""

    private fun model(jazyk: String): com.k2fsa.sherpa.onnx.OfflineRecognizer = synchronized(this) {
        rozpoznavac?.takeIf { jazykModelu == jazyk }?.let { return it }
        rozpoznavac?.release(); rozpoznavac = null
        val d = dir()
        val cfg = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                    encoder = File(d, "small-encoder.int8.onnx").path,
                    decoder = File(d, "small-decoder.int8.onnx").path,
                    language = jazyk, task = "transcribe",
                ),
                tokens = File(d, "small-tokens.txt").path,
                numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
            )
        )
        val r = com.k2fsa.sherpa.onnx.OfflineRecognizer(assetManager = null, config = cfg)
        rozpoznavac = r; jazykModelu = jazyk; r
    }

    /** prvé stlačenie začne nahrávať, druhé zastaví a text pošle do [hotovo]; povolenie mikrofónu rieši obrazovka */
    @SuppressLint("MissingPermission")
    fun prepni(hotovo: (String) -> Unit) {
        if (spracuva) return
        if (nahrava) { zastavit = true; return }
        if (!mam()) { Platforma.oznam(T("rec_najprv")); return }
        Hlas.zastav()
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val a = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR)) }
            .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: run { Platforma.oznam(T("rec_mikrofon", "")); return }
        zastavit = false; nahrava = true
        Thread {
            val vzorky = ArrayList<ShortArray>()
            var spolu = 0
            runCatching {
                a.startRecording()
                val b = ShortArray(1600)
                while (!zastavit && spolu < SR * 30) {
                    val n = a.read(b, 0, b.size)
                    if (n <= 0) break
                    vzorky += b.copyOf(n); spolu += n
                    var s = 0.0; for (i in 0 until n) { val v = b[i] / 32768.0; s += v * v }
                    val h = Math.sqrt(s / n).toFloat()
                    Platforma.naHlavnom { hlasitost = (h * 6).coerceIn(0f, 1f) }
                }
            }
            runCatching { a.stop() }; a.release()
            Platforma.naHlavnom { nahrava = false; spracuva = true; hlasitost = 0f }
            val text = runCatching {
                if (spolu < SR / 4) return@runCatching ""
                val x = FloatArray(spolu); var o = 0
                for (k in vzorky) for (v in k) x[o++] = v / 32768f
                val r = model(if (ChatJadro.nastavenia.jazykOdpovedi == "en") "en" else "sk")
                val st = r.createStream()
                st.acceptWaveform(x, SR)
                r.decode(st)
                val t = r.getResult(st).text.trim()
                st.release(); t
            }.onFailure { Platforma.oznam(T("rec_chyba", it.message ?: it.toString())) }.getOrDefault("")
            Platforma.naHlavnom { spracuva = false; if (text.isNotBlank()) hotovo(text) }
        }.start()
    }
}

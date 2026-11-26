package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Videoklip na skladbu z obrázkov vytvorených v appke.
 * Nájde tempo a údery skladby, obrázky strieda presne do rytmu (v refréne rýchlejšie),
 * každý záber sa pomaly približuje / vzďaľuje / posúva a pod to sa dá pôvodná skladba.
 * Nepotrebuje grafiku – robí to ffmpeg na procesore.
 */
object Videoklip {
    val PRIPONY_HUDBY = setOf("mp3", "wav", "flac", "m4a", "ogg", "opus", "aac", "wma")

    var pracuje by mutableStateOf(false); private set
    var priebeh by mutableFloatStateOf(0f); private set
    var cinnost by mutableStateOf(""); private set
    var zaciatok by mutableStateOf(0L); private set
    var posledneTrvanie by mutableStateOf(0L); private set
    @Volatile private var zrusit = false
    @Volatile private var proces: Process? = null

    fun zrus() { zrusit = true; proces?.destroy() }

    /** tempo: 1 = pomaly (strih každé 2 takty), 2 = stredne (každý takt), 3 = rýchlo (každé 2 údery) */
    fun vytvor(skladba: File, fotky: List<File>, tempo: Int = 2, hotovo: (File?, String) -> Unit) {
        if (pracuje) { hotovo(null, T("video_zaneprazdnene")); return }
        if (fotky.isEmpty()) { hotovo(null, T("klip_bez_fotiek")); return }
        if (!skladba.exists()) { hotovo(null, T("klip_bez_skladby")); return }
        val ff = VideoJadro.ffmpeg() ?: run { VideoJadro.stiahniFfmpeg { vytvor(skladba, fotky, tempo, hotovo) }; return }
        pracuje = true; priebeh = 0f; zrusit = false; cinnost = T("klip_rozbor")
        val start = System.currentTimeMillis(); zaciatok = start
        Thread {
            val tmp = File(System.getProperty("java.io.tmpdir"), "alexia_klip_$start").apply { mkdirs() }
            var vysledok: File? = null; var chyba = ""
            runCatching {
                // 1) skladba → mono PCM 11025 Hz
                val zvuk = File(tmp, "zvuk.raw")
                spusti(listOf(ff, "-y", "-v", "error", "-i", skladba.path, "-ac", "1", "-ar", "$VZORKY", "-f", "s16le", zvuk.path))
                val rytmus = rozborRytmu(zvuk.readBytes())
                zvuk.delete()
                if (rytmus.dlzka < 3.0) throw IllegalStateException(T("klip_kratka"))

                // 2) strihy na úderoch: pomaly 8 úderov, stredne 4, rýchlo 2; v hlasných častiach (refrén) polovica
                val zakladne = when (tempo) { 1 -> 8; 3 -> 2; else -> 4 }
                val strihy = mutableListOf(0.0)
                var i = 0
                val udery = rytmus.udery
                while (i < udery.size) {
                    val krok = if (rytmus.hlasne(udery[i]) && zakladne > 2) zakladne / 2 else zakladne
                    i += krok
                    if (i < udery.size && udery[i] - strihy.last() >= 0.6) strihy += udery[i]
                }
                if (rytmus.dlzka - strihy.last() < 0.8 && strihy.size > 1) strihy.removeAt(strihy.size - 1)
                strihy += rytmus.dlzka

                // 3) rozmer podľa prvého obrázka
                val (w, h) = rozmer(fotky.first())
                val zaberov = strihy.size - 1
                val zoznam = StringBuilder()
                for (z in 0 until zaberov) {
                    if (zrusit) throw IllegalStateException(T("klip_zrusene"))
                    Platforma.naHlavnom { cinnost = T("klip_zaber", z + 1, zaberov); priebeh = z.toFloat() / (zaberov + 1) }
                    val d = strihy[z + 1] - strihy[z]
                    val snimok = maxOf(1, Math.round(d * FPS).toInt())
                    val f = fotky[z % fotky.size]
                    val seg = File(tmp, "z%04d.mp4".format(z))
                    spusti(listOf(ff, "-y", "-v", "error", "-i", f.path, "-vf", pohyb(z, w, h, snimok), "-frames:v", "$snimok",
                        "-r", "$FPS", "-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p", seg.path))
                    zoznam.append("file '").append(seg.absolutePath.replace("\\", "/").replace("'", "'\\''")).append("'\n")
                }
                val lst = File(tmp, "zoznam.txt").apply { writeText(zoznam.toString()) }

                // 4) spojenie záberov + pôvodná skladba
                Platforma.naHlavnom { cinnost = T("klip_spajam"); priebeh = zaberov.toFloat() / (zaberov + 1) }
                val out = File(VideoJadro.priecinok().apply { mkdirs() }, "videoklip_${System.currentTimeMillis()}.mp4")
                spusti(listOf(ff, "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", lst.path, "-i", skladba.path,
                    "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", out.path))
                val trvanie = System.currentTimeMillis() - start
                File(out.path + ".json").writeText(JSONObject().put("typ", "videoklip").put("skladba", skladba.name)
                    .put("bpm", Math.round(rytmus.bpm)).put("zaberov", zaberov).put("fotiek", fotky.size)
                    .put("dlzka", rytmus.dlzka).put("trvanie", trvanie).toString())
                vysledok = out
            }.onFailure { chyba = it.message ?: T("klip_chyba") }
            tmp.deleteRecursively()
            val t = System.currentTimeMillis() - start
            Platforma.naHlavnom {
                pracuje = false; priebeh = 0f; cinnost = ""; proces = null; posledneTrvanie = t
                hotovo(vysledok, chyba)
            }
        }.apply { isDaemon = true }.start()
    }

    // ---------- pohyb kamery v zábere ----------

    /** striedavo: priblíženie, vzdialenie, posun doprava, posun doľava (obrázok vyplní celý záber) */
    private fun pohyb(z: Int, w: Int, h: Int, n: Int): String {
        val vyplnit = "scale=${w * 2}:${h * 2}:force_original_aspect_ratio=increase,crop=${w * 2}:${h * 2},setsar=1"
        val nn = maxOf(n - 1, 1)
        val zp = when (z % 4) {
            0 -> "zoompan=z='1+0.18*on/$nn':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
            1 -> "zoompan=z='1.18-0.18*on/$nn':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
            2 -> "zoompan=z='1.15':x='(iw-iw/zoom)*on/$nn':y='ih/2-(ih/zoom/2)'"
            else -> "zoompan=z='1.15':x='(iw-iw/zoom)*(1-on/$nn)':y='ih/2-(ih/zoom/2)'"
        }
        return "$vyplnit,$zp:d=$n:s=${w}x$h:fps=$FPS,format=yuv420p"
    }

    private fun rozmer(f: File): Pair<Int, Int> {
        val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return 1080 to 1920
        return when {
            img.height > img.width * 1.1 -> 1080 to 1920
            img.width > img.height * 1.1 -> 1920 to 1080
            else -> 1080 to 1080
        }
    }

    private fun spusti(prikaz: List<String>) {
        val p = ProcessBuilder(prikaz).redirectErrorStream(true).start()
        proces = p
        val vystup = p.inputStream.bufferedReader().readText()
        val kod = p.waitFor()
        if (zrusit) throw IllegalStateException(T("klip_zrusene"))
        if (kod != 0) throw IllegalStateException("ffmpeg: " + vystup.lines().lastOrNull { it.isNotBlank() }.orEmpty())
    }

    // ---------- rozbor rytmu ----------

    private const val VZORKY = 11025
    private const val FPS = 30
    private const val OKNO = 256                        // ≈ 23 ms
    private val KROK_S = OKNO.toDouble() / VZORKY

    class Rytmus(val bpm: Double, val udery: List<Double>, val dlzka: Double, private val hlasitost: DoubleArray, private val prah: Double) {
        /** je okolie tohto času hlasnejšie než bežná časť skladby (refrén, drop)? */
        fun hlasne(t: Double): Boolean {
            val i = (t / 2.0).toInt().coerceIn(0, hlasitost.size - 1)
            return hlasitost[i] > prah
        }
    }

    /** tempo a údery z energie zvuku: nábehy hlasitosti → autokorelácia (70–180 BPM) → fáza úderov */
    fun rozborRytmu(pcm: ByteArray): Rytmus {
        val n = pcm.size / 2
        val dlzka = n.toDouble() / VZORKY
        val okien = n / OKNO
        if (okien < 16) return Rytmus(120.0, emptyList(), dlzka, DoubleArray(1), 1.0)
        val energia = DoubleArray(okien)
        for (o in 0 until okien) {
            var s = 0.0
            for (k in 0 until OKNO) {
                val idx = (o * OKNO + k) * 2
                val v = ((pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xff)).toShort() / 32768.0
                s += v * v
            }
            energia[o] = sqrt(s / OKNO)
        }
        // nábehy (rast hlasitosti), odčítaný kĺzavý priemer
        val nabeh = DoubleArray(okien)
        for (o in 1 until okien) nabeh[o] = maxOf(0.0, energia[o] - energia[o - 1])
        val pol = 8
        val cisty = DoubleArray(okien) { o ->
            var s = 0.0; var c = 0
            for (k in maxOf(0, o - pol)..minOf(okien - 1, o + pol)) { s += nabeh[k]; c++ }
            maxOf(0.0, nabeh[o] - s / c)
        }
        // autokorelácia v rozsahu 70–180 BPM, mierne zvýhodnené tempo okolo 120
        val minLag = (60.0 / 180 / KROK_S).toInt(); val maxLag = (60.0 / 70 / KROK_S).toInt()
        var najLag = (60.0 / 120 / KROK_S).toInt(); var najHod = -1.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (o in 0 until okien - lag) s += cisty[o] * cisty[o + lag]
            val bpm = 60.0 / (lag * KROK_S)
            s *= 1.0 - 0.15 * abs(bpm - 120) / 60
            if (s > najHod) { najHod = s; najLag = lag }
        }
        // fáza: posun, pri ktorom údery najviac padajú na nábehy
        var najFaza = 0; var najF = -1.0
        for (f in 0 until najLag) {
            var s = 0.0; var o = f
            while (o < okien) { s += cisty[o]; o += najLag }
            if (s > najF) { najF = s; najFaza = f }
        }
        // údery; každý sa doladí na najbližší silný nábeh (±2 okná)
        val udery = mutableListOf<Double>()
        var o = najFaza
        while (o < okien) {
            var naj = o
            for (k in maxOf(0, o - 2)..minOf(okien - 1, o + 2)) if (cisty[k] > cisty[naj]) naj = k
            udery += naj * KROK_S
            o += najLag
        }
        // hlasitosť po 2 s úsekoch, prah = 1,2 × medián → refrén / drop
        val usekov = maxOf(1, (dlzka / 2.0).toInt() + 1)
        val hl = DoubleArray(usekov)
        val pocet = IntArray(usekov)
        for (i in 0 until okien) { val u = ((i * KROK_S) / 2.0).toInt().coerceIn(0, usekov - 1); hl[u] += energia[i]; pocet[u]++ }
        for (u in 0 until usekov) if (pocet[u] > 0) hl[u] /= pocet[u]
        val median = hl.sorted()[usekov / 2]
        return Rytmus(60.0 / (najLag * KROK_S), udery, dlzka, hl, median * 1.2)
    }
}

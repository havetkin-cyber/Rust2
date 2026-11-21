package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Krátke video na výšku (ako na TikToku) z obrázkov, ktoré nakreslí appka:
 * pohyb kamery, prechody (atrament, záblesk, rozmazanie, glitch), iskry, žiara a hudba.
 * Žiadne video AI – len efekty, preto to beží aj na Legion Go S. MP4 vyrába ffmpeg (stiahne sa raz).
 */
object VideoJadro {
    const val SIRKA = 1080
    const val VYSKA = 1920
    const val FPS = 30
    const val NA_OBRAZOK = 2.4          // sekundy na jeden obrázok
    const val PRECHOD = 0.45            // dĺžka prechodu
    const val POCET = 7                 // obrázkov vo videu

    private const val FFMPEG_LINUX = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz"
    private const val FFMPEG_WINDOWS = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
    const val FFMPEG_MB = 130

    var priebehFfmpeg by mutableStateOf<Float?>(null)
        private set
    var pracuje by mutableStateOf(false)
        private set
    var cinnost by mutableStateOf("")
        private set
    var priebeh by mutableStateOf(0f)
        private set

    fun priecinok() = File(Platforma.priecinok, "videa").apply { mkdirs() }

    // ------------------------------------------------ ffmpeg

    private fun mojFfmpeg() = File(Platforma.priecinok, if (Platforma.jeWindows) "ffmpeg/bin/ffmpeg.exe" else "ffmpeg/bin/ffmpeg")

    /** systémový ffmpeg, ak je, inak stiahnutý do priečinka appky */
    fun ffmpeg(): String? {
        if (mojFfmpeg().exists()) return mojFfmpeg().path
        val sys = if (Platforma.jeWindows) "ffmpeg.exe" else "ffmpeg"
        System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, sys) }?.firstOrNull { it.canExecute() }?.let { return it.path }
        return null
    }

    fun stiahniFfmpeg(hotovo: () -> Unit = {}) {
        if (priebehFfmpeg != null) return
        priebehFfmpeg = 0f
        Thread {
            val r = runCatching {
                val d = File(Platforma.priecinok, "ffmpeg-tmp").apply { deleteRecursively(); mkdirs() }
                val arch = File(d, if (Platforma.jeWindows) "ffmpeg.zip" else "ffmpeg.tar.xz")
                stiahni(if (Platforma.jeWindows) FFMPEG_WINDOWS else FFMPEG_LINUX, arch) { p -> Platforma.naHlavnom { priebehFfmpeg = p } }
                if (Platforma.jeWindows) rozbalZip(arch, d)
                else { val p = ProcessBuilder("tar", "-xJf", arch.path, "-C", d.path).redirectErrorStream(true).start(); p.inputStream.readBytes(); p.waitFor() }
                arch.delete()
                val bin = d.walkTopDown().firstOrNull { it.name == (if (Platforma.jeWindows) "ffmpeg.exe" else "ffmpeg") && it.parentFile.name == "bin" }
                    ?: throw IllegalStateException("ffmpeg sa nepodarilo rozbaliť")
                val ciel = File(Platforma.priecinok, "ffmpeg"); ciel.deleteRecursively()
                bin.parentFile.parentFile.renameTo(ciel) || throw IllegalStateException("ffmpeg sa nepodarilo presunúť")
                d.deleteRecursively()
                mojFfmpeg().setExecutable(true)
            }
            Platforma.naHlavnom {
                priebehFfmpeg = null
                r.onFailure { Platforma.oznam(T("video_ffmpeg_chyba", it.message ?: "")) }
                r.onSuccess { hotovo() }
            }
        }.start()
    }

    private fun stiahni(odkaz: String, ciel: File, p: (Float) -> Unit) {
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
        k.inputStream.use { i -> ciel.outputStream().use { o ->
            val b = ByteArray(1 shl 16); var n: Int
            while (i.read(b).also { n = it } > 0) { o.write(b, 0, n); h += n; if (spolu > 0) p(h.toFloat() / spolu) }
        } }
        if (spolu > 0 && ciel.length() != spolu) throw IllegalStateException("Súbor sa nestiahol celý")
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

    // ------------------------------------------------ projekt videa

    /** popis → 7 záberov tej istej postavy (rôzne pózy a kamera, rovnaký štýl) */
    fun zabery(popis: String): List<String> {
        val p = popis.trim().trimEnd('.', ',')
        val kamera = listOf(
            "close-up portrait, looking at the viewer",
            "upper body, dynamic pose, hair flowing in the wind",
            "medium shot, slight smile, dramatic lighting",
            "side profile, looking away, cinematic",
            "upper body, turning around, motion",
            "low angle shot, confident pose, glowing particles around",
            "close-up face, intense eyes, dramatic shadows",
        )
        return kamera.take(POCET).map { "$p, $it, same character, consistent style, high detail, vertical composition" }
    }

    class Projekt(val dir: File) {
        val obrazky: List<File> get() = dir.listFiles { f -> f.name.startsWith("obr_") && f.extension == "png" }?.sortedBy { it.name } ?: emptyList()
        val mp4 get() = File(dir, "video.mp4")
        private val info get() = File(dir, "projekt.json")
        var hudba: String?
            get() = runCatching { JSONObject(info.readText()).optString("hudba").ifEmpty { null } }.getOrNull()
            set(v) { val j = runCatching { JSONObject(info.readText()) }.getOrDefault(JSONObject()); j.put("hudba", v ?: ""); info.writeText(j.toString()) }
        val seed: Long get() = dir.name.hashCode().toLong()
        val dlzka: Double get() = max(1, obrazky.size) * NA_OBRAZOK
    }

    /** z chatu: nakreslí zábery a vyrobí MP4. [stav] dostáva text priebehu, [hotovo] projekt alebo chybu. */
    fun vytvor(popis: String, stav: (String, Float) -> Unit, hotovo: (Projekt?, String) -> Unit) {
        val proj = Projekt(File(priecinok(), "video_${System.currentTimeMillis()}"))
        proj.dir.mkdirs()
        ChatJadro.nastavenia.hudbaVidea?.takeIf { File(it).exists() }?.let { proj.hudba = it }
        val zab = zabery(popis)
        ObrazkyJadro.nakresliViac(zab, proj.dir, { i, n -> stav(T("video_kreslim", i + 1, n), i.toFloat() / n * 0.85f) }) { obr, e ->
            if (obr.size < 2) { hotovo(null, e.ifBlank { T("video_malo") }); return@nakresliViac }
            exportuj(proj, { p -> stav(T("video_skladam", (p * 100).toInt()), 0.85f + p * 0.15f) }) { ok, chyba -> hotovo(if (ok) proj else null, chyba) }
        }
    }

    /** poskladá MP4 (ffmpeg); ak ffmpeg nie je, najprv ho stiahne */
    fun exportuj(proj: Projekt, p: (Float) -> Unit, hotovo: (Boolean, String) -> Unit) {
        val ff = ffmpeg()
        if (ff == null) {
            Platforma.naHlavnom { p(0f) }
            stiahniFfmpeg { exportuj(proj, p, hotovo) }
            return
        }
        pracuje = true
        Thread {
            val r = runCatching { koduj(ff, proj) { x -> Platforma.naHlavnom { priebeh = x; p(x) } } }
            Platforma.naHlavnom { pracuje = false; hotovo(r.isSuccess, r.exceptionOrNull()?.message ?: "") }
        }.start()
    }

    private fun koduj(ff: String, proj: Projekt, p: (Float) -> Unit) {
        val obr = proj.obrazky.map { ImageIO.read(it) }
        val r = Renderer(obr, proj.seed, SIRKA, VYSKA)
        val snimok = (proj.dlzka * FPS).toInt()
        val tmp = File(proj.dir, "video.tmp.mp4")
        val prikaz = mutableListOf(ff, "-y", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "bgr0", "-s", "${SIRKA}x$VYSKA", "-r", "$FPS", "-i", "-")
        val hudba = proj.hudba?.takeIf { File(it).exists() }
        if (hudba != null) prikaz += listOf("-i", hudba)
        prikaz += listOf("-map", "0:v")
        if (hudba != null) prikaz += listOf("-map", "1:a", "-c:a", "aac", "-b:a", "192k",
            "-af", "afade=t=out:st=${"%.2f".format(java.util.Locale.ROOT, proj.dlzka - 1.2)}:d=1.2", "-shortest")
        prikaz += listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", tmp.path)
        val proc = ProcessBuilder(prikaz).redirectErrorStream(true).start()
        val chyby = StringBuilder()
        Thread { runCatching { proc.inputStream.bufferedReader().forEachLine { chyby.appendLine(it) } } }.start()
        val buf = ByteBuffer.allocate(SIRKA * VYSKA * 4).order(ByteOrder.LITTLE_ENDIAN)
        proc.outputStream.buffered(1 shl 20).use { o ->
            for (i in 0 until snimok) {
                val img = r.snimka(i.toDouble() / FPS)
                val d = (img.raster.dataBuffer as DataBufferInt).data
                buf.clear(); buf.asIntBuffer().put(d)
                o.write(buf.array())
                if (i % 10 == 0) p(i.toFloat() / snimok)
            }
        }
        if (proc.waitFor() != 0 || !tmp.exists()) throw IllegalStateException("ffmpeg: " + chyby.toString().lines().lastOrNull { it.isNotBlank() }.orEmpty())
        proj.mp4.delete(); tmp.renameTo(proj.mp4)
        p(1f)
    }

    /** uloží MP4 do ~/Videos/Alexia AI */
    fun uloz(proj: Projekt): File? = runCatching {
        val d = File(System.getProperty("user.home"), "Videos/Alexia AI").apply { mkdirs() }
        proj.mp4.copyTo(File(d, "alexia_${proj.dir.name.removePrefix("video_")}.mp4"), overwrite = true)
    }.getOrNull()

    // ------------------------------------------------ hudba v náhľade

    @Volatile private var hudbaProces: Process? = null
    @Volatile private var hudbaLinka: SourceDataLine? = null

    fun hrajHudbu(cesta: String) {
        zastavHudbu()
        val ff = ffmpeg() ?: return
        Thread {
            runCatching {
                val p = ProcessBuilder(ff, "-loglevel", "quiet", "-i", cesta, "-f", "s16le", "-ac", "2", "-ar", "44100", "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                hudbaProces = p
                val f = AudioFormat(44100f, 16, 2, true, false)
                val l = AudioSystem.getSourceDataLine(f); hudbaLinka = l
                l.open(f); l.start()
                p.inputStream.use { i -> val b = ByteArray(8192); var n: Int; while (i.read(b).also { n = it } > 0) l.write(b, 0, n - n % 4) }
                l.drain(); l.close()
            }
        }.start()
    }

    fun zastavHudbu() {
        hudbaProces?.destroy(); hudbaProces = null
        hudbaLinka?.let { runCatching { it.stop(); it.flush(); it.close() } }; hudbaLinka = null
    }

    // ------------------------------------------------ vykresľovanie snímok (rovnaké pre náhľad aj MP4)

    class Renderer(private val obr: List<BufferedImage>, seed: Long, val w: Int, val v: Int) {
        private val rnd = Random(seed)
        private val prechody = List(obr.size) { rnd.nextInt(5) }
        private val smery = List(obr.size) { rnd.nextInt(4) }
        // atramentové škvrny pre prechod (pozície a veľkosti)
        private val skvrny = List(obr.size) { List(14) { Triple(rnd.nextDouble(), rnd.nextDouble(), 0.15 + rnd.nextDouble() * 0.35) } }
        private val iskry = List(70) { doubleArrayOf(rnd.nextDouble(), rnd.nextDouble(), 0.03 + rnd.nextDouble() * 0.12, 1.5 + rnd.nextDouble() * 4, rnd.nextDouble() * 6.28) }
        private val platno = BufferedImage(w, v, BufferedImage.TYPE_INT_RGB)
        private val vineta = BufferedImage(w, v, BufferedImage.TYPE_INT_ARGB).also { b ->
            val g = b.createGraphics()
            g.paint = RadialGradientPaint(Point2D.Float(w / 2f, v / 2f), max(w, v) * 0.75f, floatArrayOf(0.45f, 1f),
                arrayOf(Color(0, 0, 0, 0), Color(0, 0, 0, 190)), MultipleGradientPaint.CycleMethod.NO_CYCLE)
            g.fillRect(0, 0, w, v); g.dispose()
        }

        /** obrázok i s pohybom kamery v čase t (0..1 v rámci jeho úseku) */
        private fun kresliObr(g: Graphics2D, i: Int, t: Double, extraZoom: Double = 1.0) {
            val o = obr[i]
            val pokryt = max(w.toDouble() / o.width, v.toDouble() / o.height)
            val smer = smery[i]
            val zoom = (if (smer % 2 == 0) 1.06 + 0.14 * t else 1.20 - 0.14 * t) * extraZoom
            val s = pokryt * zoom
            val dw = o.width * s; val dv = o.height * s
            val volneX = dw - w; val volneY = dv - v
            val px = when (smer) { 0 -> 0.5; 1 -> 0.35 + 0.3 * t; 2 -> 0.65 - 0.3 * t; else -> 0.5 }
            val py = when (smer) { 0 -> 0.45 + 0.1 * t; 3 -> 0.6 - 0.2 * t; else -> 0.5 }
            g.drawImage(o, (-volneX * px).toInt(), (-volneY * py).toInt(), dw.toInt(), dv.toInt(), null)
        }

        fun snimka(cas: Double): BufferedImage {
            val g = platno.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val n = obr.size
            val i = min(n - 1, (cas / NA_OBRAZOK).toInt())
            val tUsek = ((cas - i * NA_OBRAZOK) / NA_OBRAZOK).coerceIn(0.0, 1.0)
            kresliObr(g, i, tUsek)

            // prechod do ďalšieho obrázka na konci úseku
            val doKonca = (i + 1) * NA_OBRAZOK - cas
            if (i + 1 < n && doKonca < PRECHOD) {
                val k = 1 - doKonca / PRECHOD   // 0 → 1
                prechod(g, i, k)
            }
            // záblesk na začiatku každého obrázka (okrem prvého)
            if (i > 0 && cas - i * NA_OBRAZOK < 0.12) {
                val a = (1 - (cas - i * NA_OBRAZOK) / 0.12).toFloat() * 0.55f
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a)
                g.color = if (prechody[i - 1] == 1) Color(255, 40, 40) else Color.WHITE
                g.fillRect(0, 0, w, v)
                g.composite = AlphaComposite.SrcOver
            }
            iskry(g, cas)
            g.drawImage(vineta, 0, 0, null)
            // stmavenie na začiatku a konci
            val dlzka = n * NA_OBRAZOK
            val f = when { cas < 0.4 -> 1 - cas / 0.4; cas > dlzka - 0.8 -> (cas - (dlzka - 0.8)) / 0.8; else -> 0.0 }.coerceIn(0.0, 1.0)
            if (f > 0) { g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, f.toFloat()); g.color = Color.BLACK; g.fillRect(0, 0, w, v); g.composite = AlphaComposite.SrcOver }
            g.dispose()
            return platno
        }

        private fun prechod(g: Graphics2D, i: Int, k: Double) {
            when (prechody[i]) {
                0 -> { // prelínanie s priblížením
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, k.toFloat())
                    kresliObr(g, i + 1, 0.0, 1.0 + 0.25 * (1 - k))
                    g.composite = AlphaComposite.SrcOver
                }
                1 -> { // červený záblesk
                    if (k > 0.5) kresliObr(g, i + 1, 0.0)
                    val a = (1 - kotlin.math.abs(k - 0.5) * 2).toFloat()
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a * 0.85f)
                    g.color = Color(200, 0, 20); g.fillRect(0, 0, w, v)
                    g.composite = AlphaComposite.SrcOver
                }
                2 -> { // atrament: nový obrázok preniká cez rastúce škvrny
                    val oblast = Area()
                    skvrny[i].forEach { (x, y, r) ->
                        val rr = r * k * 1.9 * max(w, v)
                        oblast.add(Area(Ellipse2D.Double(x * w - rr / 2, y * v - rr / 2, rr, rr)))
                    }
                    val stary = g.clip
                    // čierny okraj škvŕn
                    g.color = Color(10, 10, 10)
                    g.stroke = BasicStroke((18 * (1 - k) + 4).toFloat())
                    g.draw(oblast)
                    g.clip = oblast
                    kresliObr(g, i + 1, 0.0)
                    g.clip = stary
                }
                3 -> { // glitch: pruhy posunuté do strán
                    val pruhov = 14
                    val ph = v / pruhov + 1
                    for (p in 0 until pruhov) {
                        val posun = ((if (p % 2 == 0) 1 else -1) * (1 - k) * w * 0.6 * ((p * 37 % 7) / 7.0 + 0.3)).toInt()
                        val c = g.create(0, p * ph, w, ph) as Graphics2D
                        c.translate(posun, -p * ph)
                        if (k > (p % 3) * 0.25) kresliObr(c, i + 1, 0.0)
                        c.dispose()
                    }
                }
                else -> { // „úder“: priblíženie a rozmazanie pohybom
                    for (s in 0 until 4) {
                        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (k * (0.35 + s * 0.2)).toFloat().coerceAtMost(1f))
                        kresliObr(g, i + 1, 0.0, 1.0 + (1 - k) * (0.5 - s * 0.1))
                    }
                    g.composite = AlphaComposite.SrcOver
                }
            }
        }

        private fun iskry(g: Graphics2D, cas: Double) {
            for (s in iskry) {
                val y = ((s[1] - cas * s[2]) % 1.0 + 1.0) % 1.0
                val x = s[0] + sin(cas * 1.3 + s[4]) * 0.02
                val r = s[3] * (w / 1080.0)
                val blik = (0.55 + 0.45 * sin(cas * 6 + s[4])).toFloat()
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f * blik)
                g.color = Color(255, 60, 40)
                g.fill(Ellipse2D.Double(x * w - r * 3, y * v - r * 3, r * 6, r * 6))
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f * blik)
                g.color = Color(255, 150 + (s[4] * 15).toInt().coerceAtMost(90), 120)
                g.fill(Ellipse2D.Double(x * w - r / 2, y * v - r / 2, r, r))
            }
            g.composite = AlphaComposite.SrcOver
        }
    }
}

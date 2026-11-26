package sk.alexia.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Postava spieva: samostatný proces (ONNX Runtime je oddelený od appky – nemôže sa pobiť s knižnicou hlasov
 * a pri páde nespadne celá appka). Pery na obrázku sa prekreslia podľa spevu (Wav2Lip GAN 96),
 * pod video ide pôvodná skladba.
 *
 * Argumenty: priecinokModelov obrazok skladba ffmpeg vystup.mp4
 * Výstup na stdout: „P 0.42 text“ = priebeh, „O cesta“ = hotovo, „E správa“ = chyba.
 */
object SpevProces {
    private const val SR = 16000
    private const val NFFT = 800
    private const val HOP = 200
    private const val MELS = 80
    private const val FPS = 25
    private const val KUS = 16          // mel snímok na jeden snímok videa (0,2 s)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val (modely, obrazok, skladba, ff, vystup) = args.map { it }
            subStavu = File("$vystup.stav")
            spusti(File(modely), File(obrazok), File(skladba), ff, File(vystup))
            riadok("O $vystup")
        } catch (t: Throwable) {
            riadok("E " + (t.message ?: t.toString()).replace('\n', ' '))
            kotlin.system.exitProcess(1)
        }
        kotlin.system.exitProcess(0)
    }

    /** stav aj do súboru – okno appky na Windows nemusí mať napojený výstup konzoly */
    private var subStavu: File? = null
    private fun riadok(l: String) { println(l); System.out.flush(); runCatching { subStavu?.writeText(l) } }
    private fun stav(p: Float, t: String) = riadok("P %.4f %s".format(java.util.Locale.ROOT, p, t))

    private fun spusti(modely: File, obrazok: File, skladba: File, ff: String, vystup: File) {
        // 1) spev zo skladby: stred stereo obrazu (spev býva v strede), bez basov a výšok, 16 kHz mono
        stav(0f, "spev")
        val pcm = File(vystup.path + ".pcm")
        prikaz(listOf(ff, "-y", "-v", "error", "-i", skladba.path, "-af", "highpass=f=180,lowpass=f=6000", "-ac", "1", "-ar", "$SR", "-f", "f32le", pcm.path))
        val zvuk = citajF32(pcm); pcm.delete()
        if (zvuk.size < SR) throw IllegalStateException("Skladba je príliš krátka")
        val mel = melSpektrogram(zvuk)                      // [MELS][T]
        val t = mel[0].size
        val snimok = (zvuk.size.toDouble() / SR * FPS).toInt()

        // 2) tvár na obrázku
        stav(0.02f, "tvar")
        var img = ImageIO.read(obrazok) ?: throw IllegalStateException("Obrázok sa nedá načítať")
        // párne rozmery (H.264)
        val w = img.width / 2 * 2; val h = img.height / 2 * 2
        if (w != img.width || h != img.height) img = img.getSubimage(0, 0, w, h)
        val env = OrtEnvironment.getEnvironment()
        val opt = OrtSession.SessionOptions().apply { setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() - 1)) }
        val box = env.createSession(File(modely, "yoloface_8n.onnx").path, opt).use { najdiTvar(env, it, img) }
            ?: stredTvare(w, h)
        // štvorcový výrez okolo tváre, trochu nižšie (brada)
        val strana = (maxOf(box[2] - box[0], box[3] - box[1]) * 1.05f).roundToInt().coerceIn(48, minOf(w, h))
        val cx = (box[0] + box[2]) / 2; val cy = (box[1] + box[3]) / 2 + strana * 0.06f
        val x0 = (cx - strana / 2f).roundToInt().coerceIn(0, w - strana)
        val y0 = (cy - strana / 2f).roundToInt().coerceIn(0, h - strana)
        val vyrez = zmen(img.getSubimage(x0, y0, strana, strana), 96, 96)
        val vstupTvare = pripravTvar(vyrez)                 // [6][96][96] BGR, spodná polovica prvej trojice = 0

        // 3) ffmpeg: snímky dnu, video s jemným pohybom kamery + pôvodná skladba von
        val ow = if (w > h) 1920 else (1080.0 * w / h / 2).roundToInt() * 2
        val oh = if (w > h) (1920.0 * h / w / 2).roundToInt() * 2 else 1080
        val vf = "scale=${ow * 2}:${oh * 2}:flags=lanczos," +
            "zoompan=z='1.04+0.015*sin(2*PI*on/125)':x='iw/2-(iw/zoom/2)+8*sin(2*PI*on/230)':y='ih/2-(ih/zoom/2)+5*sin(2*PI*on/170)':d=1:s=${ow}x$oh:fps=$FPS,format=yuv420p"
        val enc = ProcessBuilder(ff, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${w}x$h", "-r", "$FPS", "-i", "-",
            "-i", skladba.path, "-map", "0:v:0", "-map", "1:a:0", "-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
            "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", vystup.path)
            .redirectErrorStream(true).start()
        val chyby = StringBuilder()
        Thread { enc.inputStream.bufferedReader().forEachLine { chyby.appendLine(it) } }.apply { isDaemon = true }.start()

        val zaklad = IntArray(w * h).also { img.getRGB(0, 0, w, h, it, 0, w) }
        val maska = maskaUst(strana)
        val riadok = ByteArray(w * h * 3)
        val melVstup = FloatArray(MELS * KUS)
        env.createSession(File(modely, "wav2lip_gan_96.onnx").path, opt).use { s ->
            val mena = s.inputNames.toList()
            val menoZvuku = mena.firstOrNull { n -> s.inputInfo[n]!!.info.toString().contains("80") } ?: mena.first()
            val menoTvare = mena.first { it != menoZvuku }
            val tvarT = OnnxTensor.createTensor(env, FloatBuffer.wrap(vstupTvare), longArrayOf(1, 6, 96, 96))
            enc.outputStream.buffered(1 shl 20).use { out ->
                for (i in 0 until snimok) {
                    var od = (i * (80.0 / FPS)).toInt()
                    if (od + KUS > t) od = maxOf(0, t - KUS)
                    for (m in 0 until MELS) for (k in 0 until KUS) melVstup[m * KUS + k] = mel[m].getOrElse(od + k) { -4f }
                    val melT = OnnxTensor.createTensor(env, FloatBuffer.wrap(melVstup), longArrayOf(1, 1, MELS.toLong(), KUS.toLong()))
                    val vysl = s.run(mapOf(menoZvuku to melT, menoTvare to tvarT)).use { r ->
                        (r[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    }
                    melT.close()
                    // 96×96 BGR [0,1] → obrázok, zväčšiť na výrez, vložiť len oblasť úst s mäkkým okrajom
                    val usta = BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB)
                    for (y in 0 until 96) for (x in 0 until 96) {
                        fun c(ch: Int) = (vysl[ch * 9216 + y * 96 + x].coerceIn(0f, 1f) * 255).roundToInt()
                        usta.setRGB(x, y, (c(2) shl 16) or (c(1) shl 8) or c(0))
                    }
                    val vacsie = zmen(usta, strana, strana)
                    var p = 0
                    for (y in 0 until h) for (x in 0 until w) {
                        var rgb = zaklad[y * w + x]
                        val lx = x - x0; val ly = y - y0
                        if (lx in 0 until strana && ly in 0 until strana) {
                            val a = maska[ly * strana + lx]
                            if (a > 0f) {
                                val n = vacsie.getRGB(lx, ly)
                                fun mix(sh: Int) = (((rgb shr sh) and 255) * (1 - a) + ((n shr sh) and 255) * a).roundToInt()
                                rgb = (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
                            }
                        }
                        riadok[p++] = (rgb shr 16).toByte(); riadok[p++] = (rgb shr 8).toByte(); riadok[p++] = rgb.toByte()
                    }
                    out.write(riadok)
                    if (i % 10 == 0) stav(0.03f + 0.95f * i / snimok, "snimky $i $snimok")
                }
            }
            tvarT.close()
        }
        val kod = enc.waitFor()
        if (kod != 0 || !vystup.exists()) throw IllegalStateException("ffmpeg: " + chyby.lines().lastOrNull { it.isNotBlank() }.orEmpty())
        stav(1f, "hotovo")
    }

    // ---------- tvár ----------

    /** YOLOface 8n: 640×640, výstup [1, 20, 8400] (stred x, y, šírka, výška, skóre, 5 bodov). Vráti x1, y1, x2, y2. */
    private fun najdiTvar(env: OrtEnvironment, s: OrtSession, img: BufferedImage): FloatArray? {
        val k = 640f / maxOf(img.width, img.height)
        val nw = (img.width * k).roundToInt(); val nh = (img.height * k).roundToInt()
        val mensi = zmen(img, nw, nh)
        for (bgr in listOf(false, true)) {
            val d = FloatArray(3 * 640 * 640)
            for (y in 0 until nh) for (x in 0 until nw) {
                val c = mensi.getRGB(x, y)
                val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
                d[y * 640 + x] = if (bgr) b else r; d[409600 + y * 640 + x] = g; d[819200 + y * 640 + x] = if (bgr) r else b
            }
            val vst = OnnxTensor.createTensor(env, FloatBuffer.wrap(d), longArrayOf(1, 3, 640, 640))
            val o = s.run(mapOf(s.inputNames.first() to vst)).use { r -> (r[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } } }
            vst.close()
            val n = o.size / 20
            var naj = -1; var skore = 0.35f
            for (i in 0 until n) if (o[4 * n + i] > skore) { skore = o[4 * n + i]; naj = i }
            if (naj >= 0) {
                val xc = o[naj] / k; val yc = o[n + naj] / k; val bw = o[2 * n + naj] / k; val bh = o[3 * n + naj] / k
                return floatArrayOf(xc - bw / 2, yc - bh / 2, xc + bw / 2, yc + bh / 2)
            }
        }
        return null
    }

    /** keď detektor nič nenájde: tvár býva v hornej tretine stredu (portrét) */
    private fun stredTvare(w: Int, h: Int): FloatArray {
        val s = minOf(w, h) * 0.4f
        return floatArrayOf(w / 2f - s / 2, h * 0.38f - s / 2, w / 2f + s / 2, h * 0.38f + s / 2)
    }

    /** Wav2Lip chce 6 kanálov: tvár so zakrytou spodnou polovicou + celá tvár, BGR, [0,1] */
    private fun pripravTvar(v: BufferedImage): FloatArray {
        val d = FloatArray(6 * 9216)
        for (y in 0 until 96) for (x in 0 until 96) {
            val c = v.getRGB(x, y)
            val bgr = floatArrayOf((c and 255) / 255f, ((c shr 8) and 255) / 255f, ((c shr 16) and 255) / 255f)
            for (ch in 0 until 3) {
                d[ch * 9216 + y * 96 + x] = if (y >= 48) 0f else bgr[ch]
                d[(3 + ch) * 9216 + y * 96 + x] = bgr[ch]
            }
        }
        return d
    }

    /** oblasť úst: spodných ~55 % výrezu, mäkké okraje (inak by bol vidieť štvorec) */
    private fun maskaUst(s: Int): FloatArray {
        val m = FloatArray(s * s)
        for (y in 0 until s) for (x in 0 until s) {
            val fy = y.toFloat() / s; val fx = x.toFloat() / s
            val zvislo = ((fy - 0.42f) / 0.12f).coerceIn(0f, 1f) * ((0.98f - fy) / 0.06f).coerceIn(0f, 1f)
            val vodorovne = ((fx - 0.08f) / 0.12f).coerceIn(0f, 1f) * ((0.92f - fx) / 0.12f).coerceIn(0f, 1f)
            m[y * s + x] = zvislo * vodorovne
        }
        return m
    }

    private fun zmen(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val d = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        d.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(src, 0, 0, w, h, null); dispose()
        }
        return d
    }

    // ---------- zvuk: mel spektrogram presne ako pri trénovaní Wav2Lip ----------

    private fun citajF32(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    /** 16 kHz, n_fft 800, hop 200, Hann, preemfáza 0,97, 80 mel (55–7600 Hz, Slaney), dB normalizované do [-4, 4] */
    fun melSpektrogram(x0: FloatArray): Array<FloatArray> {
        val x = FloatArray(x0.size)
        x[0] = x0[0]
        for (i in 1 until x0.size) x[i] = x0[i] - 0.97f * x0[i - 1]
        // center = True: zrkadlové doplnenie o n_fft/2
        val pad = NFFT / 2
        val p = FloatArray(x.size + 2 * pad)
        for (i in p.indices) {
            var j = i - pad
            if (j < 0) j = -j
            if (j >= x.size) j = 2 * (x.size - 1) - j
            p[i] = x[j.coerceIn(0, x.size - 1)]
        }
        val ramcov = 1 + x.size / HOP
        val okno = FloatArray(NFFT) { (0.5 - 0.5 * cos(2 * PI * it / NFFT)).toFloat() }
        val binov = NFFT / 2 + 1
        val cosT = Array(binov) { k -> FloatArray(NFFT) { n -> cos(2 * PI * k * n / NFFT).toFloat() } }
        val sinT = Array(binov) { k -> FloatArray(NFFT) { n -> sin(2 * PI * k * n / NFFT).toFloat() } }
        val filtre = melFiltre(binov)
        val vysl = Array(MELS) { FloatArray(ramcov) }
        val ram = FloatArray(NFFT); val mag = FloatArray(binov)
        for (r in 0 until ramcov) {
            val od = r * HOP
            for (n in 0 until NFFT) ram[n] = p.getOrElse(od + n) { 0f } * okno[n]
            for (k in 0 until binov) {
                var re = 0f; var im = 0f
                val c = cosT[k]; val s = sinT[k]
                for (n in 0 until NFFT) { re += ram[n] * c[n]; im -= ram[n] * s[n] }
                mag[k] = sqrt(re * re + im * im)
            }
            for (m in 0 until MELS) {
                var e = 0f
                val f = filtre[m]
                for (k in 0 until binov) if (f[k] != 0f) e += f[k] * mag[k]
                val db = 20f * log10(maxOf(1e-5f, e)) - 20f
                vysl[m][r] = (8f * ((db + 100f) / 100f) - 4f).coerceIn(-4f, 4f)
            }
        }
        return vysl
    }

    private fun hzNaMel(f: Double): Double = if (f < 1000) f / (200.0 / 3) else 15.0 + ln(f / 1000) / (ln(6.4) / 27)
    private fun melNaHz(m: Double): Double = if (m < 15.0) m * (200.0 / 3) else 1000 * Math.exp((ln(6.4) / 27) * (m - 15.0))

    private fun melFiltre(binov: Int): Array<FloatArray> {
        val mMin = hzNaMel(55.0); val mMax = hzNaMel(7600.0)
        val hz = DoubleArray(MELS + 2) { melNaHz(mMin + (mMax - mMin) * it / (MELS + 1)) }
        val fft = DoubleArray(binov) { it * SR.toDouble() / NFFT }
        return Array(MELS) { i ->
            val en = 2.0 / (hz[i + 2] - hz[i])
            FloatArray(binov) { k ->
                val dolu = (fft[k] - hz[i]) / (hz[i + 1] - hz[i])
                val hore = (hz[i + 2] - fft[k]) / (hz[i + 2] - hz[i + 1])
                (maxOf(0.0, minOf(dolu, hore)) * en).toFloat()
            }
        }
    }

    private fun prikaz(p: List<String>) {
        val pr = ProcessBuilder(p).redirectErrorStream(true).start()
        val v = pr.inputStream.bufferedReader().readText()
        if (pr.waitFor() != 0) throw IllegalStateException("ffmpeg: " + v.lines().lastOrNull { it.isNotBlank() }.orEmpty())
    }
}

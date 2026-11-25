package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sk.alexia.ai.obrazky.Sd
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vylepšenie fotky bez zmeny obsahu: AI zväčšenie (Real-ESRGAN x4plus), automatický jas/kontrast,
 * živšie farby, doostrenie. Ľudia ani predmety na fotke sa nemenia.
 */
object VylepsenieJadro {
    const val URL_MODELU = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth"
    const val MB = 67
    private val vlakno = Executors.newSingleThreadExecutor { Thread(it, "vylepsenie") }
    private var h = 0L

    private var _vstup by mutableStateOf<File?>(null)
    /** nová fotka zmaže starý výsledok */
    var vstup: File?
        get() = _vstup
        set(v) { _vstup = v; vysledok = null }
    var vysledok by mutableStateOf<File?>(null)
        private set
    /** skutočný rozmer výsledku (náhľad môže byť zmenšený) */
    var rozmerVysledku by mutableStateOf(0 to 0)
        private set
    var pracuje by mutableStateOf(false)
        private set
    var cinnost by mutableStateOf("")
        private set
    var priebehStahovania by mutableStateOf<Float?>(null)
        private set
    var chyba by mutableStateOf("")
        private set

    var nasobok by mutableStateOf(2)          // 1 = bez zväčšenia, 2, 4, K4 = 4K, K8 = 8K
    const val K4 = 40
    const val K8 = 80
    var autoJas by mutableStateOf(true)
    var farby by mutableStateOf(true)
    var doostrit by mutableStateOf(false)

    fun model() = File(Platforma.priecinok, "vylepsenie/RealESRGAN_x4plus.pth")
    fun mamModel() = model().let { it.exists() && it.length() > 60_000_000L }

    fun stiahniModel() {
        if (priebehStahovania != null) return
        priebehStahovania = 0f; chyba = ""
        Thread {
            val r = runCatching {
                val ciel = model(); ciel.parentFile.mkdirs(); val tmp = File(ciel.path + ".part")
                var url = URL(URL_MODELU); var k: HttpURLConnection; var skoky = 0
                while (true) {
                    k = url.openConnection() as HttpURLConnection
                    k.instanceFollowRedirects = false; k.connectTimeout = 20_000; k.readTimeout = 60_000
                    val kod = k.responseCode
                    if (kod in 300..399 && skoky++ < 8) { url = URL(url, k.getHeaderField("Location")); k.disconnect(); continue }
                    if (kod != 200) throw IllegalStateException("Server vrátil $kod")
                    break
                }
                val spolu = k.contentLengthLong; var h2 = 0L
                k.inputStream.use { i -> tmp.outputStream().use { o ->
                    val b = ByteArray(1 shl 16); var n: Int
                    while (i.read(b).also { n = it } > 0) { o.write(b, 0, n); h2 += n; if (spolu > 0) { val p = h2.toFloat() / spolu; Platforma.naHlavnom { priebehStahovania = p } } }
                } }
                val zip = tmp.inputStream().use { val b = ByteArray(2); it.read(b); b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
                if ((spolu > 0 && tmp.length() != spolu) || !zip) { tmp.delete(); throw IllegalStateException("Model sa nestiahol celý") }
                ciel.delete(); tmp.renameTo(ciel)
            }
            Platforma.naHlavnom { priebehStahovania = null; r.exceptionOrNull()?.let { chyba = it.message ?: "Chyba sťahovania" } }
        }.start()
    }

    fun vylepsi() {
        val f = vstup ?: return
        if (pracuje) return
        if (nasobok > 1 && !mamModel()) { chyba = T("vyl_treba_model"); return }
        pracuje = true; chyba = ""; vysledok = null
        // vo VRAM nech je len vylepšovací model
        ChatJadro.uvolniPamat(); ObrazkyJadro.uvolniPamat()
        val nas = nasobok; val jas = autoJas; val far = farby; val ostr = doostrit
        vlakno.execute {
            val r = runCatching { spracuj(f, nas, jas, far, ostr) }
            Platforma.naHlavnom {
                pracuje = false; cinnost = ""
                r.onSuccess { vysledok = it; ObrazkyJadro.galeria.add(0, it) }
                r.onFailure { chyba = it.message ?: T("vyl_zlyhalo") }
            }
        }
    }

    /** samotné vylepšenie (beží na vlákne vylepšovania) → uložený PNG */
    private fun spracuj(f: File, nas: Int, jas: Boolean, far: Boolean, ostr: Boolean): File {
            var img = ImageIO.read(f) ?: throw IllegalStateException(T("vyl_nie_obrazok"))
            img = naRgb(img)
            if (nas == 2 || nas == 4) {
                // veľké fotky najprv zmenšíme, aby výsledok mal rozumnú veľkosť (max ~6000 px)
                val limit = if (nas == 4) 1500 else 1500 * 2
                val dlhsia = max(img.width, img.height)
                if (dlhsia > limit) img = zmen(img, limit.toDouble() / dlhsia)
                // pre 2× najprv zmenšíme na polovicu → AI 4× → výsledok je 2× pôvodnej
                val pred = if (nas == 2) zmen(img, 0.5) else img
                val v = aiX4(pred, "")
                img = if (nas == 2) zmen(v, img.width * 2.0 / v.width) else v
            } else if (nas == K4 || nas == K8) {
                // cieľ: dlhšia strana 3840 (4K) alebo 7680 (8K); AI 4× aj dvakrát po sebe
                val ciel = if (nas == K4) 3840 else 7680
                var priechod = 1
                while (max(img.width, img.height) < ciel && priechod <= 2) {
                    val d = max(img.width, img.height)
                    // aby sme neprestrelili cieľ (zbytočne obrovský medzivýsledok), vstup zmenšíme na cieľ/4
                    val vstupAi = if (d * 4 > ciel) zmen(img, ciel / 4.0 / d) else img
                    img = aiX4(vstupAi, if (nas == K8) " ($priechod/2)" else "")
                    priechod++
                }
                val d = max(img.width, img.height)
                if (d != ciel) img = zmen(img, ciel.toDouble() / d)
            }
            Platforma.naHlavnom { cinnost = T("vyl_upravujem") }
            if (jas) img = autoJasKontrast(img)
            if (far) img = sytost(img, 1.15f)
            if (ostr) img = doostri(img)
            val ciel = File(Platforma.obrazky, "alexia_vylepsene_${System.currentTimeMillis()}.png")
            ImageIO.write(img, "png", ciel)
            val rr = img.width to img.height
            Platforma.naHlavnom { rozmerVysledku = rr }
            return ciel
    }

    /** vylepšenie fotky poslanej z mobilu (čaká na výsledok; volá sa z vlákna servera) */
    fun preMobil(f: File, nas: Int, jas: Boolean, far: Boolean, ostr: Boolean): File {
        if (nas > 1 && !mamModel()) throw IllegalStateException(T("vyl_treba_model"))
        Platforma.naHlavnom { ChatJadro.uvolniPamat(); ObrazkyJadro.uvolniPamat() }
        val v = vlakno.submit<File> { spracuj(f, nas, jas, far, ostr) }.get()
        Platforma.naHlavnom { ObrazkyJadro.galeria.add(0, v) }
        return v
    }

    /** jeden priechod Real-ESRGAN (4×) */
    private fun aiX4(pred: BufferedImage, popis: String): BufferedImage {
        Platforma.naHlavnom { cinnost = T("vyl_zvacsujem") + popis }
        if (h == 0L) {
            h = Sd.upscalerNacitaj(model().absolutePath, ChatJadro.nastavenia.vlakna)
            if (h == 0L) throw IllegalStateException(Sd.chyba())
        }
        val px = pred.getRGB(0, 0, pred.width, pred.height, null, 0, pred.width)
        val roz = IntArray(2)
        val out = Sd.zvacsi(h, px, pred.width, pred.height, roz) ?: throw IllegalStateException(Sd.chyba())
        return BufferedImage(roz[0], roz[1], BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, roz[0], roz[1], out, 0, roz[0]) }
    }

    /** AI 4× pre jednu snímku videa (ARGB pixely) – používa VylepsenieVidea */
    @Synchronized
    fun zvacsiSnimku(px: IntArray, w: Int, v: Int, roz: IntArray): IntArray {
        if (h == 0L) {
            h = Sd.upscalerNacitaj(model().absolutePath, ChatJadro.nastavenia.vlakna)
            if (h == 0L) throw IllegalStateException(Sd.chyba())
        }
        return Sd.zvacsi(h, px, w, v, roz) ?: throw IllegalStateException(Sd.chyba())
    }

    fun uvolniPamat() {
        if (pracuje || VylepsenieVidea.pracuje) return
        vlakno.execute { if (h != 0L) { Sd.upscalerUvolni(h); h = 0L } }
    }

    // ------------------------------------------------ klasické úpravy (bez AI)

    private fun naRgb(i: BufferedImage): BufferedImage {
        if (i.type == BufferedImage.TYPE_INT_RGB) return i
        val o = BufferedImage(i.width, i.height, BufferedImage.TYPE_INT_RGB)
        o.createGraphics().apply { color = java.awt.Color.WHITE; fillRect(0, 0, i.width, i.height); drawImage(i, 0, 0, null); dispose() }
        return o
    }

    private fun zmen(i: BufferedImage, k: Double): BufferedImage {
        val w = max(1, (i.width * k).roundToInt()); val v = max(1, (i.height * k).roundToInt())
        val o = BufferedImage(w, v, BufferedImage.TYPE_INT_RGB)
        o.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(i, 0, 0, w, v, null); dispose()
        }
        return o
    }

    /** roztiahne jas tak, aby 0,5 % najtmavších bolo čiernych a 0,5 % najsvetlejších bielych (spoločne pre RGB – farby sa neposunú) */
    private fun autoJasKontrast(i: BufferedImage): BufferedImage {
        val px = i.getRGB(0, 0, i.width, i.height, null, 0, i.width)
        val hist = IntArray(256)
        for (p in px) hist[((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000]++
        val okraj = px.size / 200
        var lo = 0; var s = 0; while (lo < 255 && s + hist[lo] <= okraj) { s += hist[lo]; lo++ }
        var hi = 255; s = 0; while (hi > 0 && s + hist[hi] <= okraj) { s += hist[hi]; hi-- }
        if (hi - lo < 30 || (lo < 3 && hi > 252)) return i            // už je v poriadku
        val lut = IntArray(256) { ((it - lo) * 255.0 / (hi - lo)).roundToInt().coerceIn(0, 255) }
        for (k in px.indices) { val p = px[k]; px[k] = (lut[p shr 16 and 255] shl 16) or (lut[p shr 8 and 255] shl 8) or lut[p and 255] }
        return BufferedImage(i.width, i.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, i.width, i.height, px, 0, i.width) }
    }

    private fun sytost(i: BufferedImage, k: Float): BufferedImage {
        val px = i.getRGB(0, 0, i.width, i.height, null, 0, i.width)
        for (j in px.indices) {
            val p = px[j]; val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
            val sed = 0.299f * r + 0.587f * g + 0.114f * b
            fun c(x: Int) = (sed + (x - sed) * k).roundToInt().coerceIn(0, 255)
            px[j] = (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
        return BufferedImage(i.width, i.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, i.width, i.height, px, 0, i.width) }
    }

    /** jemné doostrenie (unsharp mask 3×3) */
    private fun doostri(i: BufferedImage): BufferedImage {
        val w = i.width; val v = i.height
        val px = i.getRGB(0, 0, w, v, null, 0, w); val out = px.copyOf()
        for (y in 1 until v - 1) for (x in 1 until w - 1) {
            var o = 0
            for (sh in intArrayOf(16, 8, 0)) {
                val c = px[y * w + x] shr sh and 255
                var sum = 0
                for (dy in -1..1) for (dx in -1..1) sum += px[(y + dy) * w + x + dx] shr sh and 255
                val ostra = c + ((c - sum / 9) * 0.6f).roundToInt()
                o = o or (min(255, max(0, ostra)) shl sh)
            }
            out[y * w + x] = o
        }
        return BufferedImage(w, v, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, v, out, 0, w) }
    }
}

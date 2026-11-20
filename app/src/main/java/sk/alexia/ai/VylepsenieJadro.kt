package sk.alexia.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sk.alexia.ai.obrazky.Sd
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vylepšenie fotky v mobile: AI zväčšenie (Real-ESRGAN, obsah sa nemení) a klasické úpravy jasu, farieb a ostrosti.
 * Fotka sa skopíruje do appky (systémový výber fotiek – appka nemá prístup k ostatným súborom).
 */
object VylepsenieJadro {
    const val URL_MODELU = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth"
    const val MB = 67
    const val K4 = 40
    private val vlakno = Executors.newSingleThreadExecutor { Thread(it, "vylepsenie") }
    private var h = 0L

    var vstup by mutableStateOf<File?>(null)
        private set
    var vysledok by mutableStateOf<File?>(null)
        private set
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

    var nasobok by mutableStateOf(2)          // 1 = bez zväčšenia, 2, 4, K4 = 4K
    var autoJas by mutableStateOf(true)
    var farby by mutableStateOf(true)
    var doostrit by mutableStateOf(false)

    fun model() = File(Platforma.priecinok, "vylepsenie/RealESRGAN_x4plus.pth")
    fun mamModel() = model().let { it.exists() && it.length() > 60_000_000L }

    /** skopíruje vybranú fotku do appky */
    fun nastavVstup(uri: Uri) {
        chyba = ""
        Thread {
            val r = runCatching {
                val f = File(Platforma.app.cacheDir, "vstup_${System.currentTimeMillis()}")
                Platforma.app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                f
            }
            Platforma.naHlavnom {
                r.onSuccess { vstup?.delete(); vstup = it; vysledok = null }
                r.onFailure { chyba = it.message ?: T("vyl_zlyhalo") }
            }
        }.start()
    }

    fun stiahniModel() {
        if (priebehStahovania != null) return
        priebehStahovania = 0f; chyba = ""
        Thread {
            val r = runCatching {
                val ciel = model(); ciel.parentFile?.mkdirs(); val tmp = File(ciel.path + ".part")
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

    /** obrázok v pamäti ako ARGB pixely */
    private class Obr(val w: Int, val v: Int, val px: IntArray)

    private fun nacitaj(f: File): Obr {
        // veľké fotky z mobilu dekódujeme zmenšené (inak by sa nezmestili do pamäte)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var vz = 1; while (max(o.outWidth, o.outHeight) / vz > 4096) vz *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = vz; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IllegalStateException(T("vyl_nie_obrazok"))
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val r = Obr(b.width, b.height, px); b.recycle()
        return r
    }

    private fun zmen(i: Obr, k: Double): Obr {
        val w = max(1, (i.w * k).roundToInt()); val v = max(1, (i.v * k).roundToInt())
        val b = Bitmap.createBitmap(i.px, i.w, i.v, Bitmap.Config.ARGB_8888)
        val s = Bitmap.createScaledBitmap(b, w, v, true); if (s !== b) b.recycle()
        val px = IntArray(w * v); s.getPixels(px, 0, w, 0, 0, w, v); s.recycle()
        return Obr(w, v, px)
    }

    private fun aiX4(i: Obr, popis: String): Obr {
        Platforma.naHlavnom { cinnost = T("vyl_zvacsujem") + popis }
        if (h == 0L) {
            h = Sd.upscalerNacitaj(model().absolutePath, ChatJadro.nastavenia.vlakna)
            if (h == 0L) throw IllegalStateException(Sd.chyba())
        }
        val roz = IntArray(2)
        val out = Sd.zvacsi(h, i.px, i.w, i.v, roz) ?: throw IllegalStateException(Sd.chyba())
        return Obr(roz[0], roz[1], out)
    }

    fun vylepsi() {
        val f = vstup ?: return
        if (pracuje) return
        if (nasobok > 1 && !mamModel()) { chyba = T("vyl_treba_model"); return }
        pracuje = true; chyba = ""; vysledok = null
        ChatJadro.uvolniPamat(); ObrazkyJadro.uvolniPamat()
        val nas = nasobok; val jas = autoJas; val far = farby; val ostr = doostrit
        vlakno.execute {
            val r = runCatching {
                var img = nacitaj(f)
                if (nas == 2 || nas == 4) {
                    // na mobile menšie limity: 4× z max 1000 px, 2× z max 2000 px
                    val limit = if (nas == 4) 1000 else 2000
                    val d = max(img.w, img.v)
                    if (d > limit) img = zmen(img, limit.toDouble() / d)
                    val pred = if (nas == 2) zmen(img, 0.5) else img
                    val v = aiX4(pred, "")
                    img = if (nas == 2) zmen(v, img.w * 2.0 / v.w) else v
                } else if (nas == K4) {
                    val ciel = 3840
                    var priechod = 1
                    while (max(img.w, img.v) < ciel && priechod <= 2) {
                        val d = max(img.w, img.v)
                        val vstupAi = if (d * 4 > ciel) zmen(img, ciel / 4.0 / d) else img
                        img = aiX4(vstupAi, "")
                        priechod++
                    }
                    val d = max(img.w, img.v)
                    if (d != ciel) img = zmen(img, ciel.toDouble() / d)
                }
                Platforma.naHlavnom { cinnost = T("vyl_upravujem") }
                if (jas) autoJasKontrast(img.px)
                if (far) sytost(img.px, 1.15f)
                if (ostr) doostri(img)
                val ciel = File(ObrazkyJadro.priecinokObrazkov(), "alexia_vylepsene_${System.currentTimeMillis()}.png")
                val b = Bitmap.createBitmap(img.px, img.w, img.v, Bitmap.Config.ARGB_8888)
                ciel.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
                val rr = img.w to img.v
                Platforma.naHlavnom { rozmerVysledku = rr }
                ciel
            }
            Platforma.naHlavnom {
                pracuje = false; cinnost = ""
                r.onSuccess { vysledok = it; ObrazkyJadro.galeria.add(0, it) }
                r.onFailure { chyba = if (it is OutOfMemoryError) T("vyl_pamat") else it.message ?: T("vyl_zlyhalo") }
            }
        }
    }

    fun uvolniPamat() {
        if (pracuje) return
        vlakno.execute { if (h != 0L) { Sd.upscalerUvolni(h); h = 0L } }
    }

    // ------------------------------------------------ klasické úpravy (bez AI)

    private fun autoJasKontrast(px: IntArray) {
        val hist = IntArray(256)
        for (p in px) hist[((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000]++
        val okraj = px.size / 200
        var lo = 0; var s = 0; while (lo < 255 && s + hist[lo] <= okraj) { s += hist[lo]; lo++ }
        var hi = 255; s = 0; while (hi > 0 && s + hist[hi] <= okraj) { s += hist[hi]; hi-- }
        if (hi - lo < 30 || (lo < 3 && hi > 252)) return
        val lut = IntArray(256) { ((it - lo) * 255.0 / (hi - lo)).roundToInt().coerceIn(0, 255) }
        for (k in px.indices) { val p = px[k]; px[k] = (p and -0x1000000) or (lut[p shr 16 and 255] shl 16) or (lut[p shr 8 and 255] shl 8) or lut[p and 255] }
    }

    private fun sytost(px: IntArray, k: Float) {
        for (j in px.indices) {
            val p = px[j]; val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
            val sed = 0.299f * r + 0.587f * g + 0.114f * b
            fun c(x: Int) = (sed + (x - sed) * k).roundToInt().coerceIn(0, 255)
            px[j] = (p and -0x1000000) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
    }

    private fun doostri(i: Obr) {
        val w = i.w; val v = i.v; val px = i.px; val zdroj = px.copyOf()
        for (y in 1 until v - 1) for (x in 1 until w - 1) {
            var o = zdroj[y * w + x] and -0x1000000
            for (sh in intArrayOf(16, 8, 0)) {
                val c = zdroj[y * w + x] shr sh and 255
                var sum = 0
                for (dy in -1..1) for (dx in -1..1) sum += zdroj[(y + dy) * w + x + dx] shr sh and 255
                val ostra = c + ((c - sum / 9) * 0.6f).roundToInt()
                o = o or (min(255, max(0, ostra)) shl sh)
            }
            px[y * w + x] = o
        }
    }
}

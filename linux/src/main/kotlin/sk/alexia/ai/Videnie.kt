package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Videnie: malý model Qwen3-VL 2B, ktorý vidí obrázky (aj bežné fotky z mobilu či internetu).
 *  - sekcia **Prompt**: z obrázka napíše veľmi podrobný anglický prompt pre FLUX / Z-Image / Klein / SD,
 *  - **prenos štýlu**: sám opíše štýl predlohy, aby Klein netipoval.
 * Beží na procesore (grafiku nechá obrázkovému modelu), po každom opise sa uvoľní.
 * Výstup prejde rovnakým filtrom ako všetko ostatné (Podpis.zakazane) – explicitný obsah appka nespracuje.
 */
object Videnie {
    enum class Rezim { PROMPT, STYL }

    var pracuje by mutableStateOf(false); private set
    var cinnost by mutableStateOf(""); private set
    var priebeh by mutableFloatStateOf(0f); private set
    var zaciatok by mutableStateOf(0L); private set
    var posledneTrvanie by mutableStateOf(0L); private set

    private const val REPO = "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/"
    /** (súbor, zoznam odkazov – skúšajú sa po poradí) */
    private val SUBORY = listOf(
        "Qwen3VL-2B-Instruct-Q4_K_M.gguf" to listOf(REPO + "Qwen3VL-2B-Instruct-Q4_K_M.gguf", REPO + "Qwen3VL-2B-Instruct-Q8_0.gguf"),
        "mmproj-Qwen3VL-2B-Instruct-F16.gguf" to listOf(REPO + "mmproj-Qwen3VL-2B-Instruct-F16.gguf", REPO + "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf"),
    )
    private fun priecinok() = File(Platforma.priecinok, "videnie").apply { mkdirs() }
    fun mamModel() = SUBORY.all { File(priecinok(), it.first).let { f -> f.exists() && f.length() > 1_000_000 } }

    private const val SYS_PROMPT =
        "You are a world-class prompt engineer for AI image generators (FLUX, Z-Image, Stable Diffusion). " +
        "Look at the image very carefully and write ONE extremely detailed English prompt that would recreate it as closely as possible. " +
        "Be exhaustive – describe everything you can see, in this order: " +
        "1) type of image and overall style (photo, anime, 3D render, oil painting, etc.), " +
        "2) main subject: age range, body type, skin tone and texture, face shape, eyes (shape, color, makeup), eyebrows, nose, lips, expression, " +
        "hair (color, highlights, length, texture, hairstyle), " +
        "3) clothing and accessories: every garment, its cut, fabric, color, pattern, fit, jewelry, shoes, " +
        "4) pose, body position, hands, gaze direction, " +
        "5) setting and background: location, objects, weather, time of day, depth, " +
        "6) lighting: light sources, direction, color temperature, shadows, highlights, rim light, " +
        "7) camera: shot type, angle, lens, focal length, depth of field, bokeh, composition, " +
        "8) colors and color grading, mood and atmosphere, " +
        "9) quality keywords matching the style. " +
        "Never name or identify real people, celebrities or brands; describe people only by visible features. " +
        "Output only the prompt as one long flowing paragraph of at least 150 words, no headings, no lists, no explanations."
    private const val OT_PROMPT = "Write the extremely detailed prompt for this image."

    private const val SYS_STYL =
        "You describe only the visual STYLE of an image for an AI image editing model, never its content. " +
        "Name exactly: the medium and rendering technique (e.g. photograph, semi-realistic anime illustration, cel-shaded anime, 3D render, " +
        "oil painting, watercolor, pixel art, comic), line work, shading method, level of realism, texture and brushwork only if visible, " +
        "color palette, color grading, contrast, lighting style, atmosphere and mood. " +
        "Do not mention people, objects, clothing or the scene. Output one comma-separated English line of 30 to 70 words, nothing else."
    private const val OT_STYL = "Describe the visual style of this image."

    /** Opíše obrázok (súbor). Prvý raz stiahne model (~1,8 GB). hotovo(text, chyba) na hlavnom vlákne. */
    fun opis(f: File, rezim: Rezim, hotovo: (String?, String) -> Unit) {
        if (pracuje) { hotovo(null, T("vid_zaneprazdnene")); return }
        val data = runCatching { pripravObrazok(f) }.getOrElse { hotovo(null, T("styl_nie_obrazok")); return }
        pracuje = true; priebeh = 0f; cinnost = T("vid_citam")
        val start = System.currentTimeMillis(); zaciatok = start
        Thread {
            var text: String? = null; var chyba = ""
            runCatching {
                stiahniModely()
                Platforma.naHlavnom { cinnost = T(if (rezim == Rezim.STYL) "vid_styl" else "vid_pozeram"); priebeh = -1f }
                val dir = priecinok()
                val vlakna = maxOf(2, Runtime.getRuntime().availableProcessors() - 1)
                val b = Llama.opisObrazka(File(dir, SUBORY[0].first).path, File(dir, SUBORY[1].first).path, data,
                    if (rezim == Rezim.STYL) SYS_STYL else SYS_PROMPT, if (rezim == Rezim.STYL) OT_STYL else OT_PROMPT,
                    if (rezim == Rezim.STYL) 160 else 700, vlakna)
                    ?: throw IllegalStateException(Llama.chyba().ifBlank { T("vid_chyba") })
                val t = String(b, Charsets.UTF_8).trim().trim('"').replace(Regex("\\s+"), " ")
                if (Podpis.zakazane(t)) throw IllegalStateException(T("vid_zakazane"))
                text = t
            }.onFailure { chyba = it.message ?: T("vid_chyba") }
            val trvanie = System.currentTimeMillis() - start
            Platforma.naHlavnom { pracuje = false; priebeh = 0f; cinnost = ""; posledneTrvanie = trvanie; hotovo(text, chyba) }
        }.apply { isDaemon = true }.start()
    }

    /** blokujúca verzia pre vlákna mimo UI (prenos štýlu, server) */
    fun opisCakaj(f: File, rezim: Rezim): Pair<String?, String> {
        val l = java.util.concurrent.CountDownLatch(1)
        var r: Pair<String?, String> = null to ""
        Platforma.naHlavnom { opis(f, rezim) { t, e -> r = t to e; l.countDown() } }
        l.await()
        return r
    }

    /** zmenší na max 1024 px a uloží ako PNG (stb_image nečíta webp ani niektoré JPEG-y) */
    private fun pripravObrazok(f: File): ByteArray {
        val img = ImageIO.read(f) ?: throw IllegalArgumentException()
        val k = minOf(1.0, 1024.0 / maxOf(img.width, img.height))
        val w = maxOf(1, (img.width * k).toInt()); val h = maxOf(1, (img.height * k).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(img, 0, 0, w, h, null); dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
    }

    private fun stiahniModely() {
        val dir = priecinok()
        SUBORY.forEachIndexed { idx, (meno, odkazy) ->
            val f = File(dir, meno)
            if (f.exists() && f.length() > 1_000_000) return@forEachIndexed
            val tmp = File(dir, "$meno.part")
            var posledna: Throwable? = null
            for (u in odkazy) {
                val ok = runCatching {
                    stiahni(u, tmp) { p -> Platforma.naHlavnom { cinnost = T("vid_stahujem", ((idx + p) / SUBORY.size * 100).toInt()); priebeh = (idx + p) / SUBORY.size } }
                }.onFailure { posledna = it }.isSuccess
                if (ok) { tmp.renameTo(f); break }
            }
            if (!f.exists()) throw IllegalStateException(posledna?.message ?: T("vid_chyba"))
        }
    }

    private fun stiahni(odkaz: String, ciel: File, p: (Float) -> Unit) {
        var url = java.net.URL(odkaz); var k: java.net.HttpURLConnection; var skoky = 0
        while (true) {
            k = url.openConnection() as java.net.HttpURLConnection
            k.instanceFollowRedirects = false; k.connectTimeout = 20_000; k.readTimeout = 60_000
            k.setRequestProperty("User-Agent", "AlexiaAI")
            val kod = k.responseCode
            if (kod in 300..399 && skoky++ < 8) { url = java.net.URL(url, k.getHeaderField("Location")); k.disconnect(); continue }
            if (kod != 200) throw IllegalStateException("Model sa nepodarilo stiahnuť (server vrátil $kod)")
            break
        }
        val spolu = k.contentLengthLong; var h = 0L
        k.inputStream.use { i -> ciel.outputStream().use { o ->
            val b = ByteArray(1 shl 16); var n: Int
            while (i.read(b).also { n = it } > 0) { o.write(b, 0, n); h += n; if (spolu > 0) p(h.toFloat() / spolu) }
        } }
        if (spolu > 0 && ciel.length() != spolu) throw IllegalStateException("Model sa nestiahol celý")
    }
}

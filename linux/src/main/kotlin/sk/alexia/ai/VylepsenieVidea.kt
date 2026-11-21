package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vylepšenie vlastného videa (PC / Legion Go S): ostrosť, odšumenie, farby a AI zväčšenie snímka po snímke
 * (Real-ESRGAN – rovnaký model ako pri fotkách). Zvuk ostáva pôvodný. Obsah videa sa nemení.
 */
object VylepsenieVidea {
    class Info(val sirka: Int, val vyska: Int, val fps: String, val fpsCislo: Double, val dlzka: Double, val snimok: Int)

    var vstup by mutableStateOf<File?>(null)
        private set
    var info by mutableStateOf<Info?>(null)
        private set
    var vysledok by mutableStateOf<File?>(null)
        private set
    var pracuje by mutableStateOf(false)
        private set
    var priebeh by mutableStateOf(0f)
        private set
    var cinnost by mutableStateOf("")
        private set
    var chyba by mutableStateOf("")
        private set

    // nastavenia
    var ciel by mutableStateOf(0)            // 0 = bez zväčšenia, 2 = 2× AI, 1080 = Full HD AI, 2160 = 4K AI
    var ostrost by mutableStateOf(true)
    var sum by mutableStateOf(true)
    var farby by mutableStateOf(false)
    var stabilizacia by mutableStateOf(false)
    var plynulost by mutableStateOf(false)   // 60 snímok/s (dopočíta medzisnímky)
    var zvuk by mutableStateOf(false)        // odšumenie a vyrovnanie hlasitosti

    @Volatile private var zrusit = false
    private val procesy = mutableListOf<Process>()

    private fun ffprobe(): String? = VideoJadro.ffmpeg()?.let { f ->
        val p = File(File(f).parentFile, if (Platforma.jeWindows) "ffprobe.exe" else "ffprobe")
        if (p.exists()) p.path else null
    }

    fun nastavVstup(f: File) {
        vstup = f; info = null; vysledok = null; chyba = ""
        if (VideoJadro.ffmpeg() == null) { VideoJadro.stiahniFfmpeg { nastavVstup(f) }; return }
        Thread {
            val r = runCatching {
                val pr = ffprobe() ?: throw IllegalStateException("ffprobe chýba")
                val p = ProcessBuilder(pr, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate,nb_frames:format=duration", "-of", "json", f.path)
                    .redirectErrorStream(true).start()
                val j = JSONObject(p.inputStream.bufferedReader().readText()); p.waitFor()
                val st = j.getJSONArray("streams").getJSONObject(0)
                val fps = st.optString("r_frame_rate", "30/1")
                val fc = fps.split('/').let { (it[0].toDoubleOrNull() ?: 30.0) / (it.getOrNull(1)?.toDoubleOrNull()?.takeIf { d -> d > 0 } ?: 1.0) }
                val dl = j.optJSONObject("format")?.optString("duration")?.toDoubleOrNull() ?: 0.0
                val n = st.optString("nb_frames").toIntOrNull() ?: (dl * fc).toInt()
                Info(st.getInt("width"), st.getInt("height"), fps, fc, dl, n)
            }
            Platforma.naHlavnom { r.onSuccess { info = it }; r.onFailure { chyba = T("vv_neotvori") + " " + (it.message ?: "") } }
        }.start()
    }

    private fun parne(x: Int) = x - x % 2

    /** cieľový rozmer podľa voľby (zachová pomer strán) */
    fun cielovyRozmer(i: Info): Pair<Int, Int> = when (ciel) {
        2 -> parne(i.sirka * 2) to parne(i.vyska * 2)
        1080, 2160 -> { val k = ciel.toDouble() / minOf(i.sirka, i.vyska); parne((i.sirka * k).toInt()) to parne((i.vyska * k).toInt()) }
        else -> i.sirka to i.vyska
    }

    fun zrus() { zrusit = true; synchronized(procesy) { procesy.forEach { it.destroy() } } }

    private fun spusti(vararg a: String): Process = ProcessBuilder(*a).start().also { p ->
        synchronized(procesy) { procesy += p }
        Thread { runCatching { p.errorStream.readBytes() } }.start()
    }

    fun vylepsi() {
        val f = vstup ?: return; val i = info ?: return
        if (pracuje) return
        if (ciel != 0 && !VylepsenieJadro.mamModel()) { chyba = T("vyl_treba_model"); return }
        val ff = VideoJadro.ffmpeg() ?: run { VideoJadro.stiahniFfmpeg(); return }
        pracuje = true; chyba = ""; vysledok = null; priebeh = 0f; zrusit = false
        ChatJadro.uvolniPamat(); ObrazkyJadro.uvolniPamat()
        val filtreAI = mutableListOf<String>(); val filtrePo = mutableListOf<String>()
        if (stabilizacia) filtreAI += "deshake"
        if (sum) filtreAI += "hqdn3d=1.5:1.2:4:4"
        if (ostrost) filtrePo += "unsharp=5:5:0.8:3:3:0.3"
        if (farby) filtrePo += "eq=contrast=1.06:saturation=1.15:brightness=0.01"
        if (plynulost && i.fpsCislo < 50) filtrePo += "minterpolate=fps=60:mi_mode=mci:mc_mode=aobmc:vsbmc=1"
        val audio = if (zvuk) listOf("-af", "afftdn=nf=-25,loudnorm=I=-14:TP=-1.5:LRA=11", "-c:a", "aac", "-b:a", "192k") else listOf("-c:a", "copy")
        val (tw, tv) = cielovyRozmer(i)
        val ciel = this.ciel
        Thread {
            val out = File(f.parentFile.takeIf { it.canWrite() } ?: VideoJadro.priecinok(), f.nameWithoutExtension + "_alexia.mp4")
            val r = runCatching {
                val tmp = File(VideoJadro.priecinok(), "vylepsene_${System.currentTimeMillis()}.mp4")
                if (ciel == 0) bezAI(ff, f, tmp, i, (filtreAI + filtrePo), audio) else sAI(ff, f, tmp, i, tw, tv, filtreAI, filtrePo, audio)
                if (zrusit) { tmp.delete(); throw InterruptedException() }
                out.delete(); if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
                out
            }
            synchronized(procesy) { procesy.clear() }
            Platforma.naHlavnom {
                pracuje = false; cinnost = ""
                r.onSuccess { vysledok = it }
                r.onFailure { if (it !is InterruptedException) chyba = if (it is OutOfMemoryError) T("vyl_pamat") else it.message ?: T("vyl_zlyhalo") }
            }
        }.start()
    }

    /** jeden priechod ffmpeg (bez AI) – rýchle */
    private fun bezAI(ff: String, f: File, out: File, i: Info, filtre: List<String>, audio: List<String>) {
        Platforma.naHlavnom { cinnost = T("vv_upravujem") }
        val p = spusti(*(listOf(ff, "-y", "-v", "error", "-progress", "pipe:1", "-i", f.path, "-vf", filtre.ifEmpty { listOf("null") }.joinToString(","),
            "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p") + audio + out.path).toTypedArray())
        p.inputStream.bufferedReader().forEachLine { l ->
            if (l.startsWith("out_time_us=") && i.dlzka > 0) l.substringAfter('=').toLongOrNull()?.let { us ->
                val x = (us / 1e6 / i.dlzka).toFloat().coerceIn(0f, 1f); Platforma.naHlavnom { priebeh = x }
            }
        }
        if (p.waitFor() != 0 && !zrusit) throw IllegalStateException("ffmpeg zlyhal")
    }

    /** AI: dekóduj snímky → Real-ESRGAN 4× → zakóduj (zvuk z originálu) */
    private fun sAI(ff: String, f: File, out: File, i: Info, tw: Int, tv: Int, filtreAI: List<String>, filtrePo: List<String>, audio: List<String>) {
        // vstup do AI = cieľ / 4 (AI zväčší presne na cieľ)
        val aw = maxOf(2, parne(tw / 4)); val av = maxOf(2, parne(tv / 4))
        val dek = spusti(ff, "-v", "error", "-i", f.path, "-vf", (filtreAI + "scale=$aw:$av:flags=lanczos").joinToString(","),
            "-f", "rawvideo", "-pix_fmt", "bgr0", "-")
        val vf = (filtrePo + "scale=$tw:$tv:flags=lanczos").joinToString(",")
        val kod = spusti(*(listOf(ff, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "bgr0", "-s", "${aw * 4}x${av * 4}", "-r", i.fps, "-i", "-",
            "-i", f.path, "-map", "0:v", "-map", "1:a?", "-vf", vf, "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p")
            + audio + listOf("-shortest", out.path)).toTypedArray())
        val vstupB = ByteArray(aw * av * 4); val px = IntArray(aw * av)
        val roz = IntArray(2)
        val vystupB = ByteBuffer.allocate(aw * 4 * av * 4 * 4).order(ByteOrder.LITTLE_ENDIAN)
        val citac = DataInputStream(dek.inputStream.buffered(1 shl 20))
        val start = System.nanoTime(); var n = 0
        kod.outputStream.buffered(1 shl 20).use { o ->
            while (!zrusit) {
                try { citac.readFully(vstupB) } catch (_: EOFException) { break }
                ByteBuffer.wrap(vstupB).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(px)
                for (k in px.indices) px[k] = px[k] or -0x1000000
                val vys = VylepsenieJadro.zvacsiSnimku(px, aw, av, roz)
                vystupB.clear(); vystupB.asIntBuffer().put(vys, 0, roz[0] * roz[1])
                o.write(vystupB.array(), 0, roz[0] * roz[1] * 4)
                n++
                val zaSnimku = (System.nanoTime() - start) / 1e9 / n
                val zostava = ((i.snimok - n).coerceAtLeast(0) * zaSnimku).toInt()
                val x = if (i.snimok > 0) n.toFloat() / i.snimok else 0f
                val t = T("vv_snimka", n, i.snimok, zostava / 60, zostava % 60)
                Platforma.naHlavnom { priebeh = x.coerceIn(0f, 1f); cinnost = t }
            }
        }
        dek.destroy()
        if (kod.waitFor() != 0 && !zrusit) throw IllegalStateException("ffmpeg zlyhal pri ukladaní videa")
    }

    fun ulozDoVidei(): File? = vysledok?.let { v -> runCatching {
        val d = File(System.getProperty("user.home"), "Videos/Alexia AI").apply { mkdirs() }
        v.copyTo(File(d, v.name), overwrite = true)
    }.getOrNull() }
}

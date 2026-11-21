package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 3D model z obrázka (TripoSR, MIT) – len pre obrázky, ktoré vytvorila a podpísala appka.
 * Pri prvom použití stiahne Python, balíky (PyTorch pre procesor), TripoSR a váhy (~2,5 GB spolu).
 * Výstup: .glb a .obj s farbami vo vrcholoch – otvorí ich Blender, Unreal aj Unity.
 */
object Model3D {
    const val MB = 2600
    private const val TSR_ZIP = "https://github.com/VAST-AI-Research/TripoSR/archive/refs/heads/main.zip"
    private const val VAHY = "https://huggingface.co/stabilityai/TripoSR/resolve/main/"

    var pripravuje by mutableStateOf(false)
        private set
    var pracuje by mutableStateOf(false)
        private set
    var priebeh by mutableStateOf(0f)
        private set
    var cinnost by mutableStateOf("")
        private set
    var chyba by mutableStateOf("")
        private set
    var vysledok by mutableStateOf<File?>(null)
        private set
    /** obrázok, z ktorého sa práve robí / urobil model */
    var zdroj by mutableStateOf<File?>(null)
        private set

    private fun dir() = File(Platforma.priecinok, "3d").apply { mkdirs() }
    private fun python(): File = if (Platforma.jeWindows) File(dir(), "python/python.exe") else File(dir(), "python/bin/python3")
    private fun hotovoZnacka() = File(dir(), "pripravene.txt")
    fun jePripravene() = hotovoZnacka().exists() && python().exists() && File(dir(), "vahy/model.ckpt").length() > 1_000_000_000
    fun priecinokModelov() = File(Platforma.obrazkyPouzivatela.parentFile, "Documents/Alexia AI/3D").apply { mkdirs() }

    private fun stav(t: String, p: Float) = Platforma.naHlavnom { cinnost = t; priebeh = p }

    fun priprav(potom: () -> Unit = {}) {
        if (pripravuje) return
        pripravuje = true; chyba = ""
        Thread {
            val r = runCatching {
                val d = dir()
                // 1) Python
                if (!python().exists()) {
                    stav(T("m3d_python"), 0.01f)
                    val api = URL("https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest").openConnection() as HttpURLConnection
                    api.setRequestProperty("User-Agent", "AlexiaAI"); api.connectTimeout = 20_000
                    val a = JSONObject(api.inputStream.bufferedReader().readText()).getJSONArray("assets")
                    val vzor = if (Platforma.jeWindows) Regex("cpython-3\\.11\\.\\d+\\+\\d+-x86_64-pc-windows-msvc-install_only\\.tar\\.gz")
                               else Regex("cpython-3\\.11\\.\\d+\\+\\d+-x86_64-unknown-linux-gnu-install_only\\.tar\\.gz")
                    val url = (0 until a.length()).map { a.getJSONObject(it) }.first { vzor.matches(it.getString("name")) }.getString("browser_download_url")
                    val arch = File(d, "python.tar.gz")
                    stiahni(url, arch) { stav(T("m3d_python"), 0.01f + it * 0.05f) }
                    val p = ProcessBuilder("tar", "-xzf", arch.path, "-C", d.path).redirectErrorStream(true).start()
                    p.inputStream.readBytes(); p.waitFor(); arch.delete()
                    if (!python().exists()) throw IllegalStateException("Python sa nepodarilo rozbaliť")
                }
                // 2) balíky
                if (!File(d, "balicky.txt").exists()) {
                    stav(T("m3d_balicky"), 0.08f)
                    pip("install", "--no-warn-script-location", "-q", "torch==2.5.1", "--index-url", "https://download.pytorch.org/whl/cpu")
                    stav(T("m3d_balicky"), 0.25f)
                    pip("install", "--no-warn-script-location", "-q", "numpy<2", "omegaconf==2.3.0", "einops==0.7.0", "transformers==4.44.2",
                        "trimesh==4.4.9", "PyMCubes==0.1.6", "Pillow", "huggingface_hub", "imageio")
                    File(d, "balicky.txt").writeText("ok")
                }
                // 3) TripoSR
                val tsr = File(d, "TripoSR")
                if (!File(tsr, "tsr/system.py").exists()) {
                    stav(T("m3d_tsr"), 0.3f)
                    val z = File(d, "tsr.zip"); stiahni(TSR_ZIP, z) {}
                    tsr.deleteRecursively(); rozbal(z, d); z.delete()
                    File(d, "TripoSR-main").renameTo(tsr)
                }
                skopiruj("priprav.py"); skopiruj("alexia3d.py")
                spusti(listOf(python().path, File(d, "priprav.py").path, tsr.path)).waitFor()
                // 4) váhy (1,7 GB)
                val v = File(d, "vahy").apply { mkdirs() }
                if (!File(v, "config.yaml").exists()) stiahni(VAHY + "config.yaml", File(v, "config.yaml")) {}
                if (File(v, "model.ckpt").length() < 1_000_000_000) stiahni(VAHY + "model.ckpt", File(v, "model.ckpt")) { stav(T("m3d_vahy", (it * 100).toInt()), 0.35f + it * 0.65f) }
                hotovoZnacka().writeText("ok")
            }
            Platforma.naHlavnom {
                pripravuje = false; cinnost = ""; priebeh = 0f
                r.onFailure { chyba = T("m3d_chyba_priprava", it.message ?: "") }
                r.onSuccess { potom() }
            }
        }.start()
    }

    private fun skopiruj(meno: String) {
        Model3D::class.java.getResourceAsStream("/model3d/$meno")!!.use { i -> File(dir(), meno).outputStream().use { i.copyTo(it) } }
    }

    private fun spusti(prikaz: List<String>): Process = ProcessBuilder(prikaz).directory(dir()).redirectErrorStream(true).also { pb ->
        pb.environment()["HF_HOME"] = File(dir(), "hf").path
        pb.environment()["PYTHONUTF8"] = "1"
        pb.environment().remove("PYTHONPATH"); pb.environment().remove("PYTHONHOME")
    }.start()

    private fun pip(vararg a: String) {
        val p = spusti(listOf(python().path, "-m", "pip") + a)
        val log = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw IllegalStateException("pip: " + log.lines().lastOrNull { it.isNotBlank() }.orEmpty())
    }

    /** vytvorí 3D model z obrázka (len podpísaný obrázok z appky) */
    fun vytvor(png: File) {
        if (pracuje || pripravuje) return
        if (ObrazkyJadro.seedObrazka(png) == null) { chyba = T("m3d_len_appka"); return }
        if (!jePripravene()) { priprav { vytvor(png) }; return }
        pracuje = true; chyba = ""; vysledok = null; zdroj = png; priebeh = 0f; cinnost = T("m3d_tvorim")
        ChatJadro.uvolniPamat(); ObrazkyJadro.uvolniPamat(); VylepsenieJadro.uvolniPamat()
        Thread {
            val r = runCatching {
                val von = File(priecinokModelov(), png.nameWithoutExtension + "_3d")
                val pb = ProcessBuilder(python().path, File(dir(), "alexia3d.py").path, png.path, von.path,
                    ChatJadro.nastavenia.vlakna.toString(), "256").directory(dir()).redirectErrorStream(true)
                pb.environment()["HF_HOME"] = File(dir(), "hf").path
                pb.environment()["ALEXIA_TSR_MODEL"] = File(dir(), "vahy").path
                pb.environment()["PYTHONUTF8"] = "1"
                pb.environment().remove("PYTHONPATH"); pb.environment().remove("PYTHONHOME")
                val p = pb.start()
                val posledne = ArrayDeque<String>()
                p.inputStream.bufferedReader().forEachLine { l ->
                    posledne += l; if (posledne.size > 8) posledne.removeFirst()
                    if (l.startsWith("PRIEBEH ")) l.split(' ').getOrNull(1)?.toFloatOrNull()?.let { x -> Platforma.naHlavnom { priebeh = x } }
                }
                if (p.waitFor() != 0 || !File(von.path + ".glb").exists())
                    throw IllegalStateException(posledne.lastOrNull { it.isNotBlank() && !it.startsWith("PRIEBEH") } ?: "TripoSR zlyhal")
                File(von.path + ".glb")
            }
            Platforma.naHlavnom {
                pracuje = false; cinnost = ""
                r.onSuccess { vysledok = it }
                r.onFailure { chyba = T("m3d_chyba", it.message ?: "") }
            }
        }.start()
    }

    fun otvor(f: File) { runCatching { java.awt.Desktop.getDesktop().open(f) }.onFailure { Platforma.otvorOdkaz(f.toURI().toString()) } }

    // ------------------------------------------------ sťahovanie

    private fun stiahni(odkaz: String, ciel: File, p: (Float) -> Unit) {
        var url = URL(odkaz); var k: HttpURLConnection; var skoky = 0
        while (true) {
            k = url.openConnection() as HttpURLConnection
            k.instanceFollowRedirects = false; k.connectTimeout = 20_000; k.readTimeout = 60_000
            k.setRequestProperty("User-Agent", "AlexiaAI")
            val kod = k.responseCode
            if (kod in 300..399 && skoky++ < 8) { url = URL(url, k.getHeaderField("Location")); k.disconnect(); continue }
            if (kod != 200) throw IllegalStateException("Server vrátil $kod ($odkaz)")
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

    private fun rozbal(zip: File, kam: File) {
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(kam, e.name)
                if (!f.canonicalPath.startsWith(kam.canonicalPath)) continue
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
    }
}

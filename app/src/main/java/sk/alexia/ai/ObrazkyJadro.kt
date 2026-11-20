package sk.alexia.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sk.alexia.ai.obrazky.Sd
import java.io.File
import java.util.concurrent.Executors

/** Tvorba obrázkov z textu (stable-diffusion.cpp), offline. Obrázky sú len v priečinku appky, kým ich neuložíš do galérie. */
object ObrazkyJadro {
    val odporucane = listOf(
        OdporucanyModel("Stable Diffusion 1.5 (Q8_0)", "lepšia kvalita",
            "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf", 1760),
        OdporucanyModel("Stable Diffusion 1.5 (Q4_0)", "menší, trochu horšia kvalita",
            "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf", 1570),
    )

    private val vlakno = Executors.newSingleThreadExecutor { Thread(it, "sd").apply { priority = Thread.MAX_PRIORITY } }
    private val hlavne = Handler(Looper.getMainLooper())
    private var h = 0L
    private lateinit var app: Context

    var stav by mutableStateOf(StavModelu.ZIADNY)
        private set
    var chyba by mutableStateOf("")
        private set
    var generuje by mutableStateOf(false)
        private set
    var priebeh by mutableFloatStateOf(0f)
        private set
    var posledny by mutableStateOf<File?>(null)
        private set
    val galeria = mutableStateListOf<File>()
    private var nacitany: String? = null

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        galeria.addAll(priecinokObrazkov().listFiles { f -> f.name.endsWith(".png") }?.sortedByDescending { it.lastModified() } ?: emptyList())
    }

    fun priecinokModelov() = File(app.filesDir, "obrazkove-modely").apply { mkdirs() }
    fun priecinokObrazkov() = File(app.filesDir, "obrazky").apply { mkdirs() }
    fun modely(): List<File> = priecinokModelov().listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) || f.name.endsWith(".safetensors", true) }
        ?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private val prefs get() = app.getSharedPreferences("obrazky", Context.MODE_PRIVATE)
    var aktivnyModel: String?
        get() = prefs.getString("model", null)
        set(v) { prefs.edit().putString("model", v).apply() }

    fun jeAktivny(f: File) = aktivnyModel == f.path

    /** načíta model; jazykový model sa najprv uvoľní, aby sa zmestili do pamäte */
    fun nacitaj(cesta: String, uvolnitChat: Boolean = true) {
        if (nacitany == cesta && stav == StavModelu.PRIPRAVENY) return
        aktivnyModel = cesta
        if (uvolnitChat) ChatJadro.uvolniPamat()
        stav = StavModelu.NACITAVA; chyba = ""
        val vl = ChatJadro.nastavenia.vlakna
        vlakno.execute {
            if (h != 0L) { Sd.uvolni(h); h = 0L }
            val novy = Sd.nacitaj(cesta, vl)
            val e = if (novy == 0L) Sd.chyba() else ""
            h = novy
            hlavne.post {
                if (novy != 0L) { stav = StavModelu.PRIPRAVENY; nacitany = cesta }
                else { stav = StavModelu.CHYBA; nacitany = null; chyba = e }
            }
        }
    }

    fun uvolniPamat() {
        if (generuje) return
        vlakno.execute { if (h != 0L) { Sd.uvolni(h); h = 0L } }
        nacitany = null
        if (stav != StavModelu.CHYBA) stav = StavModelu.ZIADNY
    }

    fun generuj(prompt: String, negativny: String, rozmer: Int, kroky: Int, cfg: Float) {
        if (generuje || stav != StavModelu.PRIPRAVENY || prompt.isBlank()) return
        spust(prompt, negativny, rozmer, kroky, cfg) { f, e -> f?.let { posledny = it }; if (f == null) chyba = e }
    }

    /**
     * Obrázok pre chat. Ak sa jazykový aj obrázkový model zmestia do RAM (do ~45 %), ostanú oba;
     * inak sa chat na chvíľu uvoľní a po obrázku sa znova načíta.
     */
    fun nakresliDoChatu(popis: String, hotovo: (File?, String) -> Unit) {
        val cesta = aktivnyModel
        if (cesta == null || !File(cesta).exists()) { hotovo(null, T("chat_obr_ziadny")); return }
        if (generuje) return
        val uvolnit = ChatJadro.velkostNacitaneho() + File(cesta).length() > Platforma.ramBajtov() * 45 / 100
        if (uvolnit) ChatJadro.uvolniPamat()
        nacitaj(cesta, uvolnitChat = false)
        spust(popis, "blurry, low quality, deformed", 384, 20, 7f) { f, e ->
            hotovo(f, e)
            if (uvolnit) { uvolniPamat(); ChatJadro.obnov() }
        }
    }

    /** obrázok nakreslí iné zariadenie (prepojenie v sieti) */
    fun nakresliNaDialku(adresa: String, pin: String, popis: String, hotovo: (File?, String) -> Unit) {
        if (generuje) return
        generuje = true; priebeh = 0f
        Thread {
            val f = File(priecinokObrazkov(), "alexia_${System.currentTimeMillis()}.png")
            val r = runCatching { Klient.obrazok(adresa, pin, popis, f) }
            hlavne.post {
                generuje = false
                r.onSuccess { galeria.add(0, f); hotovo(f, "") }
                r.onFailure { f.delete(); hotovo(null, it.message ?: "Spojenie zlyhalo") }
            }
        }.start()
    }

    private fun spust(prompt: String, negativny: String, rozmer: Int, kroky: Int, cfg: Float, hotovo: (File?, String) -> Unit) {
        generuje = true; priebeh = 0f; chyba = ""
        val seed = (Math.random() * Int.MAX_VALUE).toLong()
        val sledovac = object : Runnable {
            override fun run() { if (generuje) { priebeh = Sd.priebeh(); hlavne.postDelayed(this, 300) } }
        }
        hlavne.postDelayed(sledovac, 300)
        vlakno.execute {
            var subor: File? = null; var e = ""
            if (h == 0L) e = Sd.chyba().ifBlank { "Obrázkový model sa nenačítal" }
            else {
                val r = IntArray(2)
                val px = Sd.generuj(h, prompt.trim(), negativny.trim(), rozmer, rozmer, kroky, cfg, seed, r)
                if (px != null) {
                    runCatching {
                        val bmp = Bitmap.createBitmap(px, r[0], r[1], Bitmap.Config.ARGB_8888)
                        subor = File(priecinokObrazkov(), "alexia_${System.currentTimeMillis()}.png")
                        subor!!.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }.onFailure { e = it.message ?: "Obrázok sa nepodarilo uložiť" }
                } else e = Sd.chyba()
            }
            hlavne.post {
                generuje = false; priebeh = 0f
                subor?.let { galeria.add(0, it) }
                hotovo(subor, e)
            }
        }
    }

    /** obrázok ako pozadie chatu – skopíruje sa, takže zmazanie z galérie pozadie nepokazí */
    fun nastavPozadieChatu(f: File) {
        val n = ChatJadro.nastavenia
        val ok = runCatching { f.copyTo(File(app.filesDir, "pozadie-chatu.png"), overwrite = true) }.getOrNull()
        if (ok != null) { n.pozadieChatu = ok.path; n.pozadieVerzia++; n.uloz(); Platforma.oznam(T("poz_chat_nastavene")) }
    }

    fun zrusPozadieChatu() {
        val n = ChatJadro.nastavenia
        n.pozadieChatu?.let { File(it).delete() }
        n.pozadieChatu = null; n.pozadieVerzia++; n.uloz()
    }

    fun zrus() { if (h != 0L) Sd.zrus(h) }

    fun zmazObrazok(f: File) { galeria.remove(f); if (posledny == f) posledny = null; f.delete() }

    fun zmazModel(f: File) {
        if (jeAktivny(f)) { uvolniPamat(); aktivnyModel = null; stav = StavModelu.ZIADNY }
        vlakno.execute { f.delete() }
    }

    /** uloží do galérie (Obrázky/Alexia AI) – Android 10+, bez akéhokoľvek oprávnenia */
    fun ulozDoGalerie(f: File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Alexia AI")
            }
            val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
            app.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            true
        }.getOrDefault(false)
    }
}

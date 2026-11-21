package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sk.alexia.ai.obrazky.Sd
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import java.util.concurrent.Executors

/** Tvorba obrázkov z textu (stable-diffusion.cpp), offline. Obrázky sú len v priečinku appky, kým ich neuložíš do galérie. */
object ObrazkyJadro {

    private val vlakno = Executors.newSingleThreadExecutor { Thread(it, "sd").apply { priority = Thread.MAX_PRIORITY } }
    private var h = 0L
    private var spustene = false

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

    fun init() {
        if (spustene) return
        spustene = true
        galeria.addAll(priecinokObrazkov().listFiles { f -> f.name.endsWith(".png") }?.sortedByDescending { it.lastModified() } ?: emptyList())
    }

    fun priecinokModelov() = File(Platforma.priecinok, "obrazkove-modely").apply { mkdirs() }
    private fun priecinokObrazkov() = File(Platforma.priecinok, "obrazky").apply { mkdirs() }
    fun modely(): List<File> = priecinokModelov().listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) || f.name.endsWith(".safetensors", true) }
        ?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()

    var aktivnyModel: String?
        get() = ChatJadro.nastavenia.aktivnyObrazkovyModel
        set(v) { ChatJadro.nastavenia.aktivnyObrazkovyModel = v; ChatJadro.nastavenia.uloz() }

    fun jeAktivny(f: File) = aktivnyModel == f.path

    // ---- modely z viacerých súborov (katalóg)
    fun priecinokSady(s: ObrazkovaSada) = File(priecinokModelov(), s.id)
    fun suborSady(s: ObrazkovaSada, url: String) = File(priecinokSady(s), url.substringAfterLast('/'))
    fun mamSadu(s: ObrazkovaSada) = s.subory.values.all { suborSady(s, it).let { f -> f.exists() && f.length() > 1024 } }
    fun sady(): List<ObrazkovaSada> = Katalog.obrazky.filter { mamSadu(it) }
    fun aktivnaSada(): ObrazkovaSada? = aktivnyModel?.takeIf { it.startsWith("sada:") }?.let { id -> Katalog.obrazky.find { "sada:" + it.id == id } }
    fun jeAktivna(s: ObrazkovaSada) = aktivnyModel == "sada:" + s.id

    /** stiahne chýbajúce súbory sady jeden po druhom; priebeh(0..1, "2/3 ae.safetensors") */
    fun stiahniSadu(s: ObrazkovaSada, priebeh: (Float, String) -> Unit, zrusene: () -> Boolean) {
        val chyba = s.subory.values.filter { u -> !suborSady(s, u).exists() }
        priecinokSady(s).mkdirs()
        chyba.forEachIndexed { i, u ->
            val popis = "${i + 1}/${chyba.size} ${u.substringAfterLast('/')}"
            Modely.stiahni(u, { p -> priebeh(p, popis) }, zrusene, priecinokSady(s), true)
        }
    }

    fun zmazSadu(s: ObrazkovaSada) {
        if (jeAktivna(s)) { uvolniPamat(); aktivnyModel = null; stav = StavModelu.ZIADNY }
        vlakno.execute { priecinokSady(s).deleteRecursively() }
    }

    /** načíta model; jazykový model sa najprv uvoľní, aby sa zmestili do pamäte */
    fun nacitaj(cesta: String, uvolnitChat: Boolean = true) {
        if (nacitany == cesta && (stav == StavModelu.PRIPRAVENY || stav == StavModelu.NACITAVA)) return
        aktivnyModel = cesta
        if (uvolnitChat) ChatJadro.uvolniPamat()
        stav = StavModelu.NACITAVA; chyba = ""
        val vl = ChatJadro.nastavenia.vlakna
        vlakno.execute {
            if (h != 0L) { Sd.uvolni(h); h = 0L }
            val sada = Katalog.obrazky.find { "sada:" + it.id == cesta }
            val novy = if (sada != null) {
                val k = sada.subory.keys.toTypedArray()
                Sd.nacitajViac(k, k.map { suborSady(sada, sada.subory.getValue(it)).absolutePath }.toTypedArray(), vl, sada.fa)
            } else Sd.nacitaj(cesta, vl)
            val e = if (novy == 0L) Sd.chyba() else ""
            h = novy
            Platforma.naHlavnom {
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
        spust(prompt, negativny, rozmer, rozmer, kroky, cfg) { f, e -> f?.let { posledny = it }; if (f == null) chyba = e }
    }

    /** veľkosť obrázkového modelu na disku */
    private fun velkost(cesta: String): Long =
        Katalog.obrazky.find { "sada:" + it.id == cesta }?.let { s -> s.subory.values.sumOf { suborSady(s, it).length() } } ?: File(cesta).length()

    /**
     * Obrázok pre chat. Ak sa jazykový aj obrázkový model zmestia spolu do pamäte, ostanú načítané oba (rýchle).
     * Inak sa chat na chvíľu uvoľní a po obrázku sa znova načíta.
     */
    fun nakresliDoChatu(popis: String, hotovo: (File?, String) -> Unit) {
        val cesta = aktivnyModel
        val mam = cesta != null && (aktivnaSada()?.let { mamSadu(it) } ?: File(cesta).exists())
        if (!mam) { hotovo(null, T("chat_obr_ziadny")); return }
        if (generuje) return
        val spolu = ChatJadro.velkostNacitaneho() + velkost(cesta!!)
        val uvolnit = spolu > LIMIT_SPOLU
        if (uvolnit) ChatJadro.uvolniPamat()
        nacitaj(cesta, uvolnitChat = false)
        val s = aktivnaSada()
        spust(popis, "", s?.rozmer ?: 512, s?.rozmer ?: 512, s?.kroky ?: 20, s?.cfg ?: 7f) { f, e ->
            hotovo(f, e)
            if (uvolnit) { uvolniPamat(); ChatJadro.obnov() }
        }
    }

    /**
     * Viac obrázkov po sebe (na video) na výšku 9:16. [priebeh] dostane (hotových, spolu).
     * Hotové obrázky idú do [kam], nie do galérie.
     */
    fun nakresliViac(popisy: List<String>, kam: File, priebehObr: (Int, Int) -> Unit, hotovo: (List<File>, String) -> Unit) {
        val cesta = aktivnyModel
        val mam = cesta != null && (aktivnaSada()?.let { mamSadu(it) } ?: File(cesta).exists())
        if (!mam) { hotovo(emptyList(), T("chat_obr_ziadny")); return }
        if (generuje) { hotovo(emptyList(), T("video_zaneprazdnene")); return }
        val uvolnit = ChatJadro.velkostNacitaneho() + velkost(cesta!!) > LIMIT_SPOLU
        if (uvolnit) ChatJadro.uvolniPamat()
        nacitaj(cesta, uvolnitChat = false)
        val s = aktivnaSada()
        // Z-Image / FLUX zvládnu 576×1024, SD 1.5 radšej menšie
        val (w, v) = if (s != null && s.rozmer >= 768) 576 to 1024 else 448 to 768
        val hotove = mutableListOf<File>()
        kam.mkdirs()
        fun dalsi(i: Int) {
            if (i >= popisy.size) { hotovo(hotove, ""); if (uvolnit) { uvolniPamat(); ChatJadro.obnov() }; return }
            priebehObr(i, popisy.size)
            spust(popisy[i], "", w, v, s?.kroky ?: 20, s?.cfg ?: 7f, doGalerie = false) { f, e ->
                if (f == null) { hotovo(hotove, e); if (uvolnit) { uvolniPamat(); ChatJadro.obnov() }; return@spust }
                val ciel = File(kam, "obr_%02d.png".format(i + 1)); f.copyTo(ciel, overwrite = true); f.delete()
                hotove += ciel
                dalsi(i + 1)
            }
        }
        dalsi(0)
    }

    /** obrázok nakreslí iné zariadenie (prepojenie v sieti) */
    fun nakresliNaDialku(adresa: String, pin: String, popis: String, hotovo: (File?, String) -> Unit) {
        if (generuje) return
        generuje = true; priebeh = 0f
        Thread {
            val f = File(priecinokObrazkov(), "alexia_${System.currentTimeMillis()}.png")
            val r = runCatching { Klient.obrazok(adresa, pin, popis, f) }
            Platforma.naHlavnom {
                generuje = false
                r.onSuccess { galeria.add(0, f); hotovo(f, "") }
                r.onFailure { f.delete(); hotovo(null, it.message ?: "Spojenie zlyhalo") }
            }
        }.start()
    }

    /** spustí tvorbu na vlákne sd (po prípadnom načítaní modelu, ktoré je v rade pred ňou) */
    private fun spust(prompt: String, negativny: String, sirka: Int, vyska: Int, kroky: Int, cfg: Float, doGalerie: Boolean = true, hotovo: (File?, String) -> Unit) {
        generuje = true; priebeh = 0f; chyba = ""
        val seed = (Math.random() * Int.MAX_VALUE).toLong()
        // priebeh čítame z iného vlákna, kým generovanie beží
        Thread {
            while (true) {
                Thread.sleep(300)
                val p = Sd.priebeh()
                var dalej = true
                javax.swing.SwingUtilities.invokeAndWait { if (generuje) priebeh = p else dalej = false }
                if (!dalej) break
            }
        }.apply { isDaemon = true }.start()
        vlakno.execute {
            var subor: File? = null; var e = ""
            if (h == 0L) e = Sd.chyba().ifBlank { "Obrázkový model sa nenačítal" }
            else {
                val r = IntArray(2)
                val metoda = aktivnaSada()?.metoda ?: "euler_a"
                val px = Sd.generujEx(h, prompt.trim(), negativny.trim(), sirka, vyska, kroky, cfg, seed, metoda, r)
                if (px != null) {
                    runCatching {
                        val img = BufferedImage(r[0], r[1], BufferedImage.TYPE_INT_RGB)
                        img.setRGB(0, 0, r[0], r[1], px, 0, r[0])
                        subor = File(priecinokObrazkov(), "alexia_${System.currentTimeMillis()}.png")
                        ImageIO.write(img, "png", subor!!)
                    }.onFailure { e = it.message ?: "Obrázok sa nepodarilo uložiť" }
                } else e = Sd.chyba()
            }
            Platforma.naHlavnom {
                generuje = false; priebeh = 0f
                if (doGalerie) subor?.let { galeria.add(0, it) }
                hotovo(subor, e)
            }
        }
    }

    /** chat + obrázky spolu do ~10 GB, aby na 16 GB ostalo miesto pre systém */
    private const val LIMIT_SPOLU = 10_000L * 1024 * 1024

    fun zrus() { if (h != 0L) Sd.zrus(h) }

    fun zmazObrazok(f: File) { galeria.remove(f); if (posledny == f) posledny = null; f.delete() }

    fun zmazModel(f: File) {
        if (jeAktivny(f)) { uvolniPamat(); aktivnyModel = null; stav = StavModelu.ZIADNY }
        vlakno.execute { f.delete() }
    }

    /** obrázok ako pozadie chatu – skopíruje sa, takže zmazanie z galérie pozadie nepokazí */
    fun nastavPozadieChatu(f: File) {
        val n = ChatJadro.nastavenia
        val ok = runCatching { f.copyTo(File(Platforma.priecinok, "pozadie-chatu.png"), overwrite = true) }.getOrNull()
        if (ok != null) { n.pozadieChatu = ok.path; n.pozadieVerzia++; n.uloz(); Platforma.oznam(T("poz_chat_nastavene")) }
    }

    fun zrusPozadieChatu() {
        val n = ChatJadro.nastavenia
        n.pozadieChatu?.let { File(it).delete() }
        n.pozadieChatu = null; n.pozadieVerzia++; n.uloz()
    }

    /** uloží kópiu do ~/Pictures/Alexia AI */
    fun ulozDoGalerie(f: File): Boolean = runCatching {
        val dir = File(Platforma.obrazkyPouzivatela, "Alexia AI").apply { mkdirs() }
        f.copyTo(File(dir, f.name), overwrite = true); true
    }.getOrDefault(false)
}

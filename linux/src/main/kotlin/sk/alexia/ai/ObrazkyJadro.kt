package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sk.alexia.ai.obrazky.Sd
import java.awt.image.BufferedImage
import java.io.File
import org.json.JSONObject
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

    // ------------------------------------------------ výmena textového kodéra (napr. Qwen3 4B pri Z-Image)

    /** ktorý súbor sady je textový kodér (číta popis obrázka) */
    fun koderKluc(s: ObrazkovaSada): String? = listOf("llm_path", "t5xxl_path").firstOrNull { it in s.subory }

    /** voľby kodéra: (názov, URL alebo "" = pôvodný). Z-Image potrebuje model s architektúrou Qwen3 4B. */
    fun volbyKodera(s: ObrazkovaSada): List<Pair<String, String>> = when (koderKluc(s)) {
        "llm_path" -> listOf(
            "Q3_K_M · 2,1 GB · rýchlejší" to "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q3_K_M.gguf",
            "Q4_K_M · 2,5 GB · pôvodný" to "",
            "Q6_K · 3,3 GB · presnejší" to "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q6_K.gguf",
            "Q8_0 · 4,3 GB · najpresnejší" to "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q8_0.gguf",
        )
        else -> listOf("pôvodný" to "")
    }

    /** zvolený kodér: null = pôvodný zo sady, inak cesta k súboru */
    fun koderSubor(s: ObrazkovaSada, kluc: String): String? {
        if (kluc != koderKluc(s)) return null
        val v = ChatJadro.nastavenia.koder(s.id) ?: return null
        return when {
            v.startsWith("http") -> suborSady(s, v).takeIf { it.exists() }?.absolutePath
            File(v).exists() -> v
            else -> null
        }
    }

    var koderPriebeh by mutableStateOf<Float?>(null)
        private set

    /** nastaví kodér (URL z volieb – stiahne ho; cesta k vlastnému .gguf; "" = pôvodný) a sadu znova načíta */
    fun nastavKoder(s: ObrazkovaSada, volba: String) {
        if (koderPriebeh != null) return
        val n = ChatJadro.nastavenia
        fun hotovo() { n.nastavKoder(s.id, volba.ifEmpty { null }); if (jeAktivna(s)) { nacitany = null; nacitaj("sada:" + s.id) } }
        if (!volba.startsWith("http") || suborSady(s, volba).exists()) { hotovo(); return }
        koderPriebeh = 0f
        Thread {
            val r = runCatching { Modely.stiahni(volba, { p -> Platforma.naHlavnom { koderPriebeh = p } }, { false }, priecinokSady(s), true) }
            Platforma.naHlavnom { koderPriebeh = null; r.onSuccess { hotovo() }; r.onFailure { chyba = it.message ?: "Sťahovanie zlyhalo" } }
        }.start()
    }

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
                Sd.nacitajViac(k, k.map { kk -> koderSubor(sada, kk) ?: suborSady(sada, sada.subory.getValue(kk)).absolutePath }.toTypedArray(), vl, sada.fa)
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

    fun generuj(prompt: String, negativny: String, rozmer: Int, kroky: Int, cfg: Float) = generujHerne(prompt, negativny, rozmer, kroky, cfg, "obrazok", 1)

    /** koľko obrázkov zo sady je hotových (pri sade variantov) */
    var sadaHotovo by mutableStateOf(0 to 0)
        private set

    /**
     * Tvorba aj pre hry: typ "obrazok", "textura" (nekonečná, nadväzuje zo všetkých strán) alebo "sprite" (priehľadné pozadie);
     * pocet > 1 = sada variantov so seedmi idúcimi po sebe (napr. 12345, 12346 …).
     */
    fun generujHerne(prompt: String, negativny: String, rozmer: Int, kroky: Int, cfg: Float, typ: String, pocet: Int) {
        if (generuje || stav != StavModelu.PRIPRAVENY || prompt.isBlank()) return
        val p = when (typ) {
            "textura" -> "${prompt.trim()}, seamless tileable texture, flat even lighting, no shadows, top-down view, high detail"
            "sprite" -> "${prompt.trim()}, single object, centered, full view, isolated on a plain solid bright green background, no shadow, game asset"
            else -> prompt
        }
        val neg = if (typ == "sprite") listOf(negativny, "green object, background scenery, shadow").filter { it.isNotBlank() }.joinToString(", ") else negativny
        val zaklad = (10000L..99990L).random()
        vlakno.execute { Sd.nastavTexturu(typ == "textura") }
        sadaHotovo = 0 to pocet
        fun dalsi(i: Int) {
            if (i >= pocet) { vlakno.execute { Sd.nastavTexturu(false) }; return }
            spust(p, neg, rozmer, rozmer, kroky, cfg, pevnySeed = zaklad + i, sprite = typ == "sprite") { f, e ->
                f?.let { posledny = it }
                if (f == null) { chyba = e; vlakno.execute { Sd.nastavTexturu(false) }; sadaHotovo = 0 to 0; return@spust }
                sadaHotovo = (i + 1) to pocet
                dalsi(i + 1)
            }
        }
        dalsi(0)
    }

    /** sprite: odstráni jednofarebné pozadie (vyplnenie od okrajov), okraje zjemní */
    private fun odstranPozadie(img: BufferedImage): BufferedImage {
        val w = img.width; val v = img.height
        val px = img.getRGB(0, 0, w, v, null, 0, w)
        // farba pozadia = priemer rohov
        val rohy = listOf(px[0], px[w - 1], px[(v - 1) * w], px[v * w - 1])
        val br = rohy.sumOf { it shr 16 and 255 } / 4; val bg = rohy.sumOf { it shr 8 and 255 } / 4; val bb = rohy.sumOf { it and 255 } / 4
        fun vzdialenost(c: Int): Int { val r = (c shr 16 and 255) - br; val g = (c shr 8 and 255) - bg; val b = (c and 255) - bb; return r * r + g * g + b * b }
        val prah = 70 * 70
        val pozadie = BooleanArray(w * v)
        val fronta = ArrayDeque<Int>()
        for (x in 0 until w) { fronta += x; fronta += (v - 1) * w + x }
        for (y in 0 until v) { fronta += y * w; fronta += y * w + w - 1 }
        while (fronta.isNotEmpty()) {
            val i = fronta.removeFirst()
            if (pozadie[i] || vzdialenost(px[i]) > prah) continue
            pozadie[i] = true
            val x = i % w; val y = i / w
            if (x > 0) fronta += i - 1; if (x < w - 1) fronta += i + 1
            if (y > 0) fronta += i - w; if (y < v - 1) fronta += i + w
        }
        val out = BufferedImage(w, v, BufferedImage.TYPE_INT_ARGB)
        val o = IntArray(w * v)
        for (i in 0 until w * v) {
            if (pozadie[i]) { o[i] = 0; continue }
            // okraj: pixel vedľa pozadia → polopriehľadný a bez zeleného nádychu
            val x = i % w; val y = i / w
            val okraj = (x > 0 && pozadie[i - 1]) || (x < w - 1 && pozadie[i + 1]) || (y > 0 && pozadie[i - w]) || (y < v - 1 && pozadie[i + w])
            var c = px[i] and 0xFFFFFF
            if (okraj) {
                val r = c shr 16 and 255; var g = c shr 8 and 255; val b = c and 255
                g = minOf(g, maxOf(r, b))   // odstráni zelený lem
                c = (r shl 16) or (g shl 8) or b
            }
            o[i] = ((if (okraj) 0xB0 else 0xFF) shl 24) or c
        }
        out.setRGB(0, 0, w, v, o, 0, w)
        return out
    }

    /** export pre hru: presne N×N (mocnina dvoch); malé obrázky najprv AI zväčší; priehľadnosť ostane */
    fun exportujPreHru(f: File, n: Int, hotovo: (File?) -> Unit) {
        Thread {
            val r = runCatching {
                var img = ImageIO.read(f)
                val alfa = img.colorModel.hasAlpha()
                if (maxOf(img.width, img.height) * 1.5 < n && VylepsenieJadro.mamModel()) {
                    val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
                    val roz = IntArray(2)
                    val v = VylepsenieJadro.zvacsiSnimku(px.map { it or -0x1000000 }.toIntArray(), img.width, img.height, roz)
                    val vacsi = BufferedImage(roz[0], roz[1], if (alfa) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
                    if (alfa) {
                        // priehľadnosť zväčšíme zvlášť (AI pracuje len s farbami)
                        val maska = BufferedImage(roz[0], roz[1], BufferedImage.TYPE_INT_ARGB)
                        maska.createGraphics().apply { setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(img, 0, 0, roz[0], roz[1], null); dispose() }
                        val m = maska.getRGB(0, 0, roz[0], roz[1], null, 0, roz[0])
                        for (i in v.indices) v[i] = (m[i] and -0x1000000) or (v[i] and 0xFFFFFF)
                    }
                    vacsi.setRGB(0, 0, roz[0], roz[1], v, 0, roz[0]); img = vacsi
                }
                val out = BufferedImage(n, n, if (alfa) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
                val g = out.createGraphics()
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                // sprite sa zmestí celý (priehľadný okraj), obrázok/textúra vyplní celý štvorec
                val k = if (alfa) n.toDouble() / maxOf(img.width, img.height) else n.toDouble() / minOf(img.width, img.height)
                val w = (img.width * k).toInt(); val v = (img.height * k).toInt()
                g.drawImage(img, (n - w) / 2, (n - v) / 2, w, v, null); g.dispose()
                val d = File(Platforma.obrazkyPouzivatela, "Alexia AI/hra").apply { mkdirs() }
                File(d, f.nameWithoutExtension + "_${n}.png").also { ImageIO.write(out, "png", it) }
            }
            Platforma.naHlavnom { hotovo(r.getOrNull()) }
        }.start()
    }

    /** veľkosť obrázkového modelu na disku */
    private fun velkost(cesta: String): Long =
        Katalog.obrazky.find { "sada:" + it.id == cesta }?.let { s -> s.subory.values.sumOf { suborSady(s, it).length() } } ?: File(cesta).length()

    /**
     * Obrázok pre chat. Ak sa jazykový aj obrázkový model zmestia spolu do pamäte, ostanú načítané oba (rýchle).
     * Inak sa chat na chvíľu uvoľní a po obrázku sa znova načíta.
     */
    fun nakresliDoChatu(popis: String, seed: Long? = null, hotovo: (File?, String) -> Unit) {
        if (jeVideoModel) { hotovo(null, T("vai_je_video")); return }
        val cesta = aktivnyModel
        val mam = cesta != null && (aktivnaSada()?.let { mamSadu(it) } ?: File(cesta).exists())
        if (!mam) { hotovo(null, T("chat_obr_ziadny")); return }
        if (generuje) return
        val spolu = ChatJadro.velkostNacitaneho() + velkost(cesta!!)
        val uvolnit = spolu > LIMIT_SPOLU
        if (uvolnit) ChatJadro.uvolniPamat()
        nacitaj(cesta, uvolnitChat = false)
        val s = aktivnaSada()
        spust(popis, "", s?.rozmer ?: 512, s?.rozmer ?: 512, s?.kroky ?: 20, s?.cfg ?: 7f, pevnySeed = seed) { f, e ->
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
    private fun spust(prompt: String, negativny: String, sirka: Int, vyska: Int, kroky: Int, cfg: Float, doGalerie: Boolean = true,
                      pevnySeed: Long? = null, predloha: IntArray? = null, sila: Float = 0.6f, sprite: Boolean = false, hotovo: (File?, String) -> Unit) {
        generuje = true; priebeh = 0f; chyba = ""
        // krátky seed (5 číslic), aby sa dal ľahko napísať: „uprav seed 12345 …“
        val seed = pevnySeed ?: (10000L..99999L).random()
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
                val px = if (predloha != null) Sd.generujZPredlohy(h, prompt.trim(), negativny.trim(), sirka, vyska, kroky, cfg, seed, metoda, predloha, sila, r)
                         else Sd.generujEx(h, prompt.trim(), negativny.trim(), sirka, vyska, kroky, cfg, seed, metoda, r)
                if (px != null) {
                    runCatching {
                        var img = BufferedImage(r[0], r[1], BufferedImage.TYPE_INT_RGB)
                        img.setRGB(0, 0, r[0], r[1], px, 0, r[0])
                        if (sprite) img = odstranPozadie(img)
                        subor = File(priecinokObrazkov(), "alexia_${System.currentTimeMillis()}.png")
                        ImageIO.write(img, "png", subor!!)
                        // údaje o obrázku: seed a popis – podľa nich sa dá obrázok neskôr upraviť
                        val zaznam = JSONObject().put("seed", seed).put("popis", prompt.trim()).put("neg", negativny.trim())
                            .put("w", r[0]).put("h", r[1]).put("kroky", kroky).put("cfg", cfg.toDouble()).put("model", aktivnyModel ?: "")
                        Podpis.podpis(subor!!, zaznam)
                        File(subor!!.path + ".json").writeText(zaznam.toString())
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

    /** seed obrázka vytvoreného v appke (null = nie je z appky, napr. tvoja fotka) */
    /** seed len pre obrázok, ktorý appka vytvorila a podpísala (fotka zvonka ani podvrhnutý záznam neprejde) */
    fun seedObrazka(f: File): Long? = runCatching {
        val j = JSONObject(File(f.path + ".json").readText())
        if (Podpis.over(f, j)) j.getLong("seed") else null
    }.getOrNull()

    /** najnovší obrázok z appky s týmto seedom */
    fun najdiPodlaSeedu(seed: Long): File? = priecinokObrazkov().listFiles { f -> f.extension == "png" }
        ?.filter { seedObrazka(it) == seed }?.maxByOrNull { it.lastModified() }

    /**
     * Úprava obrázka, ktorý vytvorila appka: rovnaký seed, pôvodný popis + zmena, predloha = pôvodný obrázok.
     * Fotky zvonka (bez seedu) sa takto upravovať nedajú.
     */
    fun upravDoChatu(seed: Long, zmena: String, sila: Float, hotovo: (File?, String) -> Unit) {
        if (Podpis.zakazane(zmena)) { hotovo(null, T("upr_zakazane")); return }
        val f = najdiPodlaSeedu(seed) ?: run { hotovo(null, T("upr_nenajdene", seed)); return }
        val j = runCatching { JSONObject(File(f.path + ".json").readText()) }.getOrNull() ?: run { hotovo(null, T("upr_nenajdene", seed)); return }
        val cesta = aktivnyModel
        val mam = cesta != null && (aktivnaSada()?.let { mamSadu(it) } ?: File(cesta).exists())
        if (!mam) { hotovo(null, T("chat_obr_ziadny")); return }
        if (generuje) return
        val img = runCatching { ImageIO.read(f) }.getOrNull() ?: run { hotovo(null, T("upr_nenajdene", seed)); return }
        val w = img.width - img.width % 8; val v = img.height - img.height % 8
        val px = img.getRGB(0, 0, w, v, null, 0, w)
        val uvolnit = ChatJadro.velkostNacitaneho() + velkost(cesta!!) > LIMIT_SPOLU
        if (uvolnit) ChatJadro.uvolniPamat()
        nacitaj(cesta, uvolnitChat = false)
        val s = aktivnaSada()
        val popis = j.optString("popis").trimEnd('.', ',', ' ') + ", " + zmena.trim()
        spust(popis, j.optString("neg"), w, v, s?.kroky ?: j.optInt("kroky", 20), s?.cfg ?: j.optDouble("cfg", 7.0).toFloat(),
            pevnySeed = seed, predloha = px, sila = sila) { nf, e ->
            hotovo(nf, e)
            if (uvolnit) { uvolniPamat(); ChatJadro.obnov() }
        }
    }

    // ------------------------------------------------ video AI (Wan 2.1)

    fun videoSada(): ObrazkovaSada? = Katalog.obrazky.firstOrNull { it.video != null && mamSadu(it) }
    val jeVideoModel get() = aktivnaSada()?.video != null

    /**
     * Krátke video z textu. Ak je aktívny obrázkový model, na čas videa sa načíta Wan a potom sa vráti voľba používateľa.
     * [snimok] napr. 17 / 33 / 49 (pri 16 fps ≈ 1 / 2 / 3 s). Výsledok: MP4 cez ffmpeg.
     */
    fun natocAI(popis: String, snimok: Int?, hotovo: (File?, String) -> Unit) {
        val sada = aktivnaSada()?.takeIf { it.video != null } ?: videoSada() ?: run { hotovo(null, T("vai_ziadny")); return }
        if (generuje) { hotovo(null, T("video_zaneprazdnene")); return }
        val ff = VideoJadro.ffmpeg() ?: run { VideoJadro.stiahniFfmpeg { natocAI(popis, snimok, hotovo) }; return }
        val v = sada.video!!
        val predtym = aktivnyModel
        ChatJadro.uvolniPamat()
        nacitaj("sada:" + sada.id, uvolnitChat = false)
        generuje = true; priebeh = 0f; chyba = ""
        Thread {
            while (true) {
                Thread.sleep(500)
                val p = Sd.priebeh(); var dalej = true
                javax.swing.SwingUtilities.invokeAndWait { if (generuje) priebeh = p else dalej = false }
                if (!dalej) break
            }
        }.apply { isDaemon = true }.start()
        val seed = (10000L..99999L).random()
        vlakno.execute {
            var subor: File? = null; var e = ""
            if (h == 0L) e = Sd.chyba().ifBlank { "Video model sa nenačítal" }
            else runCatching {
                val r = IntArray(4)
                val neg = "blurry, static, still frame, low quality, deformed, watermark, text, extra fingers, bad hands"
                val data = Sd.generujVideo(h, popis.trim(), neg, v.sirka, v.vyska, snimok ?: v.snimok, sada.kroky, sada.cfg, v.posun, seed, sada.metoda, r)
                    ?: throw IllegalStateException(Sd.chyba())
                val (w, vy, t, fps) = r.toList()
                val out = File(VideoJadro.priecinok(), "ai_video_${System.currentTimeMillis()}.mp4")
                // náhľad = prvá snímka
                val obr = BufferedImage(w, vy, BufferedImage.TYPE_INT_RGB)
                val px = IntArray(w * vy) { i -> ((data[i * 3].toInt() and 255) shl 16) or ((data[i * 3 + 1].toInt() and 255) shl 8) or (data[i * 3 + 2].toInt() and 255) }
                obr.setRGB(0, 0, w, vy, px, 0, w); ImageIO.write(obr, "png", File(out.path.removeSuffix(".mp4") + ".png"))
                // MP4: dvojnásobná plynulosť (dopočítané snímky) a 2× väčšie
                val p = ProcessBuilder(ff, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${w}x$vy", "-r", "$fps", "-i", "-",
                    "-vf", "minterpolate=fps=${fps * 2}:mi_mode=mci:mc_mode=aobmc,scale=${w * 2}:${vy * 2}:flags=lanczos",
                    "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out.path)
                    .redirectErrorStream(true).start()
                Thread { runCatching { p.inputStream.readBytes() } }.start()
                p.outputStream.use { it.write(data, 0, w * vy * 3 * t) }
                if (p.waitFor() != 0) throw IllegalStateException("ffmpeg zlyhal")
                subor = out
            }.onFailure { e = it.message ?: "Video sa nepodarilo" }
            Platforma.naHlavnom {
                generuje = false; priebeh = 0f
                // vráti voľbu obrázkového modelu (načíta sa, keď ho bude treba)
                if (predtym != null && predtym != "sada:" + sada.id) { uvolniPamat(); aktivnyModel = predtym }
                hotovo(subor, e)
            }
        }
    }

    /** chat + obrázky spolu do ~10 GB, aby na 16 GB ostalo miesto pre systém */
    private const val LIMIT_SPOLU = 10_000L * 1024 * 1024

    fun zrus() { if (h != 0L) Sd.zrus(h) }

    fun zmazObrazok(f: File) { galeria.remove(f); if (posledny == f) posledny = null; f.delete(); File(f.path + ".json").delete() }

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

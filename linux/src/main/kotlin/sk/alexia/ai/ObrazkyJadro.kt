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
    private fun priecinokObrazkov() = Platforma.obrazky
    fun modely(): List<File> = priecinokModelov().listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) || f.name.endsWith(".safetensors", true) }
        ?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()

    var aktivnyModel: String?
        get() = ChatJadro.nastavenia.aktivnyObrazkovyModel
        set(v) { ChatJadro.nastavenia.aktivnyObrazkovyModel = v; ChatJadro.nastavenia.uloz() }

    fun jeAktivny(f: File) = aktivnyModel == f.path

    /** uvoľní model a zabudne voľbu (napr. prepnutie medzi záložkami Obrázky a Videá) */
    fun zabudniModel() { if (generuje) return; uvolniPamat(); aktivnyModel = null; stav = StavModelu.ZIADNY }

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
    fun volbyKodera(s: ObrazkovaSada): List<Pair<String, String>> = if (s.id == "flux3-klein") listOf(
            "Q3_K_M · 2,1 GB · rýchlejší" to "https://huggingface.co/unsloth/Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen3-VL-4B-Instruct-Q3_K_M.gguf",
            "Q4_K_M · 2,5 GB · pôvodný" to "",
            "Q6_K · 3,3 GB · presnejší" to "https://huggingface.co/unsloth/Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen3-VL-4B-Instruct-Q6_K.gguf",
            "Q8_0 · 4,3 GB · najpresnejší" to "https://huggingface.co/unsloth/Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen3-VL-4B-Instruct-Q8_0.gguf",
        ) else when (koderKluc(s)) {
        "llm_path" -> listOf(
            "Q3_K_M · 2,1 GB · rýchlejší" to "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q3_K_M.gguf",
            "Q4_K_M · 2,5 GB · pôvodný" to "",
            "Q6_K · 3,3 GB · presnejší" to "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q6_K.gguf",
            "Q8_0 · 4,3 GB · najpresnejší" to "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q8_0.gguf",
        )
        else -> listOf("pôvodný" to "")
    }.let { l -> if (koderKluc(s) == "t5xxl_path" && s.video != null) listOf(
            "Q3_K_M · rýchlejší" to "https://huggingface.co/city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q3_K_M.gguf",
            "Q4_K_M · pôvodný" to "",
            "Q6_K · presnejší" to "https://huggingface.co/city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q6_K.gguf",
            "Q8_0 · najpresnejší" to "https://huggingface.co/city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q8_0.gguf",
        ) else l }

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

    /** aká architektúra kodéra sa hodí k sade (null = nevieme overiť) */
    private fun potrebnyKoder(s: ObrazkovaSada): Pair<String, Long?>? = when {
        s.id == "flux3-klein" -> "qwen3vl" to null          // FLUX.3 Klein: Qwen3-VL 4B
        koderKluc(s) == "llm_path" -> "qwen3" to 2560L      // Z-Image: Qwen3 4B
        else -> null
    }

    /** chyba, ak súbor nie je kompatibilný kodér pre sadu (napr. Mistral 7B namiesto Qwen3 4B) */
    fun overKoder(s: ObrazkovaSada, f: File): String? {
        val pot = potrebnyKoder(s) ?: return null
        if (!f.name.endsWith(".gguf", true)) return null
        val i = Gguf.info(f) ?: return T("koder_necitatelny")
        val arch = i.architektura.lowercase().replace("_", "")
        if (arch != pot.first || (pot.second != null && i.embedding != pot.second))
            return T("koder_nekompatibilny", "${i.architektura}${i.embedding?.let { " · $it" } ?: ""}", "${pot.first} · ${pot.second}")
        return null
    }

    /** nastaví kodér (URL – stiahne ho; cesta k vlastnému súboru; "" = pôvodný) a sadu znova načíta */
    fun nastavKoder(s: ObrazkovaSada, volbaPouzivatela: String) {
        if (koderPriebeh != null) return
        val n = ChatJadro.nastavenia
        chyba = ""
        fun hotovo(v: String) {
            val subor = if (v.startsWith("http")) suborSady(s, v) else File(v)
            if (v.isNotEmpty()) overKoder(s, subor)?.let { e -> chyba = e; if (v.startsWith("http")) subor.delete(); Platforma.oznam(e); return }
            n.nastavKoder(s.id, v.ifEmpty { null }); if (jeAktivna(s)) { nacitany = null; nacitaj("sada:" + s.id) }
        }
        koderPriebeh = 0f
        Thread {
            val r = runCatching {
                // odkaz z Hugging Face (aj na celý repozitár) → priamy súbor
                val v = if (volbaPouzivatela.contains("huggingface.co") || volbaPouzivatela.contains("hf.co")) Modely.upravOdkaz(volbaPouzivatela) else volbaPouzivatela
                if (v.startsWith("http") && !suborSady(s, v).exists())
                    Modely.stiahni(v, { p -> Platforma.naHlavnom { koderPriebeh = p } }, { false }, priecinokSady(s), true)
                v
            }
            Platforma.naHlavnom { koderPriebeh = null; r.onSuccess { hotovo(it) }; r.onFailure { chyba = it.message ?: "Sťahovanie zlyhalo"; Platforma.oznam(chyba) } }
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
        val sadaV = Katalog.obrazky.find { "sada:" + it.id == cesta }
        val setrit = sadaV?.video != null && ChatJadro.nastavenia.setritVram
        val tae = sadaV?.let { suborSkladania(it) }?.takeIf { it.exists() && it.length() > 1_000_000 }
        val kluc = "$setrit|${tae?.name}"
        if (nacitany == cesta && nacitaneSetrenie == kluc && (stav == StavModelu.PRIPRAVENY || stav == StavModelu.NACITAVA)) return
        aktivnyModel = cesta
        if (uvolnitChat) ChatJadro.uvolniPamat()
        stav = StavModelu.NACITAVA; chyba = ""
        val vl = ChatJadro.nastavenia.vlakna
        vlakno.execute {
            if (h != 0L) { Sd.uvolni(h); h = 0L }
            val sada = Katalog.obrazky.find { "sada:" + it.id == cesta }
            val novy = if (sada != null) {
                val k = sada.subory.keys.toList()
                val hod = k.map { kk -> koderSubor(sada, kk) ?: suborSady(sada, sada.subory.getValue(kk)).absolutePath }
                // Šetriť VRAM: textový kodér počíta na CPU (v RAM, raz na začiatku); VAE ostane na grafike a skladá po kúskoch
                var kk = if (setrit) k + "backend" else k
                var hh = if (setrit) hod + "te=cpu" else hod
                // rýchle skladanie videa: malý autoenkóder (TAE / LightTAE) namiesto plného VAE
                if (tae != null) { kk = kk + "taesd_path"; hh = hh + tae.absolutePath }
                Sd.nacitajViac(kk.toTypedArray(), hh.toTypedArray(), vl, sada.fa)
            } else Sd.nacitaj(cesta, vl)
            val e = if (novy == 0L) Sd.chyba() else ""
            h = novy
            Platforma.naHlavnom {
                if (novy != 0L) { stav = StavModelu.PRIPRAVENY; nacitany = cesta; nacitaneSetrenie = kluc }
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
    /** šírka × výška pre tvar obrázka – približne rovnaký počet pixelov ako štvorec (rovnaká rýchlosť), násobky 64 */
    fun rozmerPomeru(rozmer: Int, pomer: String): Pair<Int, Int> {
        fun r64(x: Double) = (Math.round(x / 64.0) * 64).toInt().coerceAtLeast(64)
        return when (pomer) {
            "vyska" -> r64(rozmer * 0.75) to r64(rozmer / 0.75)
            "sirka" -> r64(rozmer / 0.75) to r64(rozmer * 0.75)
            "vlastne" -> ChatJadro.nastavenia.let { n -> r64(n.obrSirka.coerceIn(256, 1536).toDouble()) to r64(n.obrVyska.coerceIn(256, 1536).toDouble()) }
            // náhodne: každý obrázok dostane iný tvar
            "nahodne" -> rozmerPomeru(rozmer, listOf("stvorec", "vyska", "sirka").random())
            else -> rozmer to rozmer
        }
    }

    fun generujHerne(prompt: String, negativny: String, rozmer: Int, kroky: Int, cfg: Float, typ: String, pocet: Int, pomer: String = "stvorec") {
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
            // textúry a sprity ostávajú štvorcové
            val (w, h) = if (typ == "obrazok") rozmerPomeru(rozmer, pomer) else rozmer to rozmer
            spust(p, neg, w, h, kroky, cfg, pevnySeed = zaklad + i, sprite = typ == "sprite") { f, e ->
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
    fun nakresliDoChatu(popis: String, seed: Long? = null, negativny: String = "", pomer: String = "stvorec", hotovo: (File?, String) -> Unit) {
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
        val (w, vy) = rozmerPomeru(s?.rozmer ?: 512, pomer)
        spust(popis, negativny, w, vy, s?.kroky ?: 20, s?.cfg ?: 7f, pevnySeed = seed) { f, e ->
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
                      pevnySeed: Long? = null, predloha: IntArray? = null, sila: Float = 0.6f, sprite: Boolean = false,
                      referencie: List<BufferedImage>? = null, hotovo: (File?, String) -> Unit) {
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
                // LoRA adaptéry pre obrázky (kategória „obrazky“)
                Lory.pole("obrazky").let { (c, s) -> runCatching { Sd.nastavLory(c, s) } }
                val px = when {
                    referencie != null -> Sd.generujSReferenciami(h, prompt.trim(), negativny.trim(), sirka, vyska, kroky, cfg, seed, metoda,
                        referencie.map { it.getRGB(0, 0, it.width, it.height, null, 0, it.width) }.toTypedArray(),
                        referencie.map { it.width }.toIntArray(), referencie.map { it.height }.toIntArray(), r)
                    predloha != null -> Sd.generujZPredlohy(h, prompt.trim(), negativny.trim(), sirka, vyska, kroky, cfg, seed, metoda, predloha, sila, r)
                    else -> Sd.generujEx(h, prompt.trim(), negativny.trim(), sirka, vyska, kroky, cfg, seed, metoda, r)
                }
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

    /**
     * Obrázok z appky aj vtedy, keď si vybral jeho kópiu (napr. z Obrázkov po „Uložiť do Obrázkov“):
     * kópia nemá záznam, ale má rovnaký obsah – nájde sa originál v galérii s platným podpisom.
     */
    fun originalZAppky(f: File): File? {
        if (seedObrazka(f) != null) return f
        val h = runCatching { Podpis.hash(f) }.getOrNull() ?: return null
        return priecinokObrazkov().listFiles { x -> x.extension == "png" }?.firstOrNull { o ->
            runCatching { JSONObject(File(o.path + ".json").readText()).optString("hash") == h }.getOrDefault(false) && seedObrazka(o) != null
        }
    }

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
        // pri úprave viac krokov (časť z nich sa preskočí), aby zmena bola kvalitná
        val kroky = maxOf((s?.kroky ?: j.optInt("kroky", 20)) * 3 / 2, 12)
        spust(popis, j.optString("neg"), w, v, kroky, s?.cfg ?: j.optDouble("cfg", 7.0).toFloat(),
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
    /** popis, s ktorým bol obrázok z appky vytvorený (na rozhýbanie) */
    fun popisObrazka(f: File): String? = runCatching { org.json.JSONObject(File(f.path + ".json").readText()).optString("popis") }.getOrNull()

    /** obrázok z galérie podľa mena súboru (len z priečinka appky – pre mobil) */
    fun obrazokZGalerie(meno: String): File? {
        val d = priecinokObrazkov()
        val f = File(d, File(meno).name)
        return f.takeIf { it.isFile && it.extension == "png" && it.parentFile.canonicalPath == d.canonicalPath }
    }

    /** posledné obrázky z galérie (najnovšie prvé) */
    fun posledneObrazky(max: Int): List<File> = priecinokObrazkov().listFiles { f -> f.extension == "png" }
        ?.sortedByDescending { it.lastModified() }?.take(max) ?: emptyList()

    fun i2vSada(): ObrazkovaSada? = Katalog.obrazky.firstOrNull { it.video?.i2v == true && mamSadu(it) }

    private var nacitaneSetrenie = ""

    /** skladanie videa: "vae" (plné, najkvalitnejšie), "tae" (rýchle), "lighttae" (rýchle, kvalita blízka VAE) – lightx2v/Autoencoders */
    private fun suborySkladania(s: ObrazkovaSada): Map<String, String> = when (s.id) {
        "wan22-5b" -> mapOf("tae" to "taew2_2.safetensors", "lighttae" to "lighttaew2_2.safetensors")
        "wan21-1.3b" -> mapOf("tae" to "taew2_1.safetensors", "lighttae" to "lighttaew2_1.safetensors")
        else -> emptyMap()
    }
    fun maSkladanie(s: ObrazkovaSada) = suborySkladania(s).isNotEmpty()
    fun suborSkladania(s: ObrazkovaSada): File? = suborySkladania(s)[ChatJadro.nastavenia.skladanie]?.let { File(priecinokSady(s), it) }
    var skladaniePriebeh by mutableStateOf<Float?>(null)
        private set

    /** zvolí spôsob skladania; malý súbor (~25–45 MB) sa stiahne raz, model sa potom načíta znova */
    fun nastavSkladanie(typ: String) {
        val n = ChatJadro.nastavenia
        n.skladanie = typ; n.uloz()
        val s = aktivnaSada()?.takeIf { maSkladanie(it) } ?: Katalog.obrazky.firstOrNull { it.video != null && mamSadu(it) && maSkladanie(it) }
        val f = s?.let { suborSkladania(it) }
        fun znova() { if (s != null && jeAktivna(s)) { nacitany = null; nacitaj("sada:" + s.id, uvolnitChat = false) } }
        if (f == null || (f.exists() && f.length() > 1_000_000)) { znova(); return }
        if (skladaniePriebeh != null) return
        skladaniePriebeh = 0f
        Thread {
            val r = runCatching { Hlas.stiahniSubor("https://huggingface.co/lightx2v/Autoencoders/resolve/main/${f.name}", f) { p -> Platforma.naHlavnom { skladaniePriebeh = p } } }
            Platforma.naHlavnom {
                skladaniePriebeh = null
                r.onSuccess { znova() }.onFailure { Platforma.oznam("⚠ " + (it.message ?: "")) }
            }
        }.start()
    }

    /** sekundy → počet snímok (Wan chce 4·k+1), najviac ~5 s podľa modelu */
    fun snimokPre(v: VideoNastavenie, sekundy: Int): Int {
        val max = if (v.fps >= 24) 121 else 81
        return ((sekundy * v.fps) / 4 * 4 + 1).coerceIn(9, max)
    }

    /** [sekundy] dĺžka videa; null = podľa Nastavení */
    /** plynulosť výsledku: Wan 2.2 (24 fps) → 60 fps, Wan 2.1 (16 fps) → 48 fps (dopočítané snímky medzi skutočnými) */
    private fun cielFps(fps: Int) = if (fps >= 24) 60 else fps * 3

    // ---------------------------------------------------------------- prenos štýlu (FLUX.3 Klein, referenčné obrázky)

    /** Klein vie pracovať s referenčnými obrázkami */
    fun kleinSada(): ObrazkovaSada? = Katalog.obrazky.find { it.id == "flux3-klein" }?.takeIf { mamSadu(it) }

    /** zmenší obrázok tak, aby dlhšia strana mala najviac [max] px (VLM a VAE netreba obrovské vstupy) */
    private fun naReferenciu(f: File, max: Int): BufferedImage {
        val i = ImageIO.read(f) ?: throw IllegalStateException(T("styl_nie_obrazok"))
        val k = minOf(1.0, max.toDouble() / maxOf(i.width, i.height))
        val w = maxOf(16, (i.width * k).toInt()); val v = maxOf(16, (i.height * k).toInt())
        val o = BufferedImage(w, v, BufferedImage.TYPE_INT_RGB)
        o.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(i, 0, 0, w, v, null); dispose()
        }
        return o
    }

    /**
     * Prenos štýlu: [obsah] (obrázok vytvorený v appke) prekreslí v štýle obrázka [styl] (môže byť hocijaký).
     * Fotky zvonka sa ako obsah nedajú – rovnaké pravidlo ako pri úpravách.
     */
    fun prenosStylu(obsah: File, styl: File, doplnok: String, hotovo: (File?, String) -> Unit) {
        if (generuje) { hotovo(null, T("video_zaneprazdnene")); return }
        val obsah = originalZAppky(obsah) ?: run { hotovo(null, T("styl_len_appka")); return }
        if (Podpis.zakazane(doplnok)) { hotovo(null, T("upr_zakazane")); return }
        val k = kleinSada() ?: run { hotovo(null, T("styl_treba_klein")); return }
        val refs = runCatching { listOf(naReferenciu(obsah, 1024), naReferenciu(styl, 1024)) }.getOrElse { hotovo(null, it.message ?: T("styl_nie_obrazok")); return }
        val predtym = aktivnyModel
        ChatJadro.uvolniPamat()
        nacitaj("sada:" + k.id, uvolnitChat = false)
        // výsledok má pomer strán obsahu, plocha ako predvolený rozmer Kleinu (rovnaká rýchlosť)
        val a = refs[0].width.toDouble() / refs[0].height
        val plocha = k.rozmer.toDouble() * k.rozmer
        fun r64(x: Double) = (Math.round(x / 64.0) * 64).toInt().coerceIn(256, 1536)
        val w = r64(Math.sqrt(plocha * a)); val v = r64(Math.sqrt(plocha / a))
        val prompt = "Redraw image 1 in the artistic style of image 2. Keep the exact composition, subject, pose, face, clothing and layout of image 1. " +
            "Take only the style from image 2: colors, color grading, lighting, texture, brushwork and mood. Do not copy objects or people from image 2." +
            doplnok.trim().let { if (it.isBlank()) "" else " $it" }
        spust(prompt, "", w, v, k.kroky, k.cfg, referencie = refs) { f, e ->
            if (predtym != null && predtym != aktivnyModel) { uvolniPamat(); aktivnyModel = predtym }
            hotovo(f, e)
        }
    }

    /** obrázkový model na prvý snímok videa: najpresnejší stiahnutý */
    fun obrazkovaPreVideo(): ObrazkovaSada? = listOf("flux3-klein", "zimage", "flux-schnell", "sd15")
        .mapNotNull { id -> Katalog.obrazky.find { it.id == id } }.firstOrNull { mamSadu(it) }

    /**
     * Video „najprv obrázok“: obrázkový model (FLUX…) nakreslí presný prvý snímok podľa popisu,
     * Wan 2.2 ho potom rozhýbe. Presnejší vzhľad, tvár a farby ako video priamo z textu.
     */
    /** [pohyb] = samostatný popis pohybu pre Wan (null = rovnaký popis ako obrázok) */
    fun natocCezObrazok(popis: String, sekundy: Int?, pohyb: String? = null, hotovo: (File?, String) -> Unit) {
        val v2 = i2vSada() ?: run { hotovo(null, T("vai_ziadny_i2v")); return }
        val img = obrazkovaPreVideo() ?: run { hotovo(null, T("vid_bez_obr_modelu")); return }
        if (generuje) { hotovo(null, T("video_zaneprazdnene")); return }
        val predtym = aktivnyModel
        ChatJadro.uvolniPamat()
        nacitaj("sada:" + img.id, uvolnitChat = false)
        kusText = T("vid_faza_obrazok", img.nazov)
        val v = v2.video!!
        // rovnaký pomer strán ako video (násobky 64), aby sa pri rozhýbaní nič neorezalo
        val (w, h) = if (v.vyska >= v.sirka) 512 to 896 else 896 to 512
        spust(popis.trim(), "blurry, low quality, deformed, bad anatomy, extra fingers", w, h, img.kroky, img.cfg) { f, e ->
            kusText = ""
            if (f == null) { aktivnyModel = predtym; hotovo(null, e.ifBlank { T("vid_bez_obr_modelu") }); return@spust }
            val popisVidea = if (pohyb == null) popis else listOf(popis.trim(), pohyb.trim().ifBlank { "natural subtle motion, cinematic" }).joinToString(", ")
            natocAI(popisVidea, sekundy, f) { video, e2 ->
                if (predtym != null && predtym != aktivnyModel) { uvolniPamat(); aktivnyModel = predtym }
                hotovo(video, e2)
            }
        }
    }

    /** dĺžky, ktoré sa skladajú z viacerých kúskov (nadväzovanie, len modely s obrázkom → video) */
    val DLHE = listOf(10, 20, 30, 60, 120, 180)
    private const val KUS_SEKUND = 2
    @Volatile private var kusovSpolu = 1
    @Volatile private var kusHotovych = 0
    var kusText by mutableStateOf("")
    /** po krokoch sa skladajú snímky (VAE/TAE hlási vlastný priebeh od nuly) */
    var skladam by mutableStateOf(false)
        private set

    fun natocAI(popis: String, sekundy: Int?, predloha: File? = null, hotovo: (File?, String) -> Unit) {
        // rozhýbanie obrázka: len obrázky vytvorené a podpísané appkou
        if (predloha != null && seedObrazka(predloha) == null) { hotovo(null, T("m3d_len_appka")); return }
        val dlzka = sekundy ?: ChatJadro.nastavenia.dlzkaVidea
        val nadvazovat = dlzka > 5
        val sada = (if (predloha != null || nadvazovat) (aktivnaSada()?.takeIf { it.video?.i2v == true } ?: i2vSada())
                    else (aktivnaSada()?.takeIf { it.video != null } ?: videoSada()))
            ?: run { hotovo(null, T(if (predloha != null || nadvazovat) "vai_ziadny_i2v" else "vai_ziadny")); return }
        if (generuje) { hotovo(null, T("video_zaneprazdnene")); return }
        val ff = VideoJadro.ffmpeg() ?: run { VideoJadro.stiahniFfmpeg { natocAI(popis, sekundy, predloha, hotovo) }; return }
        val v = sada.video!!
        val predtym = aktivnyModel
        ChatJadro.uvolniPamat()
        nacitaj("sada:" + sada.id, uvolnitChat = false)
        // dlhé video = kúsky po KUS_SEKUND, každý ďalší začína posledným snímkom predchádzajúceho
        val kusov = if (nadvazovat) (dlzka + KUS_SEKUND - 1) / KUS_SEKUND else 1
        val snimokKusa = snimokPre(v, if (nadvazovat) KUS_SEKUND else dlzka)
        kusovSpolu = kusov; kusHotovych = 0; kusText = if (kusov > 1) T("vai_kus", 1, kusov) else ""
        generuje = true; priebeh = 0f; chyba = ""
        skladam = false; zrusVideo = false
        Thread {
            var kusPredtym = -1; var videlKonec = false; var sklada = false
            while (true) {
                Thread.sleep(500)
                val sp = Sd.priebeh(); val kus = kusHotovych
                if (kus != kusPredtym) { kusPredtym = kus; videlKonec = false; sklada = false }
                if (sp >= 0.99f) videlKonec = true
                else if (videlKonec && sp < 0.9f) sklada = true     // kroky dobehli, teraz VAE/TAE hlási vlastný priebeh
                val p = if (sklada) sp else (kus + sp) / kusovSpolu
                val sk = sklada || sp >= 0.99f
                var dalej = true
                javax.swing.SwingUtilities.invokeAndWait { if (generuje) { priebeh = p; skladam = sk } else dalej = false }
                if (!dalej) break
            }
        }.apply { isDaemon = true }.start()
        val seed = (10000L..99999L).random()
        vlakno.execute {
            var subor: File? = null; var e = ""
            if (h == 0L) e = Sd.chyba().ifBlank { "Video model sa nenačítal" }
            else {
                val out = File(VideoJadro.priecinok(), "ai_video_${System.currentTimeMillis()}.mp4")
                var ffp: Process? = null
                var zapisanych = 0
                runCatching {
                    val neg = "blurry, static, still frame, low quality, deformed, watermark, text, extra fingers, bad hands"
                    // obrázok → rozmer videa (vyplní celý záber, orežú sa okraje)
                    var px = predloha?.let { f ->
                        val src = ImageIO.read(f)
                        val k = maxOf(v.sirka.toDouble() / src.width, v.vyska.toDouble() / src.height)
                        val dst = BufferedImage(v.sirka, v.vyska, BufferedImage.TYPE_INT_RGB)
                        dst.createGraphics().apply {
                            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                            val dw = (src.width * k).toInt(); val dv = (src.height * k).toInt()
                            drawImage(src, (v.sirka - dw) / 2, (v.vyska - dv) / 2, dw, dv, null); dispose()
                        }
                        dst.getRGB(0, 0, v.sirka, v.vyska, null, 0, v.sirka)
                    }
                    for (kus in 0 until kusov) {
                        if (kus > 0) Platforma.naHlavnom { kusText = T("vai_kus", kus + 1, kusov) }
                        val r = IntArray(4)
                        Lory.pole("videa").let { (c, sl) -> runCatching { Sd.nastavLory(c, sl) } }
                        val data = Sd.generujVideo(h, popis.trim(), neg, v.sirka, v.vyska, snimokKusa, sada.kroky, sada.cfg, v.posun, seed + kus, sada.metoda, px, r)
                            ?: throw IllegalStateException(Sd.chyba().ifBlank { "Video sa nepodarilo" })
                        val (w, vy, t) = r.toList()
                        // fps podľa modelu (Wan 2.1 = 16, Wan 2.2 = 24) – engine vracia 16 pre všetky, video by bolo spomalené
                        val fps = v.fps
                        val snimka = w * vy * 3
                        // „rovno vo Full HD / 4K“: AI zväčší len skutočné snímky (nie dopočítané), až potom sa dopočíta plynulosť
                        val zv = ChatJadro.nastavenia.videoZvacsenie.takeIf { it > 0 && VylepsenieJadro.mamModel() } ?: 0
                        if (ffp == null) {
                            // náhľad = prvá snímka
                            val obr = BufferedImage(w, vy, BufferedImage.TYPE_INT_RGB)
                            val pxN = IntArray(w * vy) { i -> ((data[i * 3].toInt() and 255) shl 16) or ((data[i * 3 + 1].toInt() and 255) shl 8) or (data[i * 3 + 2].toInt() and 255) }
                            obr.setRGB(0, 0, w, vy, pxN, 0, w); ImageIO.write(obr, "png", File(out.path.removeSuffix(".mp4") + ".png"))
                            // MP4: dvojnásobná plynulosť (dopočítané snímky) a 2× väčšie; snímky sa zapisujú priebežne
                            val vstupRoz = if (zv > 0) "${w * 4}x${vy * 4}" else "${w}x$vy"
                            val k = if (zv > 0) zv.toDouble() / minOf(w, vy) else 2.0
                            val tw = (w * k).toInt() / 2 * 2; val th = (vy * k).toInt() / 2 * 2
                            val filter = if (zv > 0) "scale=$tw:$th:flags=lanczos,minterpolate=fps=${cielFps(fps)}:mi_mode=mci:mc_mode=aobmc:vsbmc=1"
                                         else "minterpolate=fps=${cielFps(fps)}:mi_mode=mci:mc_mode=aobmc:vsbmc=1,scale=$tw:$th:flags=lanczos"
                            ffp = ProcessBuilder(ff, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", vstupRoz, "-r", "$fps", "-i", "-",
                                "-vf", filter,
                                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out.path)
                                .redirectErrorStream(true).start().also { pr -> Thread { runCatching { pr.inputStream.readBytes() } }.start() }
                        }
                        // ďalší kúsok začína posledným snímkom predchádzajúceho → prvý vynecháme (bol by dvakrát)
                        val od = if (kus == 0) 0 else 1
                        if (zv > 0) {
                            val roz = IntArray(2); val obr = IntArray(w * vy)
                            for (fi in od until t) {
                                if (zrusVideo) throw IllegalStateException(T("zrusit"))
                                val b0 = fi * snimka
                                for (i in 0 until w * vy) obr[i] = -0x1000000 or ((data[b0 + i * 3].toInt() and 255) shl 16) or ((data[b0 + i * 3 + 1].toInt() and 255) shl 8) or (data[b0 + i * 3 + 2].toInt() and 255)
                                val vys = VylepsenieJadro.zvacsiSnimku(obr, w, vy, roz)
                                val rgb = ByteArray(roz[0] * roz[1] * 3)
                                for (i in 0 until roz[0] * roz[1]) { val c = vys[i]; rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte() }
                                ffp!!.outputStream.write(rgb)
                                val n = fi - od + 1; val spolu = t - od
                                Platforma.naHlavnom { kusText = T("vid_zvacsujem", n, spolu) + if (kusov > 1) " · " + T("vai_kus", kus + 1, kusov) else "" }
                            }
                        } else ffp!!.outputStream.write(data, od * snimka, (t - od) * snimka)
                        zapisanych += t - od
                        // posledná snímka → predloha ďalšieho kúska
                        val z = (t - 1) * snimka
                        px = IntArray(w * vy) { i -> ((data[z + i * 3].toInt() and 255) shl 16) or ((data[z + i * 3 + 1].toInt() and 255) shl 8) or (data[z + i * 3 + 2].toInt() and 255) }
                        kusHotovych = kus + 1
                    }
                }.onFailure { e = it.message ?: "Video sa nepodarilo" }
                // aj pri zrušení/chybe ostane hotová časť
                ffp?.let { pr ->
                    runCatching { pr.outputStream.close() }
                    if (pr.waitFor() == 0 && zapisanych > 0) { subor = out; if (e.isNotEmpty() && kusHotovych > 0) { e = ""; Platforma.oznam(T("vai_cast", kusHotovych, kusov)) } }
                    else if (e.isEmpty()) e = "ffmpeg zlyhal"
                }
            }
            Platforma.naHlavnom {
                generuje = false; priebeh = 0f; kusText = ""; skladam = false
                // vráti voľbu obrázkového modelu (načíta sa, keď ho bude treba)
                if (predtym != null && predtym != "sada:" + sada.id) { uvolniPamat(); aktivnyModel = predtym }
                hotovo(subor, e)
            }
        }
    }

    /** chat + obrázky spolu do ~10 GB, aby na 16 GB ostalo miesto pre systém */
    private const val LIMIT_SPOLU = 10_000L * 1024 * 1024

    @Volatile private var zrusVideo = false
    fun zrus() { zrusVideo = true; if (h != 0L) Sd.zrus(h) }

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

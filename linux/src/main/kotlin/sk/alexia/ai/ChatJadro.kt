package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** jeObrazkova = žiadosť o obrázok alebo hotový obrázok v chate; jazykovému modelu sa neposiela */
class Sprava(val rola: String, text: String, val jeObrazkova: Boolean = false, obrazok: String? = null, video: String? = null) {
    var text by mutableStateOf(text)
    var obrazok by mutableStateOf(obrazok)
    /** priečinok projektu videa (obrázky + video.mp4) */
    var video by mutableStateOf(video)
    var priebeh by mutableStateOf(0f)
    var stavText by mutableStateOf("")
    /** úryvky z dokumentu pre model (nezobrazujú sa) */
    var kontext: String? = null
}

/** „uprav seed 12345 a sprav tam …“ / „edit 12345: …“ → (seed, zmena), inak null */
fun upravaObrazka(t: String): Pair<Long, String>? {
    val m = Regex("^\\s*(uprav(iť|it)?|úprav|zmeň|zmen|edit)\\s+(obr[aá]zok\\s+)?(seed\\s*)?#?(\\d{3,12})\\s*[:,.-]?\\s*(a\\s+|and\\s+)?(.+)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t) ?: return null
    val zmena = m.groupValues[7].trim().takeIf { it.isNotEmpty() } ?: return null
    return m.groupValues[5].toLong() to zmena
}

/** sila úpravy podľa slov: jemne = drží sa predlohy, výrazne = viac zmien */
fun silaUpravy(t: String): Float {
    // overené testom so Z-Image: 0.8 = obrázok ostane, zmena je jasne vidieť; 0.9 = výraznejšia zmena
    val x = t.lowercase()
    return when {
        listOf("jemne", "trochu", "mierne", "slightly", "drž sa", "drz sa", "predloh").any { it in x } -> 0.75f
        listOf("výrazne", "vyrazne", "úplne", "uplne", "silno", "strongly", "completely").any { it in x } -> 0.92f
        else -> 0.85f
    }
}

/** „seed 12345“ v popise obrázka → (seed, popis bez neho) */
fun seedZPopisu(p: String): Pair<Long?, String> {
    val m = Regex("(?i)\\bseed\\s*[:#]?\\s*(\\d{3,12})").find(p) ?: return null to p
    return m.groupValues[1].toLong() to p.removeRange(m.range).replace(Regex("\\s{2,}"), " ").trim().trim(',')
}

/** „sprav ai video: …“, „/aivideo …“ → popis pre video AI (Wan), inak null */
fun popisAIVidea(t: String): String? {
    val m = Regex("^\\s*(/aivideo|/ai-video|sprav(te)? ai video|vytvor ai video|ai video|video ai|make an ai video)[\\s:,]+(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t)
    return m?.groupValues?.get(3)?.trim()?.takeIf { it.isNotEmpty() }
}

/** „rozhýbaj seed 12345 …“ → (seed, popis pohybu alebo "") */
fun rozhybanie(t: String): Pair<Long, String>? {
    val m = Regex("^\\s*(rozhýbaj|rozhybaj|animuj|animate)\\s+(obr[aá]zok\\s+)?(seed\\s*)?#?(\\d{3,12})\\s*[:,.-]?\\s*(a\\s+|and\\s+)?(.*)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t) ?: return null
    return m.groupValues[4].toLong() to m.groupValues[6].trim()
}

/** "/video …", "sprav video: …", "make a video …" → popis videa, inak null */
fun popisVidea(t: String): String? {
    val m = Regex("^\\s*(/video|sprav(te)? video|vytvor video|urob video|make a video|create a video)[\\s:,]+(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t)
    return m?.groupValues?.get(3)?.trim()?.takeIf { it.isNotEmpty() }
}

/** "/obrazok mačka", "nakresli mačku", "draw a cat" → popis obrázka, inak null */
fun popisObrazka(t: String): String? {
    val m = Regex("^\\s*(/obr[aá]zok|/obr|/img|/image|nakresli|namaľuj|vytvor obr[aá]zok|draw|generate image)[\\s:,]+(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t)
    return m?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
}

enum class StavModelu { ZIADNY, NACITAVA, PRIPRAVENY, CHYBA }

/**
 * Chat s lokálnym modelom. Všetky volania llama.cpp idú cez jedno vlákno.
 * Modelu sa posiela len to, čo je v chate (+ systémový prompt, ak si ho používateľ sám nastaví).
 */
object ChatJadro {
    private val vlakno = Executors.newSingleThreadExecutor { Thread(it, "llama").apply { priority = Thread.MAX_PRIORITY } }
    private var h = 0L
    private var spustene = false
    lateinit var nastavenia: Nastavenia
        private set

    val spravy = mutableStateListOf<Sprava>()
    var stav by mutableStateOf(StavModelu.ZIADNY)
        private set
    var chyba by mutableStateOf("")
        private set
    var popisModelu by mutableStateOf("")
        private set
    var generuje by mutableStateOf(false)
        private set
    var tokenovZaSekundu by mutableStateOf(0f)
        private set
    private var nacitany: String? = null
    private var nacitanyKontext = 0
    private var nacitaneVlakna = 0

    fun init() {
        if (spustene) return
        spustene = true
        nastavenia = Nastavenia()
        Sifra.priprav()
        if (!Sifra.zamknute) nacitajChat()
        if (nastavenia.vzdialene) pripoj()
        else nastavenia.aktivnyModel?.let { if (File(it).exists()) nacitajModel(it) }
    }

    fun potrebujeZnovuNacitat() = nacitany != null &&
        (nacitanyKontext != nastavenia.kontext || nacitaneVlakna != nastavenia.vlakna)

    fun nacitajModel(cesta: String) {
        if (nastavenia.vzdialene) { nastavenia.vzdialene = false; vzdialenyModel = "" }   // vlastný model namiesto vzdialeného
        nastavenia.aktivnyModel = cesta; nastavenia.uloz()
        stav = StavModelu.NACITAVA; chyba = ""
        val ctx = nastavenia.kontext; val vl = nastavenia.vlakna
        vlakno.execute {
            if (h != 0L) { Llama.uvolni(h); h = 0L }
            val novy = Llama.nacitaj(cesta, ctx, vl)
            val chybaText = if (novy == 0L) Llama.chyba() else ""
            if (novy != 0L) nastavVzorkovanie(novy)
            val popis = if (novy != 0L) Llama.popis(novy) else ""
            h = novy
            Platforma.naHlavnom {
                if (novy != 0L) {
                    stav = StavModelu.PRIPRAVENY; popisModelu = popis
                    nacitany = cesta; nacitanyKontext = ctx; nacitaneVlakna = vl
                } else {
                    stav = StavModelu.CHYBA; chyba = chybaText; nacitany = null
                }
            }
        }
    }

    private fun nastavVzorkovanie(handle: Long) =
        Llama.vzorkovanie(handle, nastavenia.teplota, 0.9f, 40, 0.05f, 1.1f)

    fun aktualizujVzorkovanie() { vlakno.execute { if (h != 0L) nastavVzorkovanie(h) } }

    /** veľkosť práve načítaného jazykového modelu (0 = žiadny) */
    fun velkostNacitaneho(): Long = nacitany?.let { File(it).length() } ?: 0L

    fun nazovModelu(): String = if (nastavenia.vzdialene) vzdialenyModel else nacitany?.let { File(it).nameWithoutExtension } ?: ""

    // ---------------------------------------------------------------- prepojenie: model beží na inom zariadení
    var vzdialenyModel by mutableStateOf("")
        private set
    var vzdialeneMeno by mutableStateOf("")
        private set
    @Volatile private var zastavitVzdialene = false

    /** pripojí sa k inému zariadeniu (adresa a PIN z nastavení) */
    fun pripoj() {
        val n = nastavenia
        if (n.vzdialenaAdresa.isBlank()) { stav = StavModelu.CHYBA; chyba = T("siet_bez_adresy"); return }
        stav = StavModelu.NACITAVA; chyba = ""
        Thread {
            val r = runCatching { Klient.info(n.vzdialenaAdresa, n.vzdialenyPin) }
            Platforma.naHlavnom {
                if (!n.vzdialene) return@naHlavnom
                r.onSuccess { i ->
                    vzdialenyModel = i.model; vzdialeneMeno = i.meno
                    if (i.pripraveny) { stav = StavModelu.PRIPRAVENY; popisModelu = "${i.meno} · ${i.model}" }
                    else { stav = StavModelu.CHYBA; chyba = T("siet_bez_modelu", i.meno) }
                }
                r.onFailure { stav = StavModelu.CHYBA; chyba = T("siet_nepripojene", it.message ?: "") }
            }
        }.start()
    }

    /** prepne medzi vlastným modelom a modelom na inom zariadení */
    fun nastavVzdialene(zapnut: Boolean) {
        if (generuje) zastav()
        nastavenia.vzdialene = zapnut; nastavenia.uloz()
        if (zapnut) {
            vlakno.execute { if (h != 0L) { Llama.uvolni(h); h = 0L } }
            nacitany = null; pripoj()
        } else {
            vzdialenyModel = ""; stav = StavModelu.ZIADNY; popisModelu = ""
            nastavenia.aktivnyModel?.let { if (File(it).exists()) nacitajModel(it) }
        }
    }

    /** pre server: vygeneruje odpoveď cudziemu zariadeniu; vráti null alebo text chyby */
    fun generujPreSiet(spravy: List<Pair<String, String>>, maxTok: Int, kus: (ByteArray) -> Boolean): String? {
        val sys = nastavenia.systemSJazykom()
        return vlakno.submit<String?> {
            if (h == 0L) return@submit T("siet_server_bez_modelu")
            val historia = spravy.toMutableList()
            var v: Int
            while (true) {
                val zoznam = (if (sys.isNotEmpty() && historia.none { it.first == "system" }) listOf("system" to sys) else emptyList()) + historia
                v = Llama.generuj(h, zoznam.map { it.first }.toTypedArray(), zoznam.map { it.second }.toTypedArray(), maxTok) { b -> kus(b) }
                if (v == -2 && historia.size > 1) { historia.removeAt(0); if (historia.firstOrNull()?.first == "assistant") historia.removeAt(0); continue }
                break
            }
            when (v) { -1 -> Llama.chyba(); -2 -> T("siet_dlha"); else -> null }
        }.get()
    }

    fun posli(text: String, obrazok: Boolean = false, video: Boolean = false) {
        if (text.isBlank()) return
        popisAIVidea(text)?.let { natocAI(text.trim(), it); return }
        rozhybanie(text)?.let { (seed, pohyb) -> rozhybaj(text.trim(), seed, pohyb); return }
        val popisV = if (video) text.trim() else popisVidea(text)
        if (popisV != null) { natoc(text.trim(), popisV); return }
        upravaObrazka(text)?.let { (seed, zmena) -> uprav(text.trim(), seed, zmena); return }
        val popis = if (obrazok) text.trim() else popisObrazka(text)
        if (popis != null) { nakresli(text.trim(), popis); return }
        if (generuje || stav != StavModelu.PRIPRAVENY) return
        spravy += Sprava("user", text.trim()).apply { kontext = Dokumenty.kontextPreOtazku(text, nastavenia.kontext) }
        generuj()
    }

    /** obrázok priamo v chate – kreslí ho obrázkový model (Obrázky) */
    /** rozhýbe obrázok z appky (Wan 2.2) */
    private fun rozhybaj(text: String, seed: Long, pohyb: String) {
        if (ObrazkyJadro.generuje || VideoJadro.pracuje) return
        spravy += Sprava("user", text, jeObrazkova = true)
        val odpoved = Sprava("assistant", "", jeObrazkova = true); spravy += odpoved
        if (nastavenia.vzdialene) { odpoved.text = "⚠ " + T("video_vzdialene"); ulozChat(); return }
        if (Podpis.zakazane(pohyb)) { odpoved.text = "⚠ " + T("upr_zakazane"); ulozChat(); return }
        val f = ObrazkyJadro.najdiPodlaSeedu(seed) ?: run { odpoved.text = "⚠ " + T("upr_nenajdene", seed); ulozChat(); return }
        val popis = listOf(ObrazkyJadro.popisObrazka(f).orEmpty(), pohyb.ifBlank { "natural subtle motion, cinematic" }).filter { it.isNotBlank() }.joinToString(", ")
        odpoved.stavText = T("vai_tvorim")
        ObrazkyJadro.natocAI(popis, null, f) { v, e ->
            if (v != null) { odpoved.video = v.path; odpoved.obrazok = v.path.removeSuffix(".mp4") + ".png" } else odpoved.text = "⚠ $e"
            odpoved.stavText = ""
            ulozChat()
        }
    }

    /** video AI (Wan) priamo v chate */
    private fun natocAI(text: String, popis: String) {
        if (ObrazkyJadro.generuje || VideoJadro.pracuje) return
        spravy += Sprava("user", text, jeObrazkova = true)
        val odpoved = Sprava("assistant", "", jeObrazkova = true); spravy += odpoved
        if (nastavenia.vzdialene) { odpoved.text = "⚠ " + T("video_vzdialene"); ulozChat(); return }
        odpoved.stavText = T("vai_tvorim")
        val o = ObrazkyJadro
        val cezObrazok = nastavenia.videoCezObrazok && o.i2vSada() != null && o.obrazkovaPreVideo() != null
        if (cezObrazok) {
            o.natocCezObrazok(popis, null) { f, e ->
                if (f != null) { odpoved.video = f.path; odpoved.obrazok = f.path.removeSuffix(".mp4") + ".png" } else odpoved.text = "⚠ $e"
                odpoved.stavText = ""
                ulozChat()
            }
            return
        }
        ObrazkyJadro.natocAI(popis, null) { f, e ->
            if (f != null) { odpoved.video = f.path; odpoved.obrazok = f.path.removeSuffix(".mp4") + ".png" } else odpoved.text = "⚠ $e"
            odpoved.stavText = ""
            ulozChat()
        }
    }

    /** krátke video z obrázkov priamo v chate */
    private fun natoc(text: String, popis: String) {
        if (ObrazkyJadro.generuje || VideoJadro.pracuje) return
        spravy += Sprava("user", text, jeObrazkova = true)
        val odpoved = Sprava("assistant", "", jeObrazkova = true); spravy += odpoved
        if (nastavenia.vzdialene) { odpoved.text = "⚠ " + T("video_vzdialene"); ulozChat(); return }
        odpoved.stavText = T("video_zacinam")
        VideoJadro.vytvor(popis, { t, p -> odpoved.stavText = t; odpoved.priebeh = p }) { proj, e ->
            if (proj != null) { odpoved.video = proj.dir.path; odpoved.obrazok = proj.obrazky.firstOrNull()?.path }
            else odpoved.text = "⚠ $e"
            ulozChat()
        }
    }

    /** úprava obrázka vytvoreného v appke podľa jeho seedu */
    private fun uprav(text: String, seed: Long, zmena: String) {
        if (ObrazkyJadro.generuje) return
        spravy += Sprava("user", text, jeObrazkova = true)
        val odpoved = Sprava("assistant", "", jeObrazkova = true); spravy += odpoved
        if (nastavenia.vzdialene) { odpoved.text = "⚠ " + T("upr_vzdialene"); ulozChat(); return }
        ObrazkyJadro.upravDoChatu(seed, zmena, silaUpravy(text)) { f, e ->
            if (f != null) odpoved.obrazok = f.path else odpoved.text = "⚠ $e"
            ulozChat()
        }
    }

    private fun nakresli(text: String, popisCely: String) {
        val (seed, popis) = seedZPopisu(popisCely)
        if (ObrazkyJadro.generuje) return
        spravy += Sprava("user", text, jeObrazkova = true)
        val odpoved = Sprava("assistant", "", jeObrazkova = true); spravy += odpoved
        if (nastavenia.vzdialene) {
            ObrazkyJadro.nakresliNaDialku(nastavenia.vzdialenaAdresa, nastavenia.vzdialenyPin, popis) { f, e ->
                if (f != null) odpoved.obrazok = f.path else odpoved.text = "⚠ $e"
                ulozChat()
            }
            return
        }
        ObrazkyJadro.nakresliDoChatu(popis, seed) { f, e ->
            if (f != null) odpoved.obrazok = f.path else odpoved.text = "⚠ $e"
            ulozChat()
        }
    }

    /** znova vygeneruje poslednú odpoveď */
    fun znova() {
        if (generuje || stav != StavModelu.PRIPRAVENY) return
        val p = spravy.lastOrNull() ?: return
        if (p.jeObrazkova) return
        if (p.rola == "assistant") spravy.removeAt(spravy.lastIndex)
        if (spravy.lastOrNull()?.rola == "user") generuj()
    }

    private fun generuj() {
        val bez = spravy.filter { !it.jeObrazkova }
        // úryvky z dokumentu len pri poslednej otázke (staršie by zbytočne zaberali pamäť)
        val historia = bez.mapIndexed { i, it -> it.rola to (if (i == bez.lastIndex && it.kontext != null) it.kontext + "\n\n" + T("dok_otazka") + " " + it.text else it.text) }.toMutableList()
        val sys = nastavenia.systemSJazykom()
        val odpoved = Sprava("assistant", ""); spravy += odpoved
        generuje = true; tokenovZaSekundu = 0f
        val maxTok = nastavenia.maxTokenov
        if (nastavenia.vzdialene) {
            zastavitVzdialene = false
            val adr = nastavenia.vzdialenaAdresa; val pin = nastavenia.vzdialenyPin
            val zoznam = (if (sys.isNotEmpty()) listOf("system" to sys) else emptyList()) + historia
            Thread {
                val zaciatok = System.nanoTime(); var kusov = 0
                val e = Klient.chat(adr, pin, zoznam, maxTok) { t ->
                    kusov++
                    Platforma.naHlavnom {
                        odpoved.text += t
                        val s = (System.nanoTime() - zaciatok) / 1e9f
                        if (s > 0.5f) tokenovZaSekundu = kusov / s
                    }
                    !zastavitVzdialene
                }
                Platforma.naHlavnom {
                    generuje = false
                    if (e != null && odpoved.text.isEmpty()) odpoved.text = "⚠ $e"
                    ulozChat()
                    Hlas.poOdpovedi(odpoved.text)
                }
            }.start()
            return
        }
        vlakno.execute {
            val zaciatok = System.nanoTime(); var tokenov = 0
            var vysledok: Int
            while (true) {
                val zoznam = (if (sys.isNotEmpty()) listOf("system" to sys) else emptyList()) + historia
                vysledok = Llama.generuj(h, zoznam.map { it.first }.toTypedArray(), zoznam.map { it.second }.toTypedArray(), maxTok) { b ->
                    val kus = String(b, Charsets.UTF_8); tokenov++
                    Platforma.naHlavnom {
                        odpoved.text += kus
                        val s = (System.nanoTime() - zaciatok) / 1e9f
                        if (s > 0.5f) tokenovZaSekundu = tokenov / s
                    }
                    true
                }
                // dlhý rozhovor: zabudne najstaršie správy (ponechá poslednú otázku)
                if (vysledok == -2 && historia.size > 1) { historia.removeAt(0); if (historia.firstOrNull()?.first == "assistant") historia.removeAt(0); continue }
                break
            }
            val chybaText = when (vysledok) { -1 -> Llama.chyba(); -2 -> "Správa je dlhšia ako pamäť modelu (kontext) – zvýš ho v nastaveniach."; else -> "" }
            Platforma.naHlavnom {
                generuje = false
                if (chybaText.isNotEmpty() && odpoved.text.isEmpty()) odpoved.text = "⚠ $chybaText"
                ulozChat()
                Hlas.poOdpovedi(odpoved.text)
            }
        }
    }

    fun zastav() { zastavitVzdialene = true; if (h != 0L) Llama.zastav(h) }

    /** uvoľní jazykový model z pamäte (napr. pri tvorbe obrázkov); znova sa načíta pri návrate do chatu */
    fun uvolniPamat() {
        if (nastavenia.vzdialene) return   // model je na inom zariadení – tu nič nezaberá
        if (generuje) zastav()
        vlakno.execute { if (h != 0L) { Llama.uvolni(h); h = 0L } }
        nacitany = null; popisModelu = ""
        if (stav != StavModelu.CHYBA) stav = StavModelu.ZIADNY
    }

    /** po návrate do chatu znova načíta posledný model */
    fun obnov() {
        if (nastavenia.vzdialene) { if (stav != StavModelu.PRIPRAVENY && stav != StavModelu.NACITAVA) pripoj(); return }
        val m = nastavenia.aktivnyModel ?: return
        if (nacitany == null && stav != StavModelu.NACITAVA && File(m).exists()) nacitajModel(m)
    }

    fun novyChat() {
        zastav()
        spravy.clear(); Historia.novy()
        vlakno.execute { if (h != 0L) Llama.zabudni(h) }
    }

    fun zmazModel(f: File) {
        if (nacitany == f.path) {
            vlakno.execute { if (h != 0L) { Llama.uvolni(h); h = 0L } }
            nacitany = null; stav = StavModelu.ZIADNY; popisModelu = ""
            nastavenia.aktivnyModel = null; nastavenia.uloz()
        }
        vlakno.execute { f.delete() }
    }

    fun jeAktivny(f: File) = nacitany == f.path

    // --- chat sa ukladá do súboru appky, aby prežil zatvorenie ---
    /** história: zašifrovaný rozhovor (pozri Historia) */
    private fun ulozChat() {
        val a = JSONArray(); spravy.forEach { a.put(JSONObject().put("r", it.rola).put("t", it.text).put("k", it.jeObrazkova).apply { it.obrazok?.let { o -> put("o", o) }; it.video?.let { v -> put("v", v) } }) }
        Historia.uloz(Historia.aktualne, a)
        if (nastavenia.aktualnyChat != Historia.aktualne) { nastavenia.aktualnyChat = Historia.aktualne; nastavenia.uloz() }
    }

    private fun nacitajChat() {
        Historia.nacitaj(nastavenia.aktualnyChat)
        zobraz(Historia.aktualne)
    }

    private fun zobraz(id: String) {
        spravy.clear()
        val a = Historia.obsah(id) ?: return
        for (i in 0 until a.length()) a.getJSONObject(i).let { spravy += Sprava(it.getString("r"), it.getString("t"), it.optBoolean("k"), Platforma.cesta(it.optString("o").ifEmpty { null }), Platforma.cesta(it.optString("v").ifEmpty { null })) }
    }

    /** po zadaní hesla načíta históriu */
    fun odomkni(heslo: String): Boolean = Sifra.odomkni(heslo).also { if (it) nacitajChat() }

    /** otvorí rozhovor z histórie */
    fun otvor(id: String) {
        if (id == Historia.aktualne) return
        zastav()
        Historia.prepni(id); nastavenia.aktualnyChat = id; nastavenia.uloz()
        zobraz(id)
        vlakno.execute { if (h != 0L) Llama.zabudni(h) }
    }

    fun zmazChat(id: String) {
        Historia.zmaz(id)
        if (id == Historia.aktualne) novyChat()
    }
}

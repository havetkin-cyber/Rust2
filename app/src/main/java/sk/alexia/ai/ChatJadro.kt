package sk.alexia.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    var video by mutableStateOf(video)   // videá robí PC / Legion Go S; tu sa len zachová
    /** úryvky z dokumentu pre model (nezobrazujú sa) */
    var kontext: String? = null
}

/** "/obrazok mačka", "nakresli mačku", "draw a cat" → popis obrázka, inak null */
fun popisObrazka(t: String): String? {
    val m = Regex("^\\s*(/obr[aá]zok|/obr|/img|/image|nakresli|namaľuj|vytvor obr[aá]zok|draw|generate image)[\\s:,]+(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t)
    return m?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
}

/** „/úprava seed 8234 pridaj čiapku“ → (seed, zmena), inak null */
fun upravaObrazka(t: String): Pair<Long, String>? {
    val m = Regex("^\\s*/?(úprava|uprava|upraviť|upravit|uprav|úprav|zmeniť|zmenit|zmeň|zmen|edit)\\s+(obr[aá]zok\\s+)?(seed\\s*)?[:#]?\\s*(\\d{3,12})\\s*[:,.-]?\\s*(a\\s+|and\\s+)?(.+)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(t) ?: return null
    val zmena = m.groupValues[6].trim().takeIf { it.isNotEmpty() } ?: return null
    return m.groupValues[4].toLong() to zmena
}

enum class StavModelu { ZIADNY, NACITAVA, PRIPRAVENY, CHYBA }

/**
 * Chat s lokálnym modelom. Všetky volania llama.cpp idú cez jedno vlákno.
 * Modelu sa posiela len to, čo je v chate (+ systémový prompt, ak si ho používateľ sám nastaví).
 */
object ChatJadro {
    private val vlakno = Executors.newSingleThreadExecutor { Thread(it, "llama").apply { priority = Thread.MAX_PRIORITY } }
    private val hlavne = Handler(Looper.getMainLooper())
    private var h = 0L
    private lateinit var app: Context
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

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        Platforma.init(app)
        nastavenia = Nastavenia(app)
        nacitajChat()
        if (nastavenia.vzdialene) pripoj()
        else nastavenia.aktivnyModel?.let { if (File(it).exists()) nacitajModel(it) }
    }

    fun potrebujeZnovuNacitat() = nacitany != null &&
        (nacitanyKontext != nastavenia.kontext || nacitaneVlakna != nastavenia.vlakna)

    fun nacitajModel(cesta: String) {
        if (nastavenia.vzdialene) { nastavenia.vzdialene = false; vzdialenyModel = ""; pcBezModelu = false }   // vlastný model namiesto vzdialeného
        nastavenia.aktivnyModel = cesta; nastavenia.uloz()
        nacitajLokalne(cesta)
    }

    /** načíta model v mobile bez vypnutia prepojenia s PC (keď PC nemá chatový model) */
    private fun nacitajLokalne(cesta: String) {
        stav = StavModelu.NACITAVA; chyba = ""
        val ctx = nastavenia.kontext; val vl = nastavenia.vlakna
        vlakno.execute {
            if (h != 0L) { Llama.uvolni(h); h = 0L }
            val novy = Llama.nacitaj(cesta, ctx, vl)
            val chybaText = if (novy == 0L) Llama.chyba() else ""
            if (novy != 0L) nastavVzorkovanie(novy)
            val popis = if (novy != 0L) Llama.popis(novy) else ""
            h = novy
            hlavne.post {
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

    fun nazovModelu(): String = if (chatNaPc) vzdialenyModel else nacitany?.let { File(it).nameWithoutExtension } ?: ""

    /** veľkosť práve načítaného jazykového modelu (0 = žiadny) */
    fun velkostNacitaneho(): Long = nacitany?.let { File(it).length() } ?: 0L

    // ---------------------------------------------------------------- prepojenie: model beží na inom zariadení
    var vzdialenyModel by mutableStateOf("")
        private set
    var vzdialeneMeno by mutableStateOf("")
        private set
    @Volatile private var zastavitVzdialene = false
    /** PC je pripojené (obrázky, videá…), ale nemá spustený chatový model (alebo neodpovedá) → chat beží v mobile */
    var pcBezModelu by mutableStateOf(false)
        private set
    /** chat naozaj beží na PC */
    val chatNaPc get() = nastavenia.vzdialene && !pcBezModelu

    /** PC chat nemá – použije sa model v mobile, ak nejaký je */
    private fun chatVMobile(sprava: String) {
        pcBezModelu = true
        val m = nastavenia.aktivnyModel
        if (m != null && File(m).exists()) {
            if (nacitany == m && h != 0L) { stav = StavModelu.PRIPRAVENY; chyba = "" } else nacitajLokalne(m)
            Platforma.oznam(sprava)
        } else { stav = StavModelu.CHYBA; chyba = sprava + " " + T("siet_ani_mobil") }
    }

    fun pripoj() {
        val n = nastavenia
        if (n.vzdialenaAdresa.isBlank()) { stav = StavModelu.CHYBA; chyba = T("siet_bez_adresy"); return }
        stav = StavModelu.NACITAVA; chyba = ""
        Thread {
            val r = runCatching { Klient.info(n.vzdialenaAdresa, n.vzdialenyPin) }
            hlavne.post {
                if (!n.vzdialene) return@post
                r.onSuccess { i ->
                    vzdialenyModel = i.model; vzdialeneMeno = i.meno
                    if (i.pripraveny) {
                        pcBezModelu = false
                        // chat ide cez PC – model v mobile netreba držať v pamäti
                        vlakno.execute { if (h != 0L) { Llama.uvolni(h); h = 0L } }; nacitany = null
                        stav = StavModelu.PRIPRAVENY; popisModelu = "${i.meno} · ${i.model}"
                    } else chatVMobile(T("siet_chat_v_mobile", i.meno))
                }
                r.onFailure { chatVMobile(T("siet_nepripojene", it.message ?: "")) }
            }
        }.start()
    }

    fun nastavVzdialene(zapnut: Boolean) {
        if (generuje) zastav()
        nastavenia.vzdialene = zapnut; nastavenia.uloz()
        if (zapnut) {
            vlakno.execute { if (h != 0L) { Llama.uvolni(h); h = 0L } }
            nacitany = null; pripoj()
        } else {
            vzdialenyModel = ""; stav = StavModelu.ZIADNY; popisModelu = ""; pcBezModelu = false
            nastavenia.aktivnyModel?.let { if (File(it).exists()) nacitajModel(it) }
        }
    }

    fun posli(text: String, obrazok: Boolean = false) {
        if (text.isBlank()) return
        if (!obrazok) upravaObrazka(text)?.let { (seed, zmena) -> uprav(text.trim(), seed, zmena); return }
        val popis = if (obrazok) text.trim() else popisObrazka(text)
        if (popis != null) { nakresli(text.trim(), popis); return }
        if (generuje || stav != StavModelu.PRIPRAVENY) return
        spravy += Sprava("user", text.trim()).apply { kontext = Dokumenty.kontextPreOtazku(text, nastavenia.kontext) }
        generuj()
    }

    /** úprava obrázka podľa seedu – robí ju PC (obrázok je v jeho galérii) */
    private fun uprav(text: String, seed: Long, zmena: String) {
        if (ObrazkyJadro.generuje) return
        spravy += Sprava("user", text, jeObrazkova = true)
        val odpoved = Sprava("assistant", "", jeObrazkova = true); spravy += odpoved
        if (!(nastavenia.vzdialene && PcSpojenie.info?.obrazky == true)) { odpoved.text = "⚠ " + T("upr_len_pc"); ulozChat(); return }
        ObrazkyJadro.nakresliNaDialku(nastavenia.vzdialenaAdresa, nastavenia.vzdialenyPin, zmena, upravaSeed = seed) { f, e ->
            if (f != null) odpoved.obrazok = f.path else odpoved.text = "⚠ $e"; ulozChat()
        }
    }

    /** obrázok priamo v chate */
    private fun nakresli(text: String, popis: String) {
        if (ObrazkyJadro.generuje) return
        spravy += Sprava("user", text, jeObrazkova = true)
        val odpoved = Sprava("assistant", "", jeObrazkova = true); spravy += odpoved
        val hotovo = { f: File?, e: String -> if (f != null) odpoved.obrazok = f.path else odpoved.text = "⚠ $e"; ulozChat() }
        if (nastavenia.vzdialene && PcSpojenie.info?.obrazky == true) ObrazkyJadro.nakresliNaDialku(nastavenia.vzdialenaAdresa, nastavenia.vzdialenyPin, popis, hotovo = hotovo)
        else ObrazkyJadro.nakresliDoChatu(popis, hotovo)
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
        // úryvky z dokumentu len pri poslednej otázke
        val historia = bez.mapIndexed { i, it -> it.rola to (if (i == bez.lastIndex && it.kontext != null) it.kontext + "\n\n" + T("dok_otazka") + " " + it.text else it.text) }.toMutableList()
        val sys = nastavenia.systemSJazykom()
        val odpoved = Sprava("assistant", ""); spravy += odpoved
        generuje = true; tokenovZaSekundu = 0f
        val maxTok = nastavenia.maxTokenov
        if (chatNaPc) {
            zastavitVzdialene = false
            val adr = nastavenia.vzdialenaAdresa; val pin = nastavenia.vzdialenyPin
            val zoznam = (if (sys.isNotEmpty()) listOf("system" to sys) else emptyList()) + historia
            Thread {
                val zaciatok = System.nanoTime(); var kusov = 0
                val e = Klient.chat(adr, pin, zoznam, maxTok) { t ->
                    kusov++
                    hlavne.post {
                        odpoved.text += t
                        val s = (System.nanoTime() - zaciatok) / 1e9f
                        if (s > 0.5f) tokenovZaSekundu = kusov / s
                    }
                    !zastavitVzdialene
                }
                hlavne.post {
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
                    hlavne.post {
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
            hlavne.post {
                generuje = false
                if (chybaText.isNotEmpty() && odpoved.text.isEmpty()) odpoved.text = "⚠ $chybaText"
                ulozChat()
                Hlas.poOdpovedi(odpoved.text)
            }
        }
    }


    // ---------------------------------------------------------------- AI napíše presný anglický prompt (obrázok / video)

    private fun systemPrePrompt(video: Boolean) = if (!video)
        "You are an expert prompt writer for AI image generators (FLUX). The user gives a short idea, often in Slovak. " +
        "Reply with ONLY one detailed English image prompt: one paragraph, 70-130 words, no quotes, no title, no explanations, no lists. " +
        "Start with the shot type and medium, e.g. 'Cinematic portrait photograph of...'. Describe precisely and concretely: the subject " +
        "(an adult, face shape, skin, eyes, makeup, expression, hair color, length and style), clothing and accessories with materials, colors and details, " +
        "pose, background and setting, lighting, camera or lens, and realism (realistic skin and fabric textures, sharp focus). " +
        "Keep every detail the user asked for and invent fitting details for the rest. " +
        "Never write sexual content, nudity or anything explicit; people are adults, fully clothed and shown in a non-sexual way."
    else
        "You are an expert prompt writer for the Wan 2.2 AI video model. The user gives a short idea, often in Slovak. " +
        "Reply with ONLY one English video prompt: one paragraph, 40-90 words, no quotes, no explanations. " +
        "Briefly describe the scene and subject, then the motion clearly: one or two simple actions with words like slowly and gently, " +
        "natural details (hair moving, breathing, blinking), the camera (static camera or the camera slowly pushes in), and end with " +
        "'smooth natural motion, realistic'. Never write sexual content, nudity or anything explicit."

    /** odpoveď malého modelu očistí na jeden odsek bez úvodzoviek a nadpisov */
    private fun ocistiPrompt(t: String): String {
        var s = t.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        s = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { it.endsWith(":") && it.length < 40 }
            .joinToString(" ")
        s = s.replace(Regex("^(\\*\\*)?(prompt|image prompt|video prompt)(\\*\\*)?\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        return s.trim().trim('"', '“', '”', '\'', '`').trim()
    }

    var pisePrompt by mutableStateOf(false)
        private set

    /** z krátkeho nápadu (aj po slovensky) napíše presný anglický prompt – modelom v mobile alebo na PC */
    fun napisPrompt(napad: String, video: Boolean, hotovo: (String?, String) -> Unit) {
        if (pisePrompt || napad.isBlank()) return
        if (generuje) { hotovo(null, T("prompt_chat_pise")); return }
        val zoznam = listOf("system" to systemPrePrompt(video), "user" to napad.trim())
        val sb = StringBuilder()
        if (chatNaPc) {
            pisePrompt = true
            val adr = nastavenia.vzdialenaAdresa; val pin = nastavenia.vzdialenyPin
            Thread {
                val e = runCatching { Klient.chat(adr, pin, zoznam, 400) { t -> sb.append(t); true } }.getOrElse { it.message }
                val v = ocistiPrompt(sb.toString())
                hlavne.post { pisePrompt = false; if (v.isNotBlank()) hotovo(v, "") else hotovo(null, e ?: T("prompt_nepodarilo")) }
            }.start()
            return
        }
        val m = nastavenia.aktivnyModel
        if (nacitany == null && (m == null || !File(m).exists())) { hotovo(null, T("prompt_bez_modelu")); return }
        pisePrompt = true
        if (nacitany == null && m != null) nacitajLokalne(m)   // načítanie beží na rovnakom vlákne – generovanie počká
        vlakno.execute {
            val e = if (h == 0L) T("prompt_bez_modelu") else {
                val v = Llama.generuj(h, zoznam.map { it.first }.toTypedArray(), zoznam.map { it.second }.toTypedArray(), 400) { b -> sb.append(String(b, Charsets.UTF_8)); true }
                if (v == -1) Llama.chyba() else null
            }
            val vysledok = ocistiPrompt(sb.toString())
            hlavne.post { pisePrompt = false; if (vysledok.isNotBlank()) hotovo(vysledok, "") else hotovo(null, e ?: T("prompt_nepodarilo")) }
        }
    }

    fun zastav() { zastavitVzdialene = true; if (h != 0L) Llama.zastav(h) }

    /** uvoľní jazykový model z pamäte (napr. pri tvorbe obrázkov); znova sa načíta pri návrate do chatu */
    fun uvolniPamat() {
        if (chatNaPc) return
        if (generuje) zastav()
        vlakno.execute { if (h != 0L) { Llama.uvolni(h); h = 0L } }
        nacitany = null; popisModelu = ""
        if (stav != StavModelu.CHYBA) stav = StavModelu.ZIADNY
    }

    /** po návrate do chatu znova načíta posledný model */
    fun obnov() {
        if (nastavenia.vzdialene) {
            // PC bez chatového modelu: znova sa opýtame (mohol sa medzitým spustiť), inak beží mobil
            if (stav != StavModelu.NACITAVA && (stav != StavModelu.PRIPRAVENY || pcBezModelu)) pripoj()
            return
        }
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
        for (i in 0 until a.length()) a.getJSONObject(i).let { spravy += Sprava(it.getString("r"), it.getString("t"), it.optBoolean("k"), it.optString("o").ifEmpty { null }, it.optString("v").ifEmpty { null }) }
    }

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

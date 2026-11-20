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
class Sprava(val rola: String, text: String, val jeObrazkova: Boolean = false, obrazok: String? = null) {
    var text by mutableStateOf(text)
    var obrazok by mutableStateOf(obrazok)
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
        nacitajChat()
        nastavenia.aktivnyModel?.let { if (File(it).exists()) nacitajModel(it) }
    }

    fun potrebujeZnovuNacitat() = nacitany != null &&
        (nacitanyKontext != nastavenia.kontext || nacitaneVlakna != nastavenia.vlakna)

    fun nacitajModel(cesta: String) {
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

    fun nazovModelu(): String = nacitany?.let { File(it).nameWithoutExtension } ?: ""

    fun posli(text: String, obrazok: Boolean = false) {
        if (text.isBlank()) return
        val popis = if (obrazok) text.trim() else popisObrazka(text)
        if (popis != null) { nakresli(text.trim(), popis); return }
        if (generuje || stav != StavModelu.PRIPRAVENY) return
        spravy += Sprava("user", text.trim())
        generuj()
    }

    /** obrázok priamo v chate – kreslí ho obrázkový model (Obrázky) */
    private fun nakresli(text: String, popis: String) {
        if (ObrazkyJadro.generuje) return
        spravy += Sprava("user", text, jeObrazkova = true)
        val odpoved = Sprava("assistant", "", jeObrazkova = true); spravy += odpoved
        ObrazkyJadro.nakresliDoChatu(popis) { f, e ->
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
        val historia = spravy.filter { !it.jeObrazkova }.map { it.rola to it.text }.toMutableList()
        val sys = nastavenia.systemovyPrompt.trim()
        val odpoved = Sprava("assistant", ""); spravy += odpoved
        generuje = true; tokenovZaSekundu = 0f
        val maxTok = nastavenia.maxTokenov
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
            }
        }
    }

    fun zastav() { if (h != 0L) Llama.zastav(h) }

    /** uvoľní jazykový model z pamäte (napr. pri tvorbe obrázkov); znova sa načíta pri návrate do chatu */
    fun uvolniPamat() {
        if (generuje) zastav()
        vlakno.execute { if (h != 0L) { Llama.uvolni(h); h = 0L } }
        nacitany = null; popisModelu = ""
        if (stav != StavModelu.CHYBA) stav = StavModelu.ZIADNY
    }

    /** po návrate do chatu znova načíta posledný model */
    fun obnov() {
        val m = nastavenia.aktivnyModel ?: return
        if (nacitany == null && stav != StavModelu.NACITAVA && File(m).exists()) nacitajModel(m)
    }

    fun novyChat() {
        zastav()
        spravy.clear(); ulozChat()
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
    private fun suborChatu() = File(Platforma.priecinok, "chat.json")

    private fun ulozChat() {
        val a = JSONArray(); spravy.forEach { a.put(JSONObject().put("r", it.rola).put("t", it.text).put("k", it.jeObrazkova).apply { it.obrazok?.let { o -> put("o", o) } }) }
        runCatching { suborChatu().writeText(a.toString()) }
    }

    private fun nacitajChat() {
        runCatching {
            val a = JSONArray(suborChatu().readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { spravy += Sprava(it.getString("r"), it.getString("t"), it.optBoolean("k"), it.optString("o").ifEmpty { null }) }
        }
    }
}

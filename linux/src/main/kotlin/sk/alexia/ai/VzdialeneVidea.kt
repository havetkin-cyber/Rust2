package sk.alexia.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Videá zadané z mobilu: rad úloh, ktoré PC postupne vytvorí (aj keď pri ňom nikto nie je).
 * Zoznam sa ukladá, takže po reštarte appky hotové videá v mobile ostanú.
 */
object VzdialeneVidea {
    class Uloha(
        val id: String, val popis: String, val sekundy: Int, val cezObrazok: Boolean, val predloha: String?,
        var stav: String = "caka", var chyba: String = "", var subor: String? = null, val cas: Long = System.currentTimeMillis(),
        var zacal: Long = 0L, var trvanie: Long = 0L,
        // videoklip: skladba (súbor na PC), obrázky z galérie PC oddelené „|“, tempo strihu 1–3
        val skladba: String? = null, val fotky: String = "", val tempo: Int = 2,
    ) {
        fun json() = JSONObject().put("id", id).put("popis", popis).put("sekundy", sekundy).put("cezObrazok", cezObrazok)
            .put("predloha", predloha ?: "").put("stav", stav).put("chyba", chyba).put("subor", subor ?: "").put("cas", cas)
            .put("velkost", subor?.let { File(it).length() } ?: 0L)
            .put("zacal", zacal).put("trvanie", trvanie)
            .put("skladba", skladba ?: "").put("fotky", fotky).put("tempo", tempo).put("klip", skladba != null)
            // ako dlho už beží (počíta PC – hodiny mobilu môžu ísť inak)
            .put("bezi_ms", if (stav == "bezi" && zacal > 0) System.currentTimeMillis() - zacal else 0L)
    }

    private val ulohy = mutableListOf<Uloha>()
    private var nacitane = false
    @Volatile private var bezi: Uloha? = null
    private val subor get() = File(Platforma.priecinok, "vzdialene-videa.json")

    private fun nacitaj() {
        if (nacitane) return
        nacitane = true
        runCatching {
            val a = JSONArray(subor.readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                ulohy += Uloha(o.getString("id"), o.getString("popis"), o.optInt("sekundy", 5), o.optBoolean("cezObrazok"),
                    o.optString("predloha").ifBlank { null },
                    // úloha, ktorá bežala pri vypnutí appky, sa spustí znova
                    o.optString("stav").let { if (it == "bezi") "caka" else it }, o.optString("chyba"), Platforma.cesta(o.optString("subor").ifBlank { null }), o.optLong("cas"), 0L, o.optLong("trvanie"),
                    Platforma.cesta(o.optString("skladba").ifBlank { null }), o.optString("fotky"), o.optInt("tempo", 2))
            }
        }
    }

    private fun uloz() = runCatching { subor.writeText(JSONArray().apply { ulohy.forEach { put(it.json()) } }.toString()) }

    fun pridaj(popis: String, sekundy: Int, cezObrazok: Boolean, predloha: String?): String = synchronized(this) {
        nacitaj()
        val u = Uloha(System.currentTimeMillis().toString(36), popis, sekundy, cezObrazok, predloha)
        ulohy += u
        // starých hotových necháme najviac 30
        while (ulohy.count { it.stav != "caka" && it.stav != "bezi" } > 30) ulohy.remove(ulohy.first { it.stav != "caka" && it.stav != "bezi" })
        uloz()
        Platforma.naHlavnom { dalsia() }
        u.id
    }

    /** videoklip z mobilu: skladba sa uloží na PC, obrázky sú mená z galérie PC */
    fun pridajKlip(nazov: String, skladba: File, fotky: List<String>, tempo: Int): String = synchronized(this) {
        nacitaj()
        val u = Uloha(System.currentTimeMillis().toString(36), "🎵 " + nazov, 0, false, null,
            skladba = skladba.path, fotky = fotky.joinToString("|"), tempo = tempo.coerceIn(1, 3))
        ulohy += u
        uloz()
        Platforma.naHlavnom { dalsia() }
        u.id
    }

    fun stav(): JSONObject = synchronized(this) {
        nacitaj()
        val o = ObrazkyJadro
        JSONObject()
            .put("ulohy", JSONArray().apply { ulohy.asReversed().forEach { put(it.json()) } })
            .put("bezi", bezi?.id ?: "")
            .put("priebeh", if (bezi?.skladba != null) Videoklip.priebeh.toDouble() else if (bezi != null) o.priebeh.toDouble() else 0.0)
            .put("text", if (bezi?.skladba != null) Videoklip.cinnost else if (bezi != null) listOf(o.kusText, if (o.skladam) T("video_skladam", (o.priebeh * 100).toInt()) else "").filter { it.isNotBlank() }.joinToString(" · ") else "")
            .put("pcZaneprazdnene", o.generuje && bezi == null)
    }

    fun najdi(id: String): Uloha? = synchronized(this) { nacitaj(); ulohy.find { it.id == id } }

    fun zrus(id: String) = synchronized(this) {
        nacitaj()
        val u = ulohy.find { it.id == id } ?: return@synchronized
        when (u.stav) {
            "caka" -> ulohy.remove(u)
            "bezi" -> if (u.skladba != null) Videoklip.zrus() else ObrazkyJadro.zrus()
            else -> { u.subor?.let { File(it).delete() }; u.skladba?.let { File(it).delete() }; ulohy.remove(u) }
        }
        uloz()
    }

    /** spustí ďalšiu čakajúcu úlohu, keď je PC voľné (volá sa na hlavnom vlákne) */
    private fun dalsia() {
        val u = synchronized(this) {
            nacitaj()
            if (bezi != null) return
            ulohy.firstOrNull { it.stav == "caka" }
        } ?: return
        // PC práve tvorí niečo iné – skúsime o chvíľu
        if (if (u.skladba != null) Videoklip.pracuje else ObrazkyJadro.generuje) { Thread { Thread.sleep(15_000); Platforma.naHlavnom { dalsia() } }.apply { isDaemon = true }.start(); return }
        synchronized(this) { bezi = u; u.stav = "bezi"; u.zacal = System.currentTimeMillis(); uloz() }
        val hotovo: (File?, String) -> Unit = { f, e ->
            synchronized(this) {
                if (f != null) { u.stav = "hotova"; u.subor = f.path } else { u.stav = "chyba"; u.chyba = e.ifBlank { "Video sa nepodarilo vytvoriť" } }
                if (u.zacal > 0) u.trvanie = System.currentTimeMillis() - u.zacal
                bezi = null; uloz()
            }
            Platforma.naHlavnom { dalsia() }
        }
        val predloha = u.predloha?.let { ObrazkyJadro.obrazokZGalerie(it) }
        when {
            u.skladba != null -> {
                val f = u.fotky.split('|').filter { it.isNotBlank() }.mapNotNull { ObrazkyJadro.obrazokZGalerie(it) }
                Videoklip.vytvor(File(u.skladba), f, u.tempo, hotovo)
            }
            u.predloha != null && predloha == null -> hotovo(null, "Obrázok sa na PC nenašiel")
            predloha != null -> {
                val popis = listOf(ObrazkyJadro.popisObrazka(predloha).orEmpty(), u.popis.ifBlank { "natural subtle motion, cinematic" }).filter { it.isNotBlank() }.joinToString(", ")
                ObrazkyJadro.natocAI(popis, u.sekundy, predloha, hotovo)
            }
            u.cezObrazok -> ObrazkyJadro.natocCezObrazok(u.popis, u.sekundy, hotovo = hotovo)
            else -> ObrazkyJadro.natocAI(u.popis, u.sekundy, hotovo = hotovo)
        }
    }

    /** po štarte servera dokončí úlohy, ktoré čakali */
    fun obnov() = Platforma.naHlavnom { dalsia() }
}

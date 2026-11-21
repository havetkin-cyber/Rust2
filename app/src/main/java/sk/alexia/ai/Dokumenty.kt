package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.Normalizer
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.math.ln

/** Dokument v chate: text rozdelený na odseky (čítanie nahlas) a úryvky (hľadanie odpovedí). */
class Dokument(val nazov: String, val kluc: String, val odseky: List<String>) {
    val znakov = odseky.sumOf { it.length }
    /** úryvky ~900 znakov z celých odsekov */
    val uryvky: List<String> = buildList {
        val b = StringBuilder()
        for (o in odseky) {
            if (b.isNotEmpty() && b.length + o.length > 900) { add(b.toString()); b.clear() }
            if (b.isNotEmpty()) b.append('\n')
            b.append(o)
        }
        if (b.isNotEmpty()) add(b.toString())
    }
    private val slova = uryvky.map { Dokumenty.slova(it) }
    private val df = HashMap<String, Int>().apply { slova.forEach { s -> s.toSet().forEach { merge(it, 1, Int::plus) } } }
    private val priemer = slova.map { it.size }.average().coerceAtLeast(1.0)

    /** BM25 – najlepšie úryvky k otázke (v poradí, ako idú v knihe) */
    fun najdi(otazka: String, kolko: Int): List<Int> {
        val q = Dokumenty.slova(otazka).toSet()
        if (q.isEmpty()) return (0 until minOf(kolko, uryvky.size)).toList()
        val n = uryvky.size.toDouble()
        val skore = slova.mapIndexed { i, s ->
            val tf = HashMap<String, Int>(); s.forEach { if (it in q) tf.merge(it, 1, Int::plus) }
            var sk = 0.0
            for ((w, f) in tf) {
                val idf = ln(1 + (n - (df[w] ?: 0) + 0.5) / ((df[w] ?: 0) + 0.5))
                sk += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * s.size / priemer))
            }
            i to sk
        }
        val naj = skore.filter { it.second > 0 }.sortedByDescending { it.second }.take(kolko).map { it.first }
        return (naj.ifEmpty { (0 until minOf(kolko, uryvky.size)).toList() }).sorted()
    }
}

object Dokumenty {
    val PRIPONY = setOf("pdf", "epub", "txt", "md", "docx", "html", "htm")

    var aktualny by mutableStateOf<Dokument?>(null)
        private set
    var nacitava by mutableStateOf(false)
        private set

    /** slová bez diakritiky, malými, aspoň 3 znaky; jednoduché odrezanie koncoviek (slovenčina sa skloňuje) */
    fun slova(t: String): List<String> {
        val s = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return Regex("[a-z0-9]{3,}").findAll(s).map { it.value.take(6) }.toList()
    }

    fun otvor(f: File) {
        if (nacitava) return
        nacitava = true
        Thread {
            val r = runCatching {
                val text = citajText(f)
                val odseky = text.replace("\r", "").split(Regex("\\n\\s*\\n|(?<=[.!?…\"“”])\\n"))
                    .map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length > 1 }
                    .flatMap { o -> if (o.length <= 1500) listOf(o) else o.split(Regex("(?<=[.!?…])\\s+")).fold(mutableListOf<StringBuilder>()) { acc, v ->
                        if (acc.isEmpty() || acc.last().length + v.length > 1200) acc += StringBuilder(v) else acc.last().append(' ').append(v); acc }.map { it.toString() } }
                if (odseky.isEmpty()) throw IllegalStateException(T("dok_prazdny"))
                Dokument(f.name, f.name + ":" + f.length(), odseky)
            }
            Platforma.naHlavnom {
                nacitava = false
                r.onSuccess { aktualny = it; Platforma.oznam(T("dok_nacitany", it.nazov, it.odseky.size)) }
                r.onFailure { Platforma.oznam("⚠ " + (it.message ?: it.toString())) }
            }
        }.start()
    }

    fun zavri() { Citac.zastav(); aktualny = null }

    private fun citajText(f: File): String = when (f.extension.lowercase()) {
        "pdf" -> run {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(Platforma.app)
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(f)
        }.use { d ->
            val s = com.tom_roush.pdfbox.text.PDFTextStripper()
            s.paragraphStart = "\n"; s.paragraphEnd = "\n"
            s.getText(d)
        }
        "epub" -> epub(f)
        "docx" -> ZipFile(f).use { z ->
            val x = z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            x.replace(Regex("</w:p>"), "\n\n").replace(Regex("<w:tab/>"), " ").replace(Regex("<[^>]+>"), "").let(::entity)
        }
        "html", "htm" -> html(f.readText())
        else -> f.readText()
    }

    /** EPUB: poradie kapitol podľa spine v .opf */
    private fun epub(f: File): String = ZipFile(f).use { z ->
        val cont = z.getEntry("META-INF/container.xml")?.let { z.getInputStream(it).readBytes().toString(Charsets.UTF_8) } ?: ""
        val opfCesta = Regex("full-path=\"([^\"]+)\"").find(cont)?.groupValues?.get(1)
        val zakl = opfCesta?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
        val opf = opfCesta?.let { z.getEntry(it) }?.let { z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
        val poradie = if (opf != null) {
            val polozky = Regex("<item\\s[^>]*>").findAll(opf).associate { m ->
                val t = m.value
                (Regex("id=\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "") to (Regex("href=\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: "")
            }
            Regex("<itemref\\s[^>]*idref=\"([^\"]+)\"").findAll(opf).mapNotNull { polozky[it.groupValues[1]] }
                .map { java.net.URLDecoder.decode(zakl + it.substringBefore('#'), "UTF-8") }.toList()
        } else z.entries().asSequence().map { it.name }.filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }.sorted().toList()
        poradie.mapNotNull { z.getEntry(it) }.joinToString("\n\n") { html(z.getInputStream(it).readBytes().toString(Charsets.UTF_8)) }
    }

    private fun html(h: String): String = entity(
        h.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|blockquote|section|tr)>"), "\n\n")
            .replace(Regex("<[^>]+>"), "")
    )

    private fun entity(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#160;", " ").replace(Regex("&#(\\d+);")) { runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault(" ") }
        .replace("&amp;", "&")

    /** úryvky pre model k otázke – toľko, koľko sa zmestí do pamäte modelu (kontextu) */
    fun kontextPreOtazku(otazka: String, kontextTokenov: Int): String? {
        val d = aktualny ?: return null
        // ~3 znaky na token (slovenčina), polovica kontextu pre úryvky
        val znakov = (kontextTokenov * 3 / 2).coerceIn(1500, 24000)
        if (d.znakov <= znakov) return T("dok_cely", d.nazov) + "\n\n" + d.odseky.joinToString("\n")
        val kolko = (znakov / 900).coerceIn(1, 20)
        val ids = d.najdi(otazka, kolko)
        return T("dok_uryvky", d.nazov) + "\n\n" + ids.joinToString("\n\n---\n\n") { d.uryvky[it] }
    }

    // --- pozícia čítania (pamätá sa pre každú knihu) ---
    private val subor get() = File(Platforma.priecinok, "citanie.properties")
    fun pozicia(d: Dokument): Int = runCatching { Properties().apply { subor.inputStream().use { load(it) } }.getProperty(d.kluc)?.toInt() }.getOrNull() ?: 0
    fun ulozPoziciu(d: Dokument, i: Int) = runCatching {
        val p = Properties(); if (subor.exists()) subor.inputStream().use { p.load(it) }
        p.setProperty(d.kluc, i.toString()); subor.outputStream().use { p.store(it, null) }
    }
}

/** čítanie dokumentu nahlas zvoleným hlasom, odsek po odseku; pamätá si, kde si skončil */
object Citac {
    var cita by mutableStateOf(false)
        private set
    var index by mutableIntStateOf(0)
        private set

    fun otvor() { Dokumenty.aktualny?.let { index = Dokumenty.pozicia(it).coerceIn(0, it.odseky.lastIndex) } }

    fun spusti(od: Int = index) {
        val d = Dokumenty.aktualny ?: return
        if (!Hlas.mam()) { Platforma.oznam(T("hlas_najprv")); return }
        index = od.coerceIn(0, d.odseky.lastIndex)
        cita = true
        citajDalsi(d)
    }

    private fun citajDalsi(d: Dokument) {
        if (!cita || Dokumenty.aktualny !== d) return
        if (index > d.odseky.lastIndex) { cita = false; index = 0; Dokumenty.ulozPoziciu(d, 0); return }
        Dokumenty.ulozPoziciu(d, index)
        Hlas.hovor(d.odseky[index], poKonci = {
            Platforma.naHlavnom { if (cita && Dokumenty.aktualny === d) { index++; citajDalsi(d) } }
        })
    }

    fun zastav() { if (cita) { cita = false; Hlas.zastav() } }
    fun posun(o: Int) {
        val d = Dokumenty.aktualny ?: return
        val nove = (index + o).coerceIn(0, d.odseky.lastIndex)
        val citalo = cita
        zastav(); index = nove; Dokumenty.ulozPoziciu(d, nove)
        if (citalo) spusti(nove)
    }
    fun chodNa(i: Int) { val c = cita; zastav(); index = i; Dokumenty.aktualny?.let { Dokumenty.ulozPoziciu(it, i) }; if (c) spusti(i) }
}

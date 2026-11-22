package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * LoRA adaptéry – malé doplnky k modelu (štýl, postava, oblečenie…), v troch kategóriách:
 * obrazky (FLUX, Z-Image, SD), videa (Wan) a chat (jazykový model, GGUF).
 * Súbory sú v Dokumenty/Alexia AI/LoRA/<kategória>, výber a sila sa ukladajú.
 * LoRA musí byť pre rovnaký základný model (FLUX LoRA k FLUX, Wan 2.2 LoRA k Wan 2.2, Qwen LoRA ku Qwen…).
 */
object Lory {
    val KATEGORIE = listOf("obrazky", "videa", "chat")
    private val PRIPONY = mapOf("obrazky" to setOf("safetensors", "gguf", "ckpt", "pt"), "videa" to setOf("safetensors", "gguf"), "chat" to setOf("gguf"))
    fun pripony(k: String) = PRIPONY[k] ?: setOf("safetensors", "gguf")

    /** zmena výberu → prekreslenie UI */
    var verzia by mutableIntStateOf(0)
        private set

    fun priecinok(k: String) = File(File(Platforma.alexia, "LoRA"), when (k) { "obrazky" -> "Obrazky"; "videa" -> "Videa"; else -> "Chat" }).apply { mkdirs() }

    fun subory(k: String): List<File> = priecinok(k).listFiles { f -> f.isFile && f.extension.lowercase() in pripony(k) }
        ?.sortedBy { it.name.lowercase() } ?: emptyList()

    // výber: kategória → meno súboru → sila (len zapnuté)
    private val subor get() = File(Platforma.priecinok, "lory.json")
    private val vyber: MutableMap<String, MutableMap<String, Float>> by lazy {
        val m = KATEGORIE.associateWith { mutableMapOf<String, Float>() }.toMutableMap()
        runCatching {
            val j = JSONObject(subor.readText())
            KATEGORIE.forEach { k -> j.optJSONObject(k)?.let { o -> o.keys().forEach { n -> m[k]!![n] = o.getDouble(n).toFloat() } } }
        }
        m
    }
    private val sily = mutableMapOf<String, Float>()   // sila aj pre vypnuté (aby sa nestratila)

    private fun uloz() {
        runCatching { subor.writeText(JSONObject().apply { vyber.forEach { (k, m) -> put(k, JSONObject(m.mapValues { it.value.toDouble() })) } }.toString()) }
        verzia++
    }

    fun zapnuta(k: String, meno: String) = vyber[k]?.containsKey(meno) == true
    fun sila(k: String, meno: String) = vyber[k]?.get(meno) ?: sily["$k/$meno"] ?: 1f

    fun prepni(k: String, meno: String, zapnut: Boolean) {
        val m = vyber[k] ?: return
        if (zapnut) m[meno] = sily["$k/$meno"] ?: 1f else m.remove(meno)?.let { sily["$k/$meno"] = it }
        uloz()
    }

    fun nastavSilu(k: String, meno: String, s: Float) {
        sily["$k/$meno"] = s
        vyber[k]?.let { if (it.containsKey(meno)) it[meno] = s }
        uloz()
    }

    fun zmaz(k: String, f: File) { vyber[k]?.remove(f.name); f.delete(); uloz() }

    fun pocetZapnutych(k: String) = aktivne(k).size

    /** zapnuté LoRA, ktoré naozaj existujú: (cesta, sila) */
    fun aktivne(k: String): List<Pair<String, Float>> {
        val d = priecinok(k)
        return vyber[k].orEmpty().mapNotNull { (n, s) -> File(d, n).takeIf { it.isFile }?.let { it.path to s } }
    }

    fun pole(k: String): Pair<Array<String>, FloatArray> = aktivne(k).let { a -> a.map { it.first }.toTypedArray() to a.map { it.second }.toFloatArray() }

    fun pridany() { verzia++ }
}

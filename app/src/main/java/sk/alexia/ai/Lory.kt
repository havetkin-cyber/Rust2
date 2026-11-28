package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * Chatové LoRA v mobile (GGUF adaptéry k jazykovému modelu v mobile).
 * Súbory sú vo filesDir/lory, výber a sila sa ukladajú do lory.json.
 */
object Lory {
    var verzia by mutableIntStateOf(0)
        private set

    fun priecinok() = File(Platforma.priecinok, "lory").apply { mkdirs() }
    fun subory(): List<File> = priecinok().listFiles { f -> f.isFile && f.extension.equals("gguf", true) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private val subor get() = File(Platforma.priecinok, "lory.json")
    private val vyber: MutableMap<String, Float> by lazy {
        val m = mutableMapOf<String, Float>()
        runCatching { val j = JSONObject(subor.readText()); j.keys().forEach { n -> m[n] = j.getDouble(n).toFloat() } }
        m
    }
    private val sily = mutableMapOf<String, Float>()

    private fun uloz() {
        runCatching { subor.writeText(JSONObject(vyber.mapValues { it.value.toDouble() }).toString()) }
        verzia++
        ChatJadro.pouziLory()
    }

    fun zapnuta(meno: String) = vyber.containsKey(meno)
    fun sila(meno: String) = vyber[meno] ?: sily[meno] ?: 1f
    fun prepni(meno: String, zapnut: Boolean) {
        if (zapnut) vyber[meno] = sily[meno] ?: 1f else vyber.remove(meno)?.let { sily[meno] = it }
        uloz()
    }
    fun nastavSilu(meno: String, s: Float) { sily[meno] = s; if (vyber.containsKey(meno)) vyber[meno] = s; uloz() }
    fun zmaz(f: File) { vyber.remove(f.name); f.delete(); uloz() }

    fun pole(): Pair<Array<String>, FloatArray> {
        val a = vyber.mapNotNull { (n, s) -> File(priecinok(), n).takeIf { it.isFile }?.let { it.path to s } }
        return a.map { it.first }.toTypedArray() to a.map { it.second }.toFloatArray()
    }
}

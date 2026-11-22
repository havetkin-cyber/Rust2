package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * História chatov – každý rozhovor je zvlášť zašifrovaný (AES-256-GCM) v priečinku appky.
 * Kľúč drží [Sifra] (mobil: Android Keystore; PC: kľúč v priečinku appky, voliteľne chránený heslom).
 */
object Historia {
    class Zaznam(val id: String, val nazov: String, val cas: Long)

    val zoznam = mutableStateListOf<Zaznam>()
    var aktualne by mutableStateOf(novId())
        private set

    private fun dir() = Platforma.rozhovory
    private fun subor(id: String) = File(dir(), "$id.enc")
    private fun suborZoznamu() = File(dir(), "zoznam.enc")
    fun novId() = "c" + System.currentTimeMillis().toString(36) + (100..999).random()

    // ---- šifrovanie: [12 B IV][šifrovaný text + 16 B tag]
    private fun zasifruj(t: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, Sifra.kluc())
        return c.iv + c.doFinal(t.toByteArray(Charsets.UTF_8))
    }

    private fun desifruj(b: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, Sifra.kluc(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }

    private fun zapis(f: File, t: String) {
        val tmp = File(f.path + ".tmp"); tmp.writeBytes(zasifruj(t))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** načíta zoznam rozhovorov (po odomknutí) a prevezme starý nešifrovaný chat.json */
    fun nacitaj(aktualneId: String?) {
        zoznam.clear()
        runCatching {
            val a = JSONArray(desifruj(suborZoznamu().readBytes()))
            for (i in 0 until a.length()) a.getJSONObject(i).let { zoznam += Zaznam(it.getString("id"), it.getString("n"), it.getLong("c")) }
        }
        zoznam.removeAll { !subor(it.id).exists() }
        val stary = File(Platforma.priecinok, "chat.json")
        if (stary.exists()) {
            runCatching { val a = JSONArray(stary.readText()); if (a.length() > 0) { val id = novId(); uloz(id, a); aktualne = id } }
            stary.delete()
        } else aktualne = aktualneId?.takeIf { subor(it).exists() } ?: zoznam.firstOrNull()?.id ?: novId()
    }

    private fun ulozZoznam() {
        val a = JSONArray(); zoznam.forEach { a.put(JSONObject().put("id", it.id).put("n", it.nazov).put("c", it.cas)) }
        runCatching { zapis(suborZoznamu(), a.toString()) }
    }

    /** uloží rozhovor; prázdny sa neukladá */
    fun uloz(id: String, spravy: JSONArray) {
        if (spravy.length() == 0) return
        runCatching { zapis(subor(id), spravy.toString()) }.onFailure { return }
        val prva = (0 until spravy.length()).map { spravy.getJSONObject(it) }.firstOrNull { it.optString("r") == "user" }?.optString("t").orEmpty()
        val nazov = prva.replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "Chat" }
        val i = zoznam.indexOfFirst { it.id == id }
        val z = Zaznam(id, nazov, System.currentTimeMillis())
        if (i >= 0) zoznam.removeAt(i)
        zoznam.add(0, z)
        ulozZoznam()
    }

    fun obsah(id: String): JSONArray? = runCatching { JSONArray(desifruj(subor(id).readBytes())) }.getOrNull()

    fun prepni(id: String) { aktualne = id }
    fun novy() { aktualne = novId() }

    fun zmaz(id: String) {
        subor(id).delete()
        zoznam.removeAll { it.id == id }
        ulozZoznam()
    }

    /** po zmene hesla: všetko prešifruje novým kľúčom */
    fun presifruj(starySifrujuci: () -> Unit) {
        val vsetko = zoznam.associate { it.id to obsah(it.id) }
        starySifrujuci()
        vsetko.forEach { (id, a) -> if (a != null) runCatching { zapis(subor(id), a.toString()) } }
        ulozZoznam()
    }
}

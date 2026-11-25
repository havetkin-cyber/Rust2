package sk.alexia.ai

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Podpis obrázkov, ktoré vytvorila appka. Úprava podľa textu („uprav seed 12345 a …“) je povolená len pre obrázok
 * s platným podpisom – fotku zvonka (napr. skutočného človeka) takto upraviť nejde, ani keď k nej niekto
 * dopíše vlastný záznam so seedom alebo podvrhne súbor: podpis je HMAC-SHA256 z obsahu PNG a údajov o obrázku
 * tajným kľúčom appky (rovnaký kľúč ako šifrovaná história).
 */
object Podpis {
    private fun kluc(): SecretKeySpec {
        val k = Sifra.kluc().encoded
        val odvodeny = MessageDigest.getInstance("SHA-256").digest("alexia-podpis-obrazkov".toByteArray() + k)
        return SecretKeySpec(odvodeny, "HmacSHA256")
    }

    fun hash(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); var n: Int; while (i.read(b).also { n = it } > 0) md.update(b, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sprava(j: JSONObject, h: String) = "$h|${j.optLong("seed")}|${j.optString("popis")}|${j.optInt("w")}x${j.optInt("h")}"

    private fun hmac(t: String): String = Mac.getInstance("HmacSHA256").run { init(kluc()); doFinal(t.toByteArray(Charsets.UTF_8)) }
        .joinToString("") { "%02x".format(it) }

    /** pridá do záznamu hash obrázka a podpis */
    fun podpis(png: File, j: JSONObject) {
        runCatching { val h = hash(png); j.put("hash", h).put("podpis", hmac(sprava(j, h))) }
    }

    /** platí podpis a sedí obsah súboru? */
    fun over(png: File, j: JSONObject): Boolean = runCatching {
        val h = hash(png)
        h == j.optString("hash") && MessageDigest.isEqual(hmac(sprava(j, h)).toByteArray(), j.optString("podpis").toByteArray())
    }.getOrDefault(false)

    /** úprava, ktorá by z obrázka robila intímny alebo nahý obsah, sa nespustí */
    private val zakazaneSlova = listOf(
        "vyzleč", "vyzlec", "nahá", "nahý", "nahé", "nahú", "nahí", " naha", " nahy", "nahot", "bez oblečenia", "bez oblecenia", "bez šiat", "bez siat", "bez podprsenky", "bez nohavičiek",
        "spodné prádlo", "spodne pradlo", "prádlo", "pradlo", "bikin", "plavky", "hrudník", "prsia", "prsa", "zadok", "intím", "intim",
        "sex", "erot", "porno", "nude", "naked", "nsfw", "topless", "undress", "lingerie", "underwear", "bra ", "panties", "boobs",
        "breast", "nipple", "butt ", "explicit", "striptíz", "striptiz", "striptease",
    )
    fun zakazane(t: String): Boolean { val x = " " + t.lowercase() + " "; return zakazaneSlova.any { x.contains(it) } }
}

package sk.alexia.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Pripojenie k Alexia AI na inom zariadení v domácej sieti (napr. Legion Go S so 14B modelom).
 * Rovnaký súbor je v mobilnej aj počítačovej verzii – používa len java.net.
 */
object Klient {
    const val PORT = 8765
    const val PORT_HLADANIA = 8766

    class Info(val meno: String, val model: String, val pripraveny: Boolean, val obrazky: Boolean)

    private fun url(adresa: String, cesta: String): URL {
        val a = adresa.trim().removePrefix("http://").removeSuffix("/")
        return URL("http://" + (if (a.contains(':')) a else "$a:$PORT") + cesta)
    }

    private fun spoj(adresa: String, pin: String, cesta: String, telo: JSONObject? = null, citanie: Int = 15_000): HttpURLConnection {
        val k = url(adresa, cesta).openConnection() as HttpURLConnection
        k.connectTimeout = 5_000; k.readTimeout = citanie
        k.setRequestProperty("X-Alexia-PIN", pin.trim())
        if (telo != null) {
            k.requestMethod = "POST"; k.doOutput = true
            k.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            k.outputStream.use { it.write(telo.toString().toByteArray(Charsets.UTF_8)) }
        }
        val kod = k.responseCode
        if (kod == 403) throw IllegalStateException("Zlý PIN")
        if (kod != 200) {
            val chyba = runCatching { k.errorStream?.bufferedReader()?.readText() }.getOrNull()
            throw IllegalStateException(chyba?.takeIf { it.isNotBlank() } ?: "Server vrátil $kod")
        }
        return k
    }

    fun info(adresa: String, pin: String): Info {
        val j = JSONObject(spoj(adresa, pin, "/api/info").inputStream.bufferedReader(Charsets.UTF_8).readText())
        return Info(j.optString("meno"), j.optString("model"), j.optBoolean("pripraveny"), j.optBoolean("obrazky"))
    }

    /** pošle rozhovor; [kus] dostáva text po kúskoch, false = zastaviť. Vráti null (v poriadku) alebo text chyby. */
    fun chat(adresa: String, pin: String, spravy: List<Pair<String, String>>, maxTok: Int, kus: (String) -> Boolean): String? {
        var zastavene = false
        return try {
            val a = JSONArray(); spravy.forEach { (r, t) -> a.put(JSONObject().put("r", r).put("t", t)) }
            val k = spoj(adresa, pin, "/api/chat", JSONObject().put("spravy", a).put("max", maxTok), citanie = 10 * 60_000)
            var chyba: String? = null
            k.inputStream.reader(Charsets.UTF_8).use { r ->
                val b = CharArray(256)
                while (true) {
                    val n = r.read(b); if (n < 0) break
                    val t = String(b, 0, n)
                    val i = t.indexOf('\u0000')   // \u0000 = koniec s chybou, za ním je text chyby
                    if (i >= 0) { if (i > 0) kus(t.substring(0, i)); chyba = t.substring(i + 1) + r.readText(); break }
                    if (!kus(t)) { zastavene = true; zastav(adresa, pin); break }
                }
            }
            chyba
        } catch (e: Exception) {
            if (zastavene) null else e.message ?: "Spojenie zlyhalo"
        }
    }

    fun zastav(adresa: String, pin: String) {
        Thread { runCatching { spoj(adresa, pin, "/api/stop", JSONObject()).inputStream.close() } }.start()
    }

    /** nechá obrázok nakresliť na druhom zariadení a uloží ho do [ciel] */
    fun obrazok(adresa: String, pin: String, popis: String, ciel: File) {
        val k = spoj(adresa, pin, "/api/obrazok", JSONObject().put("popis", popis), citanie = 30 * 60_000)
        k.inputStream.use { i -> ciel.outputStream().use { i.copyTo(it) } }
    }

    /** nájde Alexia AI v domácej sieti (odpovedá zariadenie so zapnutým zdieľaním) → zoznam (adresa, meno) */
    fun najdi(cakat: Int = 1500): List<Pair<String, String>> {
        val najdene = LinkedHashMap<String, String>()
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true; s.soTimeout = 300
                val otazka = "ALEXIA?".toByteArray()
                val ciele = mutableSetOf(InetAddress.getByName("255.255.255.255"))
                NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { ni ->
                    ni.interfaceAddresses.mapNotNull { it.broadcast }.forEach { ciele += it }
                }
                ciele.forEach { runCatching { s.send(DatagramPacket(otazka, otazka.size, it, PORT_HLADANIA)) } }
                val koniec = System.currentTimeMillis() + cakat
                val b = ByteArray(512)
                while (System.currentTimeMillis() < koniec) {
                    val p = DatagramPacket(b, b.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val t = String(p.data, 0, p.length, Charsets.UTF_8)
                    if (t.startsWith("ALEXIA ")) {
                        val casti = t.split(' ', limit = 3)
                        najdene[p.address.hostAddress + ":" + casti.getOrElse(1) { "$PORT" }] = casti.getOrElse(2) { "Alexia AI" }
                    }
                }
            }
        }
        return najdene.map { it.key to it.value }
    }
}

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

    class Info(val meno: String, val model: String, val pripraveny: Boolean, val obrazky: Boolean, val video: Boolean = false, val rozhybat: Boolean = false, val vylepsit: Boolean = false, val styl: Boolean = false)

    private fun url(adresa: String, cesta: String): URL {
        val a = adresa.trim().removePrefix("http://").removeSuffix("/")
        return URL("http://" + (if (a.contains(':')) a else "$a:$PORT") + cesta)
    }

    /** pripojenie s jedným novým pokusom (Tailscale/VPN niekedy prvé spojenie nestihne) */
    private fun spoj(adresa: String, pin: String, cesta: String, telo: JSONObject? = null, citanie: Int = 15_000): HttpURLConnection =
        try {
            try { spoj1(adresa, pin, cesta, telo, citanie) }
            catch (e: java.net.SocketTimeoutException) { if (e.message?.contains("connect", true) == true) spoj1(adresa, pin, cesta, telo, citanie) else throw e }
            catch (e: java.net.ConnectException) { Thread.sleep(1500); spoj1(adresa, pin, cesta, telo, citanie) }
        } catch (e: java.net.ConnectException) { throw IllegalStateException(T("pc_neodpoveda", adresa)) }
        catch (e: java.net.NoRouteToHostException) { throw IllegalStateException(T("pc_neodpoveda", adresa)) }
        catch (e: java.net.SocketTimeoutException) { if (e.message?.contains("connect", true) == true) throw IllegalStateException(T("pc_neodpoveda", adresa)) else throw e }

    private fun spoj1(adresa: String, pin: String, cesta: String, telo: JSONObject?, citanie: Int): HttpURLConnection {
        val k = url(adresa, cesta).openConnection() as HttpURLConnection
        k.connectTimeout = 25_000; k.readTimeout = citanie
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

    /** POST s binárnym telom (napr. fotka) */
    private fun spojTelo(adresa: String, pin: String, cesta: String, telo: ByteArray, typ: String, citanie: Int): HttpURLConnection {
        val k = url(adresa, cesta).openConnection() as HttpURLConnection
        k.connectTimeout = 25_000; k.readTimeout = citanie
        k.setRequestProperty("X-Alexia-PIN", pin.trim())
        k.requestMethod = "POST"; k.doOutput = true
        k.setRequestProperty("Content-Type", typ)
        k.setFixedLengthStreamingMode(telo.size)
        k.outputStream.use { it.write(telo) }
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
        return Info(j.optString("meno"), j.optString("model"), j.optBoolean("pripraveny"), j.optBoolean("obrazky"), j.optBoolean("video"), j.optBoolean("rozhybat"), j.optBoolean("vylepsit"), j.optBoolean("styl"))
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
    fun obrazok(adresa: String, pin: String, popis: String, ciel: File, negativny: String = "", pomer: String = "stvorec", sirka: Int = 0, vyska: Int = 0) {
        val k = spoj(adresa, pin, "/api/obrazok", JSONObject().put("popis", popis).put("negativny", negativny).put("pomer", pomer)
            .put("sirka", sirka).put("vyska", vyska), citanie = 30 * 60_000)
        k.inputStream.use { i -> ciel.outputStream().use { i.copyTo(it) } }
    }

    /** prenos štýlu na PC (FLUX.3 Klein): obsah = meno obrázka z galérie PC, telo = štýlový obrázok (JPG) */
    fun prenosStylu(adresa: String, pin: String, obsahMeno: String, styl: ByteArray, doplnok: String, ciel: File) {
        val cesta = "/api/styl?obsah=" + java.net.URLEncoder.encode(obsahMeno, "UTF-8") + "&doplnok=" + java.net.URLEncoder.encode(doplnok, "UTF-8")
        val k = spojTelo(adresa, pin, cesta, styl, "image/jpeg", citanie = 30 * 60_000)
        k.inputStream.use { i -> ciel.outputStream().use { i.copyTo(it) } }
    }

    /** vylepší fotku na PC (Real-ESRGAN); [nasobok] 1/2/4/40=4K/80=8K */
    fun vylepsi(adresa: String, pin: String, fotka: ByteArray, nasobok: Int, jas: Boolean, farby: Boolean, ostrit: Boolean, ciel: File) {
        val cesta = "/api/vylepsi?nasobok=$nasobok&jas=${if (jas) 1 else 0}&farby=${if (farby) 1 else 0}&ostrit=${if (ostrit) 1 else 0}"
        val k = spojTelo(adresa, pin, cesta, fotka, "image/jpeg", citanie = 30 * 60_000)
        k.inputStream.use { i -> ciel.outputStream().use { i.copyTo(it) } }
    }

    // ---------- videá na PC (zadané z mobilu) a galéria PC ----------

    /** pridá video do radu na PC; vráti id úlohy */
    fun videoPridaj(adresa: String, pin: String, popis: String, sekundy: Int, cezObrazok: Boolean, predloha: String?): String {
        val t = JSONObject().put("popis", popis).put("sekundy", sekundy).put("cezObrazok", cezObrazok).put("predloha", predloha ?: "")
        return JSONObject(spoj(adresa, pin, "/api/video/pridaj", t).inputStream.bufferedReader(Charsets.UTF_8).readText()).getString("id")
    }

    /** videoklip na PC: skladba z mobilu + obrázky z galérie PC; vráti id úlohy */
    fun klipPridaj(adresa: String, pin: String, nazov: String, skladba: ByteArray, fotky: List<String>, tempo: Int): String {
        val q = "/api/klip/pridaj?tempo=$tempo&nazov=" + java.net.URLEncoder.encode(nazov, "UTF-8") +
            "&fotky=" + java.net.URLEncoder.encode(fotky.joinToString("|"), "UTF-8")
        val k = spojTelo(adresa, pin, q, skladba, "application/octet-stream", citanie = 120_000)
        return JSONObject(k.inputStream.bufferedReader(Charsets.UTF_8).readText()).getString("id")
    }

    fun videoStav(adresa: String, pin: String): JSONObject =
        JSONObject(spoj(adresa, pin, "/api/video/stav").inputStream.bufferedReader(Charsets.UTF_8).readText())

    fun videoZrus(adresa: String, pin: String, id: String) {
        spoj(adresa, pin, "/api/video/zrus", JSONObject().put("id", id)).inputStream.close()
    }

    /** stiahne hotové video; [p] = priebeh 0..1 */
    fun videoStiahni(adresa: String, pin: String, id: String, ciel: java.io.OutputStream, p: (Float) -> Unit) {
        val k = spoj(adresa, pin, "/api/video/subor?id=" + java.net.URLEncoder.encode(id, "UTF-8"), citanie = 120_000)
        val spolu = k.contentLengthLong.coerceAtLeast(1)
        k.inputStream.use { i ->
            val b = ByteArray(1 shl 16); var n = 0L
            while (true) { val r = i.read(b); if (r < 0) break; ciel.write(b, 0, r); n += r; p((n.toFloat() / spolu).coerceIn(0f, 1f)) }
        }
    }

    /** posledné obrázky na PC: (meno, popis, vytvorený appkou) */
    fun galeria(adresa: String, pin: String): List<Triple<String, String, Boolean>> {
        val a = JSONObject(spoj(adresa, pin, "/api/galeria/zoznam").inputStream.bufferedReader(Charsets.UTF_8).readText()).getJSONArray("obrazky")
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Triple(o.getString("meno"), o.optString("popis"), o.optBoolean("appka")) } }
    }

    fun nahlad(adresa: String, pin: String, meno: String): ByteArray =
        spoj(adresa, pin, "/api/galeria/nahlad?meno=" + java.net.URLEncoder.encode(meno, "UTF-8")).inputStream.use { it.readBytes() }

    fun obrazokZPc(adresa: String, pin: String, meno: String, ciel: java.io.OutputStream) {
        spoj(adresa, pin, "/api/galeria/subor?meno=" + java.net.URLEncoder.encode(meno, "UTF-8"), citanie = 60_000).inputStream.use { it.copyTo(ciel) }
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

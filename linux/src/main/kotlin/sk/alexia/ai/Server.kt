package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Zdieľanie v sieti: iné zariadenie (mobil, PC) sa pripojí s PIN-om a používa model z tohto zariadenia.
 * Beží len keď ho zapneš. Bez PIN-u server nič neurobí; po 5 zlých PIN-och z jednej adresy ju na 10 min zablokuje.
 */
object Server {
    var bezi by mutableStateOf(false)
        private set
    var pripojeni by mutableStateOf(0)
        private set
    private var http: HttpServer? = null
    private var hladanie: DatagramSocket? = null
    private val zle = HashMap<String, Pair<Int, Long>>()

    /** PIN – 8 číslic, uložený v nastaveniach; dá sa vygenerovať nový */
    val pin: String get() = ChatJadro.nastavenia.pinServera.ifBlank { novyPin() }

    fun novyPin(): String {
        val r = SecureRandom()
        val p = (1..8).joinToString("") { r.nextInt(10).toString() }
        ChatJadro.nastavenia.pinServera = p; ChatJadro.nastavenia.uloz()
        return p
    }

    fun meno(): String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Alexia AI"

    /** adresy tohto zariadenia v sieti (na zadanie v mobile) */
    fun adresy(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress }
    }.getOrDefault(emptyList())

    fun spusti(): String? {
        if (bezi) return null
        return runCatching {
            val s = HttpServer.create(InetSocketAddress(Klient.PORT), 8)
            s.executor = Executors.newCachedThreadPool { Thread(it, "server").apply { isDaemon = true } }
            s.createContext("/api/info") { x -> obsluz(x) { info(x) } }
            s.createContext("/api/chat") { x -> obsluz(x) { chat(x) } }
            s.createContext("/api/stop") { x -> obsluz(x) { ChatJadro.zastav(); odpovedz(x, 200, "{}") } }
            s.createContext("/api/obrazok") { x -> obsluz(x) { obrazok(x) } }
            s.start(); http = s
            spustiHladanie()
            bezi = true
            null
        }.getOrElse { it.message ?: "Server sa nepodarilo spustiť" }
    }

    fun zastav() {
        http?.stop(0); http = null
        hladanie?.close(); hladanie = null
        bezi = false; pripojeni = 0
    }

    /** odpovedá na „ALEXIA?“ z mobilu, aby ťa našiel bez písania adresy */
    private fun spustiHladanie() {
        Thread({
            runCatching {
                DatagramSocket(Klient.PORT_HLADANIA).use { s ->
                    hladanie = s
                    val b = ByteArray(64)
                    while (!s.isClosed) {
                        val p = DatagramPacket(b, b.size)
                        s.receive(p)
                        if (String(p.data, 0, p.length) == "ALEXIA?") {
                            val o = "ALEXIA ${Klient.PORT} ${meno()}".toByteArray(Charsets.UTF_8)
                            s.send(DatagramPacket(o, o.size, p.address, p.port))
                        }
                    }
                }
            }
        }, "server-hladanie").apply { isDaemon = true }.start()
    }

    private fun obsluz(x: HttpExchange, f: () -> Unit) {
        try {
            val ip = x.remoteAddress.address.hostAddress
            val blok = synchronized(zle) { zle[ip]?.let { it.first >= 5 && System.currentTimeMillis() - it.second < 10 * 60_000 } ?: false }
            if (blok) { odpovedz(x, 429, "Príliš veľa zlých PIN-ov, skús o 10 minút"); return }
            if (x.requestHeaders.getFirst("X-Alexia-PIN") != pin) {
                synchronized(zle) { val p = zle[ip]; zle[ip] = ((p?.first ?: 0) + 1) to System.currentTimeMillis() }
                odpovedz(x, 403, "Zlý PIN"); return
            }
            synchronized(zle) { zle.remove(ip) }
            f()
        } catch (e: Exception) {
            runCatching { odpovedz(x, 500, e.message ?: "Chyba") }
        } finally { x.close() }
    }

    private fun odpovedz(x: HttpExchange, kod: Int, text: String, typ: String = "application/json; charset=utf-8") {
        val b = text.toByteArray(Charsets.UTF_8)
        x.responseHeaders.add("Content-Type", typ)
        x.sendResponseHeaders(kod, b.size.toLong())
        x.responseBody.use { it.write(b) }
    }

    private fun telo(x: HttpExchange) = JSONObject(x.requestBody.readBytes().toString(Charsets.UTF_8).ifBlank { "{}" })

    private fun info(x: HttpExchange) {
        val j = JSONObject()
            .put("meno", meno())
            .put("model", ChatJadro.nazovModelu())
            .put("pripraveny", ChatJadro.stav == StavModelu.PRIPRAVENY)
            .put("obrazky", ObrazkyJadro.aktivnyModel != null)
        odpovedz(x, 200, j.toString())
    }

    private fun chat(x: HttpExchange) {
        val j = telo(x)
        val a = j.getJSONArray("spravy")
        val spravy = (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("r") to o.getString("t") } }
        val maxTok = j.optInt("max", 1024).coerceIn(16, 8192)
        x.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        x.sendResponseHeaders(200, 0) // po kúskoch
        Platforma.naHlavnom { pripojeni++ }
        val o = x.responseBody
        val chyba = try {
            ChatJadro.generujPreSiet(spravy, maxTok) { b -> runCatching { o.write(b); o.flush() }.isSuccess }
        } finally { Platforma.naHlavnom { pripojeni = maxOf(0, pripojeni - 1) } }
        runCatching { if (chyba != null) o.write(("\u0000" + chyba).toByteArray(Charsets.UTF_8)); o.close() }
    }

    private fun obrazok(x: HttpExchange) {
        val popis = telo(x).optString("popis").trim()
        if (popis.isEmpty()) { odpovedz(x, 400, "Chýba popis"); return }
        val hotovo = CountDownLatch(1)
        var subor: File? = null; var chyba = ""
        Platforma.naHlavnom {
            if (ObrazkyJadro.generuje) { chyba = "Na počítači sa práve kreslí iný obrázok"; hotovo.countDown() }
            else ObrazkyJadro.nakresliDoChatu(popis) { f, e -> subor = f; chyba = e; hotovo.countDown() }
        }
        if (!hotovo.await(30, TimeUnit.MINUTES)) { odpovedz(x, 504, "Obrázok trval príliš dlho"); return }
        val f = subor
        if (f == null) { odpovedz(x, 500, chyba.ifBlank { "Obrázok sa nepodarilo nakresliť" }); return }
        val b = f.readBytes()
        x.responseHeaders.add("Content-Type", "image/png")
        x.sendResponseHeaders(200, b.size.toLong())
        x.responseBody.use { it.write(b) }
    }
}

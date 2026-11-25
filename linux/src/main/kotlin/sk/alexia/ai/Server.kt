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
            // videá zadané z mobilu (rad úloh) a galéria PC
            s.createContext("/api/video/pridaj") { x -> obsluz(x) { videoPridaj(x) } }
            s.createContext("/api/video/stav") { x -> obsluz(x) { odpovedz(x, 200, VzdialeneVidea.stav().toString()) } }
            s.createContext("/api/video/subor") { x -> obsluz(x) { videoSubor(x) } }
            s.createContext("/api/video/zrus") { x -> obsluz(x) { VzdialeneVidea.zrus(telo(x).optString("id")); odpovedz(x, 200, "{}") } }
            s.createContext("/api/galeria/zoznam") { x -> obsluz(x) { galeria(x) } }
            s.createContext("/api/galeria/nahlad") { x -> obsluz(x) { nahlad(x) } }
            s.createContext("/api/galeria/subor") { x -> obsluz(x) { obrazokSubor(x) } }
            s.createContext("/api/vylepsi") { x -> obsluz(x) { vylepsi(x) } }
            s.createContext("/api/styl") { x -> obsluz(x) { prenosStylu(x) } }
            s.start(); http = s
            spustiHladanie()
            bezi = true
            VzdialeneVidea.obnov()
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
            .put("video", ObrazkyJadro.videoSada() != null)
            .put("rozhybat", ObrazkyJadro.i2vSada() != null)
            .put("vylepsit", VylepsenieJadro.mamModel())
            .put("styl", ObrazkyJadro.kleinSada() != null)
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
        val j = telo(x)
        val popis = j.optString("popis").trim()
        val neg = j.optString("negativny").trim()
        val pomer = j.optString("pomer", "stvorec").let { if (it in setOf("stvorec", "vyska", "sirka", "nahodne", "vlastne")) it else "stvorec" }
        // vlastný rozmer z mobilu (nie z nastavení PC)
        val vlastny = if (pomer == "vlastne") j.optInt("sirka", 0).coerceIn(256, 1536) to j.optInt("vyska", 0).coerceIn(256, 1536) else null
        if (popis.isEmpty()) { odpovedz(x, 400, "Chýba popis"); return }
        val hotovo = CountDownLatch(1)
        var subor: File? = null; var chyba = ""
        Platforma.naHlavnom {
            if (ObrazkyJadro.generuje) { chyba = "Na počítači sa práve kreslí iný obrázok"; hotovo.countDown() }
            else ObrazkyJadro.nakresliDoChatu(popis, negativny = neg, pomer = pomer, vlastny = vlastny) { f, e -> subor = f; chyba = e; hotovo.countDown() }
        }
        if (!hotovo.await(30, TimeUnit.MINUTES)) { odpovedz(x, 504, "Obrázok trval príliš dlho"); return }
        val f = subor
        if (f == null) { odpovedz(x, 500, chyba.ifBlank { "Obrázok sa nepodarilo nakresliť" }); return }
        val b = f.readBytes()
        x.responseHeaders.add("Content-Type", "image/png")
        x.sendResponseHeaders(200, b.size.toLong())
        x.responseBody.use { it.write(b) }
    }

    private fun parameter(x: HttpExchange, meno: String): String? =
        x.requestURI.rawQuery?.split('&')?.map { it.split('=', limit = 2) }?.firstOrNull { it[0] == meno }
            ?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    private fun posliSubor(x: HttpExchange, f: File, typ: String) {
        x.responseHeaders.add("Content-Type", typ)
        x.sendResponseHeaders(200, f.length())
        x.responseBody.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }
    }

    private fun videoPridaj(x: HttpExchange) {
        val j = telo(x)
        val popis = j.optString("popis").trim()
        val predloha = j.optString("predloha").trim().ifBlank { null }
        if (popis.isEmpty() && predloha == null) { odpovedz(x, 400, "Chýba popis"); return }
        if (predloha != null && Podpis.zakazane(popis)) { odpovedz(x, 400, T("upr_zakazane")); return }
        if (predloha != null && ObrazkyJadro.i2vSada() == null) { odpovedz(x, 400, T("vai_ziadny_i2v")); return }
        if (predloha == null && ObrazkyJadro.videoSada() == null) { odpovedz(x, 400, T("vai_ziadny")); return }
        val sek = j.optInt("sekundy", 5).coerceIn(1, 180)
        val id = VzdialeneVidea.pridaj(popis, sek, j.optBoolean("cezObrazok"), predloha)
        odpovedz(x, 200, JSONObject().put("id", id).toString())
    }

    private fun videoSubor(x: HttpExchange) {
        val u = parameter(x, "id")?.let { VzdialeneVidea.najdi(it) }
        val f = u?.subor?.let { File(it) }?.takeIf { it.isFile }
        if (f == null) { odpovedz(x, 404, "Video sa nenašlo"); return }
        posliSubor(x, f, "video/mp4")
    }

    private fun galeria(x: HttpExchange) {
        val a = org.json.JSONArray()
        ObrazkyJadro.posledneObrazky(60).forEach { f ->
            a.put(JSONObject().put("meno", f.name).put("popis", ObrazkyJadro.popisObrazka(f).orEmpty()).put("appka", ObrazkyJadro.seedObrazka(f) != null))
        }
        odpovedz(x, 200, JSONObject().put("obrazky", a).toString())
    }

    private fun nahlad(x: HttpExchange) {
        val f = parameter(x, "meno")?.let { ObrazkyJadro.obrazokZGalerie(it) } ?: run { odpovedz(x, 404, "Nenašiel sa"); return }
        val img = javax.imageio.ImageIO.read(f) ?: run { odpovedz(x, 500, "Obrázok sa nedá čítať"); return }
        val k = 320.0 / maxOf(img.width, img.height)
        val w = maxOf(1, (img.width * k).toInt()); val h = maxOf(1, (img.height * k).toInt())
        val m = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        m.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null); dispose()
        }
        val b = java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(m, "jpg", b)
        x.responseHeaders.add("Content-Type", "image/jpeg")
        x.sendResponseHeaders(200, b.size().toLong())
        x.responseBody.use { it.write(b.toByteArray()) }
    }

    private fun obrazokSubor(x: HttpExchange) {
        val f = parameter(x, "meno")?.let { ObrazkyJadro.obrazokZGalerie(it) } ?: run { odpovedz(x, 404, "Nenašiel sa"); return }
        posliSubor(x, f, "image/png")
    }

    /** vylepšenie fotky z mobilu: telo = obrázok (JPG/PNG), parametre v adrese; odpoveď = PNG */
    private fun vylepsi(x: HttpExchange) {
        val nas = parameter(x, "nasobok")?.toIntOrNull()?.takeIf { it in setOf(1, 2, 4, VylepsenieJadro.K4, VylepsenieJadro.K8) } ?: 2
        val b = x.requestBody.use { i ->
            val o = java.io.ByteArrayOutputStream(); val buf = ByteArray(1 shl 16); var n = 0L
            while (true) { val r = i.read(buf); if (r < 0) break; n += r; if (n > 40_000_000) throw IllegalStateException("Fotka je príliš veľká"); o.write(buf, 0, r) }
            o.toByteArray()
        }
        if (b.isEmpty()) { odpovedz(x, 400, "Chýba fotka"); return }
        val vstup = File.createTempFile("mobil_", ".img").apply { writeBytes(b); deleteOnExit() }
        val r = runCatching { VylepsenieJadro.preMobil(vstup, nas, parameter(x, "jas") != "0", parameter(x, "farby") != "0", parameter(x, "ostrit") == "1") }
        vstup.delete()
        r.onSuccess { posliSubor(x, it, "image/png") }
            .onFailure { odpovedz(x, 500, (it.cause ?: it).message ?: "Vylepšenie zlyhalo") }
    }

    /** prenos štýlu pre mobil: obsah = obrázok z galérie PC, telo = štýlový obrázok; odpoveď = PNG */
    private fun prenosStylu(x: HttpExchange) {
        val obsah = parameter(x, "obsah")?.let { ObrazkyJadro.obrazokZGalerie(it) } ?: run { odpovedz(x, 404, T("styl_len_appka")); return }
        val doplnok = parameter(x, "doplnok").orEmpty()
        val b = x.requestBody.use { i ->
            val o = java.io.ByteArrayOutputStream(); val buf = ByteArray(1 shl 16); var n = 0L
            while (true) { val r = i.read(buf); if (r < 0) break; n += r; if (n > 30_000_000) throw IllegalStateException("Obrázok je príliš veľký"); o.write(buf, 0, r) }
            o.toByteArray()
        }
        if (b.isEmpty()) { odpovedz(x, 400, "Chýba štýlový obrázok"); return }
        val styl = File.createTempFile("styl_", ".jpg").apply { writeBytes(b); deleteOnExit() }
        val hotovo = CountDownLatch(1)
        var subor: File? = null; var chyba = ""
        Platforma.naHlavnom {
            if (ObrazkyJadro.generuje) { chyba = T("video_zaneprazdnene"); hotovo.countDown() }
            else ObrazkyJadro.prenosStylu(obsah, styl, doplnok) { f, e -> subor = f; chyba = e; hotovo.countDown() }
        }
        val dokoncene = hotovo.await(30, TimeUnit.MINUTES)
        styl.delete()
        if (!dokoncene) { odpovedz(x, 504, "Trvalo to príliš dlho"); return }
        val f = subor ?: run { odpovedz(x, 500, chyba.ifBlank { T("styl_nepodarilo") }); return }
        posliSubor(x, f, "image/png")
    }
}

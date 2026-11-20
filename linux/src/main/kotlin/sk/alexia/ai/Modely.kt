package sk.alexia.ai

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class OdporucanyModel(val nazov: String, val popis: String, val url: String, val mb: Int, val ram: String = "")

/** Modely GGUF v priečinku appky (~/.local/share/AlexiaAI/modely). */
object Modely {
    val odporucane get() = Katalog.text

    fun priecinok() = File(Platforma.priecinok, "modely").apply { mkdirs() }

    fun zoznam(): List<File> =
        priecinok().listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun bezpecneMeno(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").let {
        if (it.endsWith(".gguf", ignoreCase = true) || it.endsWith(".safetensors", ignoreCase = true)) it else "$it.gguf"
    }

    /** GGUF má hlavičku "GGUF"; safetensors (len pre obrázkové modely) začína dĺžkou hlavičky a '{' */
    private fun platny(f: File, ajSafetensors: Boolean): Boolean {
        if (f.length() < 1024) return false
        val b = f.inputStream().use { i -> ByteArray(9).also { i.read(it) } }
        if (b.copyOfRange(0, 4).contentEquals("GGUF".toByteArray())) return true
        return ajSafetensors && f.name.removeSuffix(".part").endsWith(".safetensors", true) && b[8] == '{'.code.toByte()
    }

    /** skopíruje model zo súboru, ktorý si používateľ vybral */
    fun importuj(zdroj: File, priebeh: (Float) -> Unit, kam: File = priecinok(), ajSafetensors: Boolean = false): File {
        val ciel = File(kam, bezpecneMeno(zdroj.name)); val tmp = File(ciel.path + ".part")
        zdroj.inputStream().use { i -> tmp.outputStream().use { o -> kopiruj(i, o, zdroj.length(), priebeh) } }
        if (!platny(tmp, ajSafetensors)) { tmp.delete(); throw IllegalStateException("Súbor nie je podporovaný model") }
        ciel.delete(); tmp.renameTo(ciel)
        return ciel
    }

    /** stiahne model z odkazu (Hugging Face presmeruje na iný server – presmerovania riešime sami) */
    fun stiahni(odkazPouzivatela: String, priebeh: (Float) -> Unit, zrusene: () -> Boolean, kam: File = priecinok(), ajSafetensors: Boolean = false): File {
        val odkaz = upravOdkaz(odkazPouzivatela)
        var url = URL(odkaz.trim()); var k: HttpURLConnection; var skoky = 0
        while (true) {
            k = url.openConnection() as HttpURLConnection
            k.instanceFollowRedirects = false; k.connectTimeout = 20_000; k.readTimeout = 60_000
            k.setRequestProperty("User-Agent", "AlexiaAI (Linux)")
            val kod = k.responseCode
            if (kod in 300..399 && skoky++ < 8) { url = URL(url, k.getHeaderField("Location")); k.disconnect(); continue }
            if (kod != 200) throw IllegalStateException("Server vrátil $kod")
            break
        }
        val meno = posledny(url.toString()) ?: posledny(odkaz) ?: "model.gguf"
        val povodne = posledny(odkaz)
        val ok = { n: String? -> n != null && (n.endsWith(".gguf", true) || n.endsWith(".safetensors", true)) }
        val ciel = File(kam, bezpecneMeno(if (ok(povodne)) povodne!! else meno))
        val tmp = File(ciel.path + ".part")
        val spolu = k.contentLengthLong
        k.inputStream.use { i -> tmp.outputStream().use { o -> kopiruj(i, o, spolu, priebeh, zrusene) } }
        if (zrusene()) { tmp.delete(); throw InterruptedException("Zrušené") }
        if ((spolu > 0 && tmp.length() != spolu) || !platny(tmp, ajSafetensors)) { tmp.delete(); throw IllegalStateException("Stiahnutý súbor nie je celý podporovaný model") }
        ciel.delete(); tmp.renameTo(ciel)
        return ciel
    }

    /**
     * Odkaz z Hugging Face prerobí na priamy súbor:
     *  …/blob/main/x.gguf → …/resolve/main/x.gguf, odstráni ?download=true,
     *  odkaz na celý repozitár → vyberie GGUF (najradšej Q4_K_M, potom Q4_0, inak najmenší).
     */
    fun upravOdkaz(o: String): String {
        var u = o.trim().substringBefore('?').substringBefore('#')
        if (!u.startsWith("http")) u = "https://$u"
        u = u.replace("://hf.co/", "://huggingface.co/")
        if (!u.contains("huggingface.co")) return if (o.trim().startsWith("http")) o.trim() else u
        u = u.replace("/blob/", "/resolve/")
        if (u.contains("/resolve/")) return u
        val m = Regex("huggingface\\.co/([^/]+)/([^/]+)").find(u) ?: return u
        val repo = "${m.groupValues[1]}/${m.groupValues[2]}"
        val k = URL("https://huggingface.co/api/models/$repo/tree/main?recursive=true").openConnection() as HttpURLConnection
        k.setRequestProperty("User-Agent", "AlexiaAI (Linux)"); k.connectTimeout = 20_000; k.readTimeout = 30_000
        val a = org.json.JSONArray(k.inputStream.bufferedReader().readText())
        val ggufy = (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("type") == "file" && it.optString("path").endsWith(".gguf", true) && !it.optString("path").contains("mmproj", true) }
            .map { it.optString("path") to it.optLong("size") }
            // viac-dielne modely (-00001-of-00003) appka nevie spojiť
            .filter { !Regex("-\\d{5}-of-\\d{5}").containsMatchIn(it.first) }
        if (ggufy.isEmpty()) throw IllegalStateException("V repozitári $repo nie je žiadny GGUF model")
        val vyber = ggufy.firstOrNull { it.first.contains("Q4_K_M", true) } ?: ggufy.firstOrNull { it.first.contains("Q4_0", true) } ?: ggufy.minBy { it.second }
        return "https://huggingface.co/$repo/resolve/main/${vyber.first}"
    }

    private fun kopiruj(i: java.io.InputStream, o: java.io.OutputStream, spolu: Long, priebeh: (Float) -> Unit, zrusene: () -> Boolean = { false }) {
        val b = ByteArray(1 shl 16); var n: Int; var h = 0L; var posledne = 0L
        while (i.read(b).also { n = it } > 0) {
            if (zrusene()) return
            o.write(b, 0, n); h += n
            if (spolu > 0 && h - posledne > (1 shl 20)) { posledne = h; priebeh(h.toFloat() / spolu) }
        }
        priebeh(1f)
    }

    fun posledny(u: String): String? = runCatching { java.net.URI(u.trim()).path.substringAfterLast('/') }.getOrNull()?.takeIf { it.isNotBlank() }

    fun velkost(f: File): String = f.length().let { if (it > 1_000_000_000) "%.2f GB".format(it / 1e9) else "%d MB".format(it / 1_000_000) }
}

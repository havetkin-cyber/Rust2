package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Dabing videa (PC / Linux): odkaz (YouTube…) alebo súbor → prepis reči (Whisper) → preklad do slovenčiny / češtiny
 * (chatový model) → AI hlas (Piper Jirka / Lili, Supertonic) načasovaný podľa pôvodných replík →
 * pôvodný zvuk ostane v pozadí (pri reči sa stíši) + titulky (.srt aj v MP4).
 * Hlasy skutočných ľudí sa nenapodobňujú – dabuje vždy zvolený AI hlas.
 */
object Dabing {
    var pracuje by mutableStateOf(false); private set
    var cinnost by mutableStateOf(""); private set
    var priebeh by mutableFloatStateOf(0f); private set
    var zaciatok by mutableStateOf(0L); private set
    var posledneTrvanie by mutableStateOf(0L); private set
    @Volatile private var zrusit = false
    @Volatile private var proces: Process? = null

    fun zrus() { zrusit = true; proces?.destroy() }

    private const val SR = 16000           // Whisper
    private const val SR_DAB = 24000       // výsledná stopa dabingu

    private class Replika(var od: Double, var po: Double, var text: String = "", var preklad: String = "")

    /**
     * [zdroj] = odkaz (http…) alebo cesta k súboru, [jazyk] = "sk" / "cs", [hlas] = id hlasu (jirka, lili, st0…),
     * [pozadie] = ponechať pôvodný zvuk (hudba, efekty) stíšený pod dabingom
     */
    fun spusti(zdroj: String, jazyk: String, hlas: String, pozadie: Boolean, hotovo: (File?, String) -> Unit) {
        if (pracuje) return
        pracuje = true; zrusit = false; priebeh = 0f; cinnost = T("dab_pripravujem")
        val start = System.currentTimeMillis(); zaciatok = start
        Thread {
            val tmp = File(Platforma.priecinok, "dabing-tmp/$start").apply { mkdirs() }
            var vysledok: File? = null; var chyba = ""
            runCatching {
                fun krok(p: Float, t: String) { if (zrusit) throw IllegalStateException(T("klip_zrusene")); Platforma.naHlavnom { priebeh = p; cinnost = t } }
                // 0) nástroje a modely
                val ff = VideoJadro.ffmpeg() ?: run {
                    val l = java.util.concurrent.CountDownLatch(1)
                    Platforma.naHlavnom { VideoJadro.stiahniFfmpeg { l.countDown() } }; l.await()
                    VideoJadro.ffmpeg() ?: throw IllegalStateException(T("nahovor_ffmpeg"))
                }
                // Whisper large-v3-turbo (~1 GB) – presnejší prepis ako malý model v chate
                if (!Rec.mamTurbo()) {
                    krok(0.01f, T("dab_whisper"))
                    Rec.stiahniTurbo { p -> Platforma.naHlavnom { priebeh = 0.01f + p * 0.04f; cinnost = T("dab_whisper") + " " + (p * 100).toInt() + " %" } }
                }
                if (!Hlas.mamDabingHlas(hlas)) { krok(0.02f, T("dab_hlas")); Hlas.stiahniDabingHlas(hlas) { p -> Platforma.naHlavnom { priebeh = 0.02f + p * 0.03f } } }

                // 1) video
                val video: File = if (zdroj.startsWith("http", ignoreCase = true)) {
                    krok(0.05f, T("dab_stahujem"))
                    stiahniVideo(zdroj, tmp, ff) { p -> Platforma.naHlavnom { priebeh = 0.05f + p * 0.1f } }
                } else File(zdroj).takeIf { it.exists() } ?: throw IllegalStateException(T("dab_ziadne_video"))
                val nazov = video.nameWithoutExtension.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(60)

                // 2) zvuk pre Whisper + úseky reči (ticho medzi replikami)
                krok(0.16f, T("dab_hladam_rec"))
                val pcm = File(tmp, "rec.f32")
                prikaz(listOf(ff, "-y", "-v", "error", "-i", video.path, "-vn", "-ac", "1", "-ar", "$SR", "-f", "f32le", pcm.path))
                val zvuk = citajF32(pcm); pcm.delete()
                val dlzka = zvuk.size.toDouble() / SR
                if (dlzka < 1) throw IllegalStateException(T("dab_bez_zvuku"))
                val repliky = useky(ff, video, dlzka)

                // 3) prepis
                repliky.forEachIndexed { i, r ->
                    krok(0.18f + 0.3f * i / repliky.size, T("dab_prepis", i + 1, repliky.size))
                    val a = (r.od * SR).toInt().coerceIn(0, zvuk.size); val b = (r.po * SR).toInt().coerceIn(a, zvuk.size)
                    r.text = runCatching { Rec.prepis(zvuk.copyOfRange(a, b), SR, turbo = true) }.getOrDefault("")
                        .replace(Regex("\\[[^]]*]|\\([^)]*\\)|♪"), "").trim()
                }
                val reci = repliky.filter { it.text.length >= 2 }
                if (reci.isEmpty()) throw IllegalStateException(T("dab_bez_reci"))

                // 4) preklad (po dávkach, číslované riadky)
                krok(0.5f, T("dab_prekladam", 0, reci.size))
                if (!ChatJadro.cakajNaModel()) throw IllegalStateException(T("dab_treba_chat"))
                prelozVsetko(reci, jazyk) { hotovych -> krok(0.5f + 0.2f * hotovych / reci.size, T("dab_prekladam", hotovych, reci.size)) }
                Platforma.naHlavnom { ChatJadro.uvolniPamat() }

                // 5) dabing: každá replika na svoje miesto, ak je dlhšia ako miesto, zrýchli sa (najviac 1,6×)
                val stopa = FloatArray((dlzka * SR_DAB).toInt() + SR_DAB)
                reci.forEachIndexed { i, r ->
                    krok(0.7f + 0.22f * i / reci.size, T("dab_hovorim", i + 1, reci.size))
                    if (r.preklad.isBlank()) return@forEachIndexed
                    val dalsi = reci.getOrNull(i + 1)?.od ?: dlzka
                    val miesto = (dalsi - r.od - 0.08).coerceAtLeast(0.4)
                    var (x, sr) = Hlas.syntetizuj(Hlas.naCitanie(r.preklad), hlas, 1f, jazyk)
                    val trvanie = x.size.toDouble() / sr
                    if (trvanie > miesto) {
                        val k = (trvanie / miesto).coerceAtMost(1.6).toFloat()
                        val y = Hlas.syntetizuj(Hlas.naCitanie(r.preklad), hlas, k, jazyk); x = y.first; sr = y.second
                    }
                    val res = prevzorkuj(x, sr, SR_DAB)
                    val od = (r.od * SR_DAB).toInt()
                    for (j in res.indices) { val k = od + j; if (k < stopa.size) stopa[k] += res[j] * 0.95f }
                }
                val dabWav = File(tmp, "dabing.wav"); zapisWav(dabWav, stopa, SR_DAB)

                // 6) titulky + spojenie
                krok(0.93f, T("dab_spajam"))
                val srt = File(tmp, "titulky.srt").apply { writeText(srt(reci)) }
                val out = File(VideoJadro.priecinok().apply { mkdirs() }, "dabing_${jazyk}_${nazov}_${System.currentTimeMillis() / 1000}.mp4")
                val filter = if (pozadie)
                    "[1:a]aresample=48000,asplit=2[d][sc];[0:a]aresample=48000[p];[p][sc]sidechaincompress=threshold=0.015:ratio=14:attack=15:release=450[bg];[bg][d]amix=inputs=2:duration=first:normalize=0[a]"
                else "[1:a]aresample=48000[a]"
                prikaz(listOf(ff, "-y", "-v", "error", "-i", video.path, "-i", dabWav.path, "-i", srt.path,
                    "-filter_complex", filter, "-map", "0:v:0", "-map", "[a]", "-map", "2:s:0",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-c:s", "mov_text",
                    "-metadata:s:a:0", "language=" + (if (jazyk == "cs") "ces" else "slk"),
                    "-metadata:s:s:0", "language=" + (if (jazyk == "cs") "ces" else "slk"),
                    "-movflags", "+faststart", out.path))
                srt.copyTo(File(out.path.removeSuffix(".mp4") + ".srt"), overwrite = true)
                File(out.path + ".json").writeText(org.json.JSONObject().put("typ", "dabing").put("zdroj", zdroj).put("jazyk", jazyk)
                    .put("hlas", hlas).put("replik", reci.size).put("trvanie", System.currentTimeMillis() - start).toString())
                vysledok = out
            }.onFailure { chyba = it.message ?: T("dab_chyba") }
            tmp.deleteRecursively()
            val t = System.currentTimeMillis() - start
            Platforma.naHlavnom { pracuje = false; priebeh = 0f; cinnost = ""; proces = null; posledneTrvanie = t; hotovo(vysledok, chyba) }
        }.apply { isDaemon = true }.start()
    }

    // ---------------- úseky reči ----------------

    /** ticho (ffmpeg silencedetect na pásme reči) → úseky reči; dlhé sa delia na ≤ 20 s (Whisper berie 30 s) */
    private fun useky(ff: String, video: File, dlzka: Double): List<Replika> {
        val p = ProcessBuilder(ff, "-v", "info", "-i", video.path, "-vn", "-ac", "1",
            "-af", "highpass=f=200,lowpass=f=3500,silencedetect=noise=-30dB:d=0.35", "-f", "null", "-").redirectErrorStream(true).start()
        proces = p
        val log = p.inputStream.bufferedReader().readText(); p.waitFor()
        val ticha = mutableListOf<Pair<Double, Double>>()
        var od: Double? = null
        for (l in log.lines()) {
            Regex("silence_start: (-?[0-9.]+)").find(l)?.let { od = it.groupValues[1].toDouble().coerceAtLeast(0.0) }
            Regex("silence_end: ([0-9.]+)").find(l)?.let { m -> ticha += (od ?: 0.0) to m.groupValues[1].toDouble(); od = null }
        }
        od?.let { ticha += it to dlzka }
        val rec = mutableListOf<Replika>()
        var t = 0.0
        for ((a, b) in ticha) { if (a - t > 0.05) rec += Replika(t, a); t = b }
        if (dlzka - t > 0.05) rec += Replika(t, dlzka)
        // spojiť blízke (medzera < 0,3 s), zahodiť krátke, rozdeliť dlhé
        val spojene = mutableListOf<Replika>()
        for (r in rec) { val p0 = spojene.lastOrNull(); if (p0 != null && r.od - p0.po < 0.3 && r.po - p0.od < 15) p0.po = r.po else spojene += r }
        val out = mutableListOf<Replika>()
        for (r in spojene.filter { it.po - it.od >= 0.4 }) {
            var a = r.od
            while (r.po - a > 20) { out += Replika(a, a + 15); a += 15 }
            out += Replika(maxOf(0.0, a - 0.1), minOf(dlzka, r.po + 0.15))
        }
        return out
    }

    // ---------------- preklad ----------------

    private fun prelozVsetko(reci: List<Replika>, jazyk: String, priebeh: (Int) -> Unit) {
        val jazykMeno = if (jazyk == "cs") "Czech" else "Slovak"
        val sys = "You are a professional film dubbing translator. Translate each numbered line into natural, spoken $jazykMeno " +
            "for dubbing: short, colloquial, similar length to the original, keep the meaning and tone, no explanations. " +
            "Return exactly the same numbering, one line per number, in the format N|translation."
        var hotovych = 0
        for (davka in reci.chunked(12)) {
            val vstup = davka.mapIndexed { i, r -> "${i + 1}|${r.text}" }.joinToString("\n")
            val sb = StringBuilder()
            ChatJadro.generujPreSiet(listOf("system" to sys, "user" to vstup), 1200) { b -> sb.append(String(b, Charsets.UTF_8)); !zrusit }
            val riadky = ChatJadro.ocistiPrompt(sb.toString()).lines()
            for (l in riadky) {
                val m = Regex("^\\s*(\\d+)\\s*[|:.)-]\\s*(.+)$").find(l) ?: continue
                val i = m.groupValues[1].toInt() - 1
                if (i in davka.indices) davka[i].preklad = m.groupValues[2].trim()
            }
            // čo sa nepreložilo v dávke, skúsi sa po jednom
            for (r in davka.filter { it.preklad.isBlank() }) {
                val s1 = StringBuilder()
                ChatJadro.generujPreSiet(listOf("system" to "Translate into natural spoken $jazykMeno for film dubbing. Reply with the translation only.", "user" to r.text), 200) { b -> s1.append(String(b, Charsets.UTF_8)); !zrusit }
                r.preklad = ChatJadro.ocistiPrompt(s1.toString()).lines().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            }
            hotovych += davka.size; priebeh(hotovych)
        }
    }

    // ---------------- pomocné ----------------

    private fun ytDlp(): File = File(Platforma.priecinok, "ytdlp/" + if (Platforma.jeWindows) "yt-dlp.exe" else "yt-dlp").apply { parentFile.mkdirs() }

    private fun stiahniVideo(url: String, tmp: File, ff: String, p: (Float) -> Unit): File {
        val y = ytDlp()
        if (!y.exists() || System.currentTimeMillis() - y.lastModified() > 7L * 24 * 3600 * 1000) {
            // vždy najnovší yt-dlp (YouTube sa často mení), raz za týždeň
            val odkaz = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/" + if (Platforma.jeWindows) "yt-dlp.exe" else "yt-dlp_linux"
            val t = File(y.path + ".part")
            Hlas.stiahniSubor(odkaz, t) {}
            y.delete(); t.renameTo(y); y.setExecutable(true)
        }
        val ffDir = File(ff).parentFile.path
        val pr = ProcessBuilder(y.path, "--no-playlist", "--newline", "-f", "bv*[height<=1080]+ba/b[height<=1080]/b",
            "--merge-output-format", "mp4", "--ffmpeg-location", ffDir, "-o", File(tmp, "zdroj.%(ext)s").path, url)
            .redirectErrorStream(true).start()
        proces = pr
        val log = StringBuilder()
        pr.inputStream.bufferedReader().forEachLine { l ->
            log.appendLine(l)
            Regex("\\[download]\\s+([0-9.]+)%").find(l)?.let { p(it.groupValues[1].toFloat() / 100f) }
        }
        pr.waitFor()
        if (zrusit) throw IllegalStateException(T("klip_zrusene"))
        return tmp.listFiles { f -> f.name.startsWith("zdroj.") && f.extension in setOf("mp4", "mkv", "webm") }?.firstOrNull()
            ?: throw IllegalStateException(T("dab_stahovanie_chyba") + " " + log.lines().lastOrNull { it.contains("ERROR") }.orEmpty())
    }

    private fun prikaz(p: List<String>) {
        val pr = ProcessBuilder(p).redirectErrorStream(true).start()
        proces = pr
        val v = pr.inputStream.bufferedReader().readText()
        val k = pr.waitFor()
        if (zrusit) throw IllegalStateException(T("klip_zrusene"))
        if (k != 0) throw IllegalStateException("ffmpeg: " + v.lines().lastOrNull { it.isNotBlank() }.orEmpty())
    }

    private fun citajF32(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun prevzorkuj(x: FloatArray, z: Int, na: Int): FloatArray {
        if (z == na) return x
        val n = (x.size.toLong() * na / z).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() * z / na; val a = t.toInt(); val f = (t - a).toFloat()
            val v0 = x.getOrElse(a) { 0f }; val v1 = x.getOrElse(a + 1) { v0 }
            v0 + (v1 - v0) * f
        }
    }

    private fun zapisWav(f: File, x: FloatArray, sr: Int) {
        val pcm = ByteArray(x.size * 2)
        for (i in x.indices) { val v = (x[i].coerceIn(-1f, 1f) * 32767).roundToInt(); pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte() }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        f.outputStream().use { it.write(h.array()); it.write(pcm) }
    }

    private fun srt(r: List<Replika>): String {
        fun cas(s: Double): String { val ms = (s * 1000).toLong(); return "%02d:%02d:%02d,%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000) }
        return r.filter { it.preklad.isNotBlank() }.mapIndexed { i, x -> "${i + 1}\n${cas(x.od)} --> ${cas(x.po)}\n${x.preklad}\n" }.joinToString("\n")
    }
}

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

    private class Replika(var od: Double, var po: Double, var text: String = "", var preklad: String = "", var muz: Boolean? = null,
                          var hovoriaci: Int = -1, var db: Double = -30.0, var f0: Double? = null, var emocia: String = "neutral")

    /** prednes podľa emócie: rýchlosť, výška, hlasitosť, Piper noise_scale / noise_w (živosť intonácie) */
    private data class Prednes(val rychlost: Float, val vyska: Float, val zisk: Float, val sum: Float?, val sumW: Float?)
    private val PREDNES = mapOf(
        "neutral" to Prednes(1f, 1f, 1f, null, null),
        "happy" to Prednes(1.05f, 1.03f, 1.05f, 0.75f, 0.9f),
        "excited" to Prednes(1.1f, 1.05f, 1.15f, 0.8f, 1f),
        "sad" to Prednes(0.9f, 0.97f, 0.85f, 0.55f, 0.6f),
        "angry" to Prednes(1.05f, 0.98f, 1.25f, 0.8f, 0.9f),
        "scared" to Prednes(1.08f, 1.04f, 0.95f, 0.85f, 1f),
        "whisper" to Prednes(0.95f, 1f, 0.55f, 0.5f, 0.5f),
        "shout" to Prednes(1.05f, 1.03f, 1.35f, 0.85f, 1f),
    )
    /** citoslovcia bez prekladu (smiech, vzdych…) – nechá sa pôvodný zvuk z Demucsu */
    private val CITOSLOVCIA = Regex("^(?:\\s|[.,!?…-])*(?:(?:ha|he|hi|ah|aah|oh|ooh|uh|um|umm|hmm|mm|mhm|wow|whoa|ow|ouch|eh|huh|oops|yay|ugh|aw|aww|ahh|ohh|uhh|hm)+(?:\\s|[.,!?…-])*)+$", RegexOption.IGNORE_CASE)

    /** hlas postavy: Piper/Supertonic id + posun výšky (1 = bez zmeny) */
    private data class Hlasovka(val id: String, val vyska: Float)

    /**
     * [zdroj] = odkaz (http…) alebo cesta k súboru, [jazyk] = "sk" / "cs", [hlas] = id hlasu (jirka, lili, st0…),
     * [pozadie] = ponechať pôvodný zvuk (hudba, efekty) stíšený pod dabingom
     */
    fun spusti(zdroj: String, jazyk: String, hlas: String, pozadie: Boolean, hotovo: (File?, String) -> Unit) =
        spusti(zdroj, jazyk, hlas, null, pozadie, hotovo)

    /** [hlasMuz] != null → dva hlasy: muž/žena podľa výšky pôvodného hlasu v každej replike ([hlas] = ženský) */
    fun spusti(zdroj: String, jazyk: String, hlas: String, hlasMuz: String?, pozadie: Boolean, hotovo: (File?, String) -> Unit) =
        spusti(zdroj, jazyk, hlas, hlasMuz, pozadie, false, hotovo)

    /** [pery] = experimentálne prekreslenie úst hlavnej postavy podľa dabingu (Wav2Lip) */
    fun spusti(zdroj: String, jazyk: String, hlas: String, hlasMuz: String?, pozadie: Boolean, pery: Boolean, hotovo: (File?, String) -> Unit) {
        if (pracuje) return
        val postavy = hlasMuz != null
        pracuje = true; zrusit = false; priebeh = 0f; cinnost = T("dab_pripravujem")
        val start = System.currentTimeMillis(); zaciatok = start
        Thread {
            val tmp = File(Platforma.priecinok, "dabing-tmp/$start").apply { mkdirs() }
            var vysledok: File? = null; var chyba = ""
            runCatching {
                fun krok(p: Float, t: String) { if (zrusit) throw IllegalStateException(T("klip_zrusene")); println("dabing: $t"); Platforma.naHlavnom { priebeh = p; cinnost = t } }
                // grafika aj RAM pre dabing (na Legione je pamäť spoločná – obrázkový model by zabral miesto Whisperu)
                Platforma.naHlavnom { runCatching { ObrazkyJadro.uvolniPamat() }; runCatching { ChatJadro.uvolniPamat() } }
                // 0) nástroje a modely
                val ff = VideoJadro.ffmpeg() ?: run {
                    val l = java.util.concurrent.CountDownLatch(1)
                    Platforma.naHlavnom { VideoJadro.stiahniFfmpeg { l.countDown() } }; l.await()
                    VideoJadro.ffmpeg() ?: throw IllegalStateException(T("nahovor_ffmpeg"))
                }
                // Whisper large-v3-turbo (~1 GB) – presnejší prepis ako malý model v chate
                if (!Rec.mamTurbo()) {
                    krok(0.01f, T("dab_whisper"))
                    runCatching { Rec.stiahniTurbo { p -> Platforma.naHlavnom { priebeh = 0.01f + p * 0.04f; cinnost = T("dab_whisper") + " " + (p * 100).toInt() + " %" } } }
                        .onFailure { println("dabing: Whisper turbo sa nestiahol: ${it.message}"); if (!Rec.mam()) throw IllegalStateException(T("dab_whisper_chyba") + " – " + it.message) }
                }
                for (h in listOfNotNull(hlas, hlasMuz).distinct())
                    if (!Hlas.mamDabingHlas(h)) { krok(0.02f, T("dab_hlas")); Hlas.stiahniDabingHlas(h) { p -> Platforma.naHlavnom { priebeh = 0.02f + p * 0.03f } } }

                // 1) video
                val video: File = if (zdroj.startsWith("http", ignoreCase = true)) {
                    krok(0.05f, T("dab_stahujem"))
                    stiahniVideo(zdroj, tmp, ff) { p -> Platforma.naHlavnom { priebeh = 0.05f + p * 0.1f } }
                } else File(zdroj).takeIf { it.exists() } ?: throw IllegalStateException(T("dab_ziadne_video"))
                val nazov = video.nameWithoutExtension.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(60)

                // 2) zvuk: AI oddelenie hlasov od hudby (Demucs) – čisté pozadie (hudba, efekty) a čisté hlasy pre Whisper
                val orig = File(tmp, "orig.s16")
                prikaz(listOf(ff, "-y", "-v", "error", "-i", video.path, "-vn", "-ac", "2", "-ar", "44100", "-f", "s16le", orig.path))
                if (orig.length() < 44100L * 4) throw IllegalStateException(T("dab_bez_zvuku"))
                val bgF = File(tmp, "pozadie.s16"); val vocF = File(tmp, "hlasy.s16")
                var oddelene = false
                runCatching {
                    val m = demucsModel { p -> Platforma.naHlavnom { priebeh = 0.15f; cinnost = T("dab_demucs", (p * 100).toInt()) } }
                    krok(0.16f, T("dab_oddelujem", 0, 1))
                    val p = ProcessBuilder(Videoklip.pomocnyProces("sk.alexia.ai.DemucsProces", "--demucs",
                        listOf(m.path, orig.path, bgF.path, vocF.path))).redirectErrorStream(true).start()
                    proces = p
                    p.inputStream.bufferedReader().forEachLine { l ->
                        val mm = Regex("^P (\\d+) (\\d+)$").find(l.trim())
                        if (mm != null) { val i = mm.groupValues[1].toInt(); val n = maxOf(1, mm.groupValues[2].toInt()); krok(0.16f + 0.14f * i / n, T("dab_oddelujem", i, n)) }
                        else println("demucs: $l")
                    }
                    val kod = p.waitFor()
                    if (zrusit) throw IllegalStateException(T("klip_zrusene"))
                    oddelene = kod == 0 && bgF.length() > 0 && vocF.length() > 0
                    if (!oddelene) println("dabing: oddelenie hlasov zlyhalo (kód $kod) – pokračujem s pôvodným zvukom")
                }.onFailure { if (zrusit) throw it; println("dabing: oddelenie hlasov nejde: ${it.message}") }
                val zdrojReci = if (oddelene) vocF else orig
                val surove = listOf("-f", "s16le", "-ar", "44100", "-ac", "2", "-i", zdrojReci.path)

                krok(0.3f, T("dab_hladam_rec"))
                val zvukF = File(tmp, "rec.f32")
                prikaz(listOf(ff, "-y", "-v", "error") + surove + listOf("-ac", "1", "-ar", "$SR", "-f", "f32le", zvukF.path))
                val zvuk = citajF32(zvukF)
                val dlzka = zvuk.size.toDouble() / SR
                if (dlzka < 1) throw IllegalStateException(T("dab_bez_zvuku"))
                val repliky = useky(ff, surove, dlzka)
                // hlasitosť a výška hlasu každej repliky (emócie: krik / šepot, rozrušenie)
                repliky.forEach { r ->
                    val a = (r.od * SR).toInt().coerceIn(0, zvuk.size); val b = (r.po * SR).toInt().coerceIn(a, zvuk.size)
                    var e = 0.0; for (k in a until b) e += zvuk[k] * zvuk[k]
                    r.db = 10 * Math.log10(e / maxOf(1, b - a) + 1e-10)
                    if (postavy) r.f0 = vyskaHlasu(zvuk, a, b)
                }

                // modely na rozpoznanie hovoriacich (kto je kto) – len pre „rôzne hlasy pre postavy“
                val diar = if (postavy) runCatching { krok(0.31f, T("dab_hovoriaci_model")); diarModely() }.onFailure { println("dabing: modely hovoriacich: ${it.message}") }.getOrNull() else null

                // 3) prepis – v samostatnom procese (pád Whisperu nezhodí appku), najprv turbo, pri chybe malý model
                val usekyF = File(tmp, "useky.txt").apply { writeText(repliky.joinToString("\n") { "${it.od} ${it.po}" }) }
                val moznosti = buildList {
                    if (Rec.mamTurbo()) add(Rec.dirTurboVerejne() to "turbo")
                    if (Rec.mam()) add(Rec.dirSmall() to "small")
                }
                var hotovo = false; var posledna = ""; var hovF: File? = null
                for ((dir, pred) in moznosti) {
                    val vystupF = File(tmp, "prepis-$pred.txt")
                    val argy = listOf(Hlas.sherpaDir().path, dir.path, pred, zvukF.path, usekyF.path, vystupF.path) +
                        (diar?.let { listOf(it.first.path, it.second.path) } ?: emptyList())
                    val p = ProcessBuilder(Videoklip.pomocnyProces("sk.alexia.ai.PrepisProces", "--prepis", argy)).redirectErrorStream(true).start()
                    proces = p
                    p.inputStream.bufferedReader().forEachLine { l ->
                        val m = Regex("^P (\\d+) (\\d+)$").find(l.trim())
                        if (m != null) { val i = m.groupValues[1].toInt(); val n = m.groupValues[2].toInt(); krok(0.32f + 0.18f * i / n, T("dab_prepis", i + 1, n)) }
                        else { println("prepis: $l"); if (l.contains("rozpoznávam hovoriacich")) Platforma.naHlavnom { cinnost = T("dab_hovoriaci") } }
                    }
                    val kod = p.waitFor()
                    if (zrusit) throw IllegalStateException(T("klip_zrusene"))
                    vystupF.takeIf { it.exists() }?.readLines()?.forEach { l ->
                        val i = l.substringBefore('\t').toIntOrNull() ?: return@forEach
                        repliky.getOrNull(i)?.text = cistiPrepis(l.substringAfter('\t'))
                    }
                    File(vystupF.path + ".hov").takeIf { it.exists() && it.length() > 0 }?.let { hovF = it }
                    if (kod == 0) { hotovo = true; break }
                    posledna = "Whisper $pred skončil s kódom $kod"
                    println("dabing: $posledna – skúšam ďalší model")
                }
                if (!hotovo) throw IllegalStateException(posledna.ifBlank { T("dab_whisper_chyba") })
                // neverbálne zvuky (smiech, vzdych, výkrik bez slov): nedabujú sa – ostane pôvodný hlas z Demucsu
                val medDbVsetky = repliky.map { it.db }.sorted().let { if (it.isEmpty()) -30.0 else it[it.size / 2] }
                val neverbalne = if (oddelene && pozadie) repliky.filter { r ->
                    (r.text.isBlank() || CITOSLOVCIA.matches(r.text)) && r.db > medDbVsetky - 8 && r.po - r.od >= 0.25
                } else emptyList()
                println("dabing: neverbálnych úsekov ${neverbalne.size}")
                val reci = repliky.filter { it.text.length >= 2 && !CITOSLOVCIA.matches(it.text) }
                if (reci.isEmpty()) throw IllegalStateException(T("dab_bez_reci"))

                // kto hovorí: podľa rozpoznania hovoriacich, inak aspoň muž/žena podľa výšky hlasu
                hovF?.readLines()?.mapNotNull { l -> l.split(' ').takeIf { it.size >= 3 }?.let { Triple(it[0].toDouble(), it[1].toDouble(), it[2].toInt()) } }?.let { seg ->
                    reci.forEach { r ->
                        r.hovoriaci = seg.groupBy { it.third }.mapValues { (_, l) -> l.sumOf { maxOf(0.0, minOf(it.second, r.po) - maxOf(it.first, r.od)) } }
                            .filter { it.value > 0 }.maxByOrNull { it.value }?.key ?: -1
                    }
                }
                val hlasy = if (postavy) priradHlasy(reci, hlas, hlasMuz ?: "jirka", jazyk) else emptyMap()
                println("dabing: hovoriaci ${reci.map { it.hovoriaci }.distinct()} → ${hlasy.values.distinct()}")

                // 4) preklad (po dávkach, číslované riadky)
                krok(0.5f, T("dab_prekladam", 0, reci.size))
                pripravPrekladac { p -> Platforma.naHlavnom { priebeh = 0.5f; cinnost = T("dab_prekladac", (p * 100).toInt()) } }
                if (!ChatJadro.cakajNaModel()) throw IllegalStateException(T("dab_treba_chat"))
                krok(0.5f, T("dab_kontext"))
                val kontext = runCatching { kontextPrekladu(reci, jazyk) }.onFailure { println("dabing: kontext: ${it.message}") }.getOrDefault("")
                println("dabing: kontext prekladu:\n$kontext")
                prelozVsetko(reci, jazyk, kontext) { hotovych -> krok(0.5f + 0.2f * hotovych / reci.size, T("dab_prekladam", hotovych, reci.size)) }
                Platforma.naHlavnom { ChatJadro.uvolniPamat() }

                // 5) dabing: repliky tej istej postavy sa neprekrývajú; iná postava môže skočiť do reči (max 0,35 s), ako v origináli
                //    každá postava svoj hlas, hlasitosť, výška a prednes podľa originálu a emócie
                val stopa = FloatArray((dlzka * SR_DAB).toInt() + SR_DAB * 30)
                val medianDb = reci.map { it.db }.sorted().let { it[it.size / 2] }
                val medianF0 = reci.groupBy { it.hovoriaci }.mapValues { (_, l) -> l.mapNotNull { it.f0 }.sorted().let { v -> if (v.isEmpty()) null else v[v.size / 2] } }
                var kurzor = 0.0; var predosly = -2
                reci.forEachIndexed { i, r ->
                    krok(0.7f + 0.22f * i / reci.size, T("dab_hovorim", i + 1, reci.size))
                    if (r.preklad.isBlank()) return@forEachIndexed
                    val inaPostava = postavy && r.hovoriaci >= 0 && predosly >= 0 && r.hovoriaci != predosly
                    val start = if (inaPostava) maxOf(r.od, kurzor - 0.35) else maxOf(r.od, kurzor + 0.06)
                    val pr = PREDNES[r.emocia] ?: PREDNES.getValue("neutral")
                    val dalsi = reci.getOrNull(i + 1)?.od ?: (dlzka + 1.0)
                    val miesto = (dalsi - start - 0.06).coerceAtLeast(0.5)
                    val zaklad = hlasy[r.hovoriaci] ?: if (postavy && r.muz == true) Hlasovka(hlasMuz ?: "jirka", 1f) else Hlasovka(hlas, 1f)
                    // rozrušenie: vyšší hlas ako je u postavy bežné → dabing o kúsok vyšší (a naopak)
                    val pomer = r.f0?.let { f -> medianF0[r.hovoriaci]?.let { m -> f / m } } ?: 1.0
                    val vyska = zaklad.vyska * pr.vyska * when { pomer > 1.15 -> 1.04f; pomer < 0.88 -> 0.97f; else -> 1f }
                    var res = hovor(r.preklad, zaklad.id, vyska, pr.rychlost, jazyk, pr.sum, pr.sumW)
                    val trvanie = res.size.toDouble() / SR_DAB
                    if (trvanie > miesto) res = hovor(r.preklad, zaklad.id, vyska, (pr.rychlost * trvanie / miesto).coerceAtMost(1.8).toFloat(), jazyk, pr.sum, pr.sumW)
                    // krik / šepot: hlasitosť podľa originálu (zmiernená) a emócie
                    val zisk = (Math.pow(10.0, (r.db - medianDb) * 0.6 / 20) * pr.zisk).coerceIn(0.45, 1.6).toFloat()
                    val od = (start * SR_DAB).toInt()
                    for (j in res.indices) { val k = od + j; if (k < stopa.size) stopa[k] = jemnyLimit(stopa[k] + res[j] * 0.92f * zisk) }
                    kurzor = maxOf(kurzor, start + res.size.toDouble() / SR_DAB); predosly = r.hovoriaci
                }
                val dabWav = File(tmp, "dabing.wav"); zapisWav(dabWav, stopa, SR_DAB)

                // 6) pery (experimentálne): ústa hlavnej postavy podľa dabingu
                var videoPery: File? = null
                if (pery) runCatching {
                    krok(0.92f, T("dab_pery_modely"))
                    val modely = Videoklip.modelySpevu { p -> Platforma.naHlavnom { priebeh = 0.92f; cinnost = T("dab_pery_modely") + " " + (p * 100).toInt() + " %" } }
                    val dab16 = File(tmp, "dab16.f32"); zapisF32(dab16, prevzorkuj(stopa, SR_DAB, SR))
                    val vp = File(tmp, "pery.mp4")
                    val p = ProcessBuilder(Videoklip.pomocnyProces("sk.alexia.ai.PeryProces", "--pery",
                        listOf(modely.path, video.path, dab16.path, ff, vp.path))).redirectErrorStream(true).start()
                    proces = p
                    p.inputStream.bufferedReader().forEachLine { l ->
                        val m = Regex("^P (\\d+) (\\d+)$").find(l.trim())
                        if (m != null) { val i = m.groupValues[1].toInt(); val n = maxOf(1, m.groupValues[2].toInt()); krok(0.92f + 0.05f * i / n, T("dab_pery", (100 * i / n).coerceAtMost(100))) }
                        else println("pery: $l")
                    }
                    val kod = p.waitFor()
                    if (zrusit) throw IllegalStateException(T("klip_zrusene"))
                    if (kod == 0 && vp.length() > 10_000) videoPery = vp else println("dabing: pery zlyhali (kód $kod) – ostáva pôvodné video")
                }.onFailure { if (zrusit) throw it; println("dabing: pery nejdú: ${it.message}") }

                // 7) titulky + spojenie (+ neverbálne zvuky, vyrovnanie hlasitosti na -16 LUFS)
                krok(0.97f, T("dab_spajam"))
                val srt = File(tmp, "titulky.srt").apply { writeText(srt(reci)) }
                val out = File(VideoJadro.priecinok().apply { mkdirs() }, "dabing_${jazyk}_${nazov}_${System.currentTimeMillis() / 1000}.mp4")
                val jazykMeta = if (jazyk == "cs") "ces" else "slk"
                val vstupy = mutableListOf("-i", (videoPery ?: video).path, "-i", dabWav.path, "-i", srt.path)
                var dalsi = 3
                val origA = if (videoPery != null) { vstupy += listOf("-i", video.path); dalsi++ } else 0
                val nvF = File(tmp, "neverbalne.s16")
                val nvIdx = if (oddelene && pozadie && neverbalne.isNotEmpty() && runCatching { vystrihni(vocF, nvF, neverbalne) }.isSuccess) {
                    vstupy += listOf("-f", "s16le", "-ar", "44100", "-ac", "2", "-i", nvF.path); dalsi++
                } else -1
                val nv = if (nvIdx >= 0) "[$nvIdx:a]aresample=48000,volume=0.9[nv];" else ""
                val nvIn = if (nvIdx >= 0) "[nv]" else ""; val nvN = if (nvIdx >= 0) 1 else 0
                val filter = when {
                    !pozadie -> "[1:a]aresample=48000[m]"
                    oddelene -> {
                        // čisté pozadie z Demucsu (bez reči) – pri dabingu sa len jemne stíši
                        vstupy += listOf("-f", "s16le", "-ar", "44100", "-ac", "2", "-i", bgF.path)
                        nv + "[1:a]aresample=48000,asplit=2[d][sc];[$dalsi:a]aresample=48000[p];[p][sc]sidechaincompress=threshold=0.03:ratio=3:attack=20:release=400[bg];" +
                            "[bg][d]${nvIn}amix=inputs=${2 + nvN}:duration=longest:normalize=0[m]"
                    }
                    // záloha: pôvodné hlasy sú zvyčajne v strede sterea → odčítaním kanálov zmiznú
                    else -> "[1:a]aresample=48000,asplit=2[d][sc];[$origA:a]aresample=48000,aformat=channel_layouts=stereo,pan=stereo|c0=0.7*c0-0.7*c1|c1=0.7*c1-0.7*c0,volume=0.8[p];" +
                        "[p][sc]sidechaincompress=threshold=0.01:ratio=20:attack=10:release=500[bg];[bg][d]amix=inputs=2:duration=first:normalize=0[m]"
                } + ";[m]loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[a]"
                prikaz(listOf(ff, "-y", "-v", "error") + vstupy + listOf(
                    "-filter_complex", filter, "-map", "0:v:0", "-map", "[a]", "-map", "2:s:0",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-c:s", "mov_text", "-shortest",
                    "-metadata:s:a:0", "language=$jazykMeta", "-metadata:s:s:0", "language=$jazykMeta",
                    "-movflags", "+faststart", out.path))
                srt.copyTo(File(out.path.removeSuffix(".mp4") + ".srt"), overwrite = true)
                File(out.path + ".json").writeText(org.json.JSONObject().put("typ", "dabing").put("zdroj", zdroj).put("jazyk", jazyk)
                    .put("hlas", hlas).put("hlas_muz", hlasMuz ?: "").put("pery", videoPery != null).put("replik", reci.size).put("trvanie", System.currentTimeMillis() - start).toString())
                vysledok = out
            }.onFailure { chyba = it.message ?: T("dab_chyba") }
            tmp.deleteRecursively()
            val t = System.currentTimeMillis() - start
            Platforma.naHlavnom { pracuje = false; priebeh = 0f; cinnost = ""; proces = null; posledneTrvanie = t; hotovo(vysledok, chyba) }
        }.apply { isDaemon = true }.start()
    }

    // ---------------- hlasy postáv ----------------

    /**
     * Každý hovoriaci dostane vlastný hlas: pohlavie podľa mediánu výšky hlasu, poradie podľa toho, kto hovorí najviac.
     * Mužom Jirka (a jeho hlbšia / vyššia verzia), ženám Lili, Supertonic (SK) a ich varianty.
     */
    private fun priradHlasy(reci: List<Replika>, zena: String, muz: String, jazyk: String): Map<Int, Hlasovka> {
        val muzske = listOf(Hlasovka(muz, 1f), Hlasovka(muz, 0.9f), Hlasovka(muz, 1.09f), Hlasovka(muz, 0.83f), Hlasovka(muz, 1.16f))
        val zenske = buildList {
            add(Hlasovka(zena, 1f))
            if (jazyk == "sk" && Hlas.mamSt()) listOf("st0", "st1", "st2", "st3", "st4").filter { it != zena }.forEach { add(Hlasovka(it, 1f)) }
            add(Hlasovka(zena, 1.08f)); add(Hlasovka(zena, 0.93f)); add(Hlasovka(zena, 1.15f))
        }
        val skupiny = reci.groupBy { it.hovoriaci }
        val poradie = skupiny.entries.sortedByDescending { (_, l) -> l.sumOf { it.po - it.od } }
        var m = 0; var z = 0
        val vysl = mutableMapOf<Int, Hlasovka>()
        for ((spk, l) in poradie) {
            if (spk < 0) continue
            val f = l.mapNotNull { it.f0 }.sorted()
            val jeMuz = f.isNotEmpty() && f[f.size / 2] < 160.0
            vysl[spk] = if (jeMuz) muzske[m++ % muzske.size] else zenske[z++ % zenske.size]
        }
        // repliky bez hovoriaceho: aspoň muž / žena podľa vlastnej výšky hlasu
        reci.filter { it.hovoriaci < 0 }.forEach { it.muz = it.f0?.let { f -> f < 160.0 } }
        return vysl
    }

    /** syntéza s posunom výšky: TTS rýchlejšie/pomalšie a prehratie r× → výška ×r, dĺžka podľa [rychlost] */
    private fun hovor(text: String, id: String, vyska: Float, rychlost: Float, jazyk: String, sum: Float? = null, sumW: Float? = null): FloatArray {
        val r = vyska.toDouble()
        val (x, sr) = Hlas.syntetizuj(Hlas.naCitanie(text), id, (rychlost / vyska).coerceIn(0.5f, 3f), jazyk, sum, sumW)
        val y = if (kotlin.math.abs(r - 1) < 0.005) x else FloatArray((x.size / r).toInt()) { i ->
            val t = i * r; val a = t.toInt(); val f = (t - a).toFloat()
            val v0 = x.getOrElse(a) { 0f }; val v1 = x.getOrElse(a + 1) { v0 }; v0 + (v1 - v0) * f
        }
        return prevzorkuj(y, sr, SR_DAB)
    }

    /** z hlasovej stopy Demucsu (s16 stereo 44,1 kHz) nechá len neverbálne úseky (s prelínaním), zvyšok ticho */
    private fun vystrihni(voc: File, out: File, useky: List<Replika>) {
        val fade = 0.04 * 44100
        val us = useky.map { (it.od * 44100).toLong() to (it.po * 44100).toLong() }.sortedBy { it.first }
        val buf = ByteArray(1 shl 20)
        var i = 0L; var u = 0
        voc.inputStream().buffered().use { vin -> out.outputStream().buffered().use { vout ->
            while (true) {
                val n = vin.readNBytes(buf, 0, buf.size); if (n <= 0) break
                val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until n / 4) {
                    while (u < us.size && us[u].second <= i) u++
                    var g = 0.0
                    var j = u
                    while (j < us.size && us[j].first <= i) {
                        val (a, e) = us[j]
                        if (i < e) g = maxOf(g, minOf(1.0, (i - a + 1) / fade, (e - i) / fade))
                        j++
                    }
                    if (g < 1.0) {
                        bb.putShort(4 * k, (bb.getShort(4 * k) * g).toInt().toShort())
                        bb.putShort(4 * k + 2, (bb.getShort(4 * k + 2) * g).toInt().toShort())
                    }
                    i++
                }
                vout.write(buf, 0, n - n % 4)
            }
        } }
    }

    private fun jemnyLimit(v: Float): Float = if (v > 0.9f) 0.9f + (v - 0.9f) / (1 + (v - 0.9f) * 10) else if (v < -0.9f) -0.9f + (v + 0.9f) / (1 - (v + 0.9f) * 10) else v

    // ---------------- modely: Demucs a rozpoznanie hovoriacich ----------------

    private const val DEMUCS_URL = "https://github.com/havetkin-cyber/alexia-dj-update/releases/download/ai-v1/htdemucs.onnx"
    private const val DEMUCS_VELKOST = 174264711L
    private fun dirModelov() = File(Platforma.priecinok, "dabing").apply { mkdirs() }

    private fun demucsModel(p: (Float) -> Unit): File {
        val f = File(dirModelov(), "htdemucs.onnx")
        if (f.length() == DEMUCS_VELKOST) return f
        val t = File(f.path + ".part")
        Hlas.stiahniSubor(DEMUCS_URL, t, p)
        if (t.length() != DEMUCS_VELKOST) { t.delete(); throw IllegalStateException("Model Demucs sa nestiahol celý") }
        f.delete(); t.renameTo(f); return f
    }

    /** (segmentácia pyannote, hlasový odtlačok 3D-Speaker) – spolu ~40 MB */
    private fun diarModely(): Pair<File, File> {
        val d = File(dirModelov(), "hovoriaci").apply { mkdirs() }
        val seg = File(d, "model.onnx")
        if (seg.length() < 1_000_000) {
            val arch = File(d, "seg.tar.bz2")
            Hlas.stiahniSubor("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2", arch) {}
            Hlas.rozbalTarBz2(arch, d) { it.endsWith("/model.onnx") || it == "model.onnx" }
            arch.delete()
        }
        val emb = File(d, "3dspeaker_eres2net_base_16k.onnx")
        if (emb.length() < 1_000_000) {
            val t = File(emb.path + ".part")
            Hlas.stiahniSubor("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx", t) {}
            emb.delete(); t.renameTo(emb)
        }
        if (!seg.exists() || emb.length() < 1_000_000) throw IllegalStateException("Modely hovoriacich sa nestiahli")
        return seg to emb
    }

    // ---------------- čistenie prepisu (halucinácie Whispera) ----------------

    private val HALUCINACIE = listOf("thank you for watching", "thanks for watching", "subtitles by", "amara.org", "please subscribe",
        "like and subscribe", "transcribed by", "captions by", "translated by")

    /**
     * Whisper si pri hudbe bez reči (úvod, znelka) vymýšľa – opakuje jedno slovo („more more more…“)
     * alebo vetu typu „Thanks for watching“. Opakovania sa zlúčia, úseky, kde je skoro len jedno slovo, sa zahodia.
     */
    internal fun cistiPrepis(t0: String): String {
        var t = t0.replace(Regex("\\[[^]]*]|\\([^)]*\\)|♪|\\*[^*]*\\*"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return ""
        val male = t.lowercase()
        if (HALUCINACIE.any { male.contains(it) } && t.length < 80) return ""
        val slova = male.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotBlank() }
        if (slova.size >= 4) {
            val naj = slova.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
            if (naj.value >= 4 && naj.value * 2 >= slova.size) return ""        // „more more more more…“
        }
        // opakujúce sa frázy (1–6 slov) za sebou → raz
        for (n in 1..6) t = t.replace(Regex("(?i)\\b((?:[\\p{L}\\p{N}']+[\\s,.!?-]+){$n})(?:\\1){2,}"), "$1")
        return t.trim()
    }

    // ---------------- muž / žena podľa výšky hlasu ----------------

    /**
     * Základná výška hlasu (F0) autokoreláciou v rámcoch 40 ms; medián znelých rámcov.
     * Muži zvyčajne 85–155 Hz, ženy 165–255 Hz → hranica 160 Hz. null = nedá sa určiť (hudba, šepot).
     */
    private fun jeMuz(x: FloatArray, od: Int, po: Int): Boolean? = vyskaHlasu(x, od, po)?.let { it < 160.0 }

    /** medián F0 (Hz) úseku, null = nedá sa určiť; rámce sa berú s krokom 20 ms (rýchlosť) */
    private fun vyskaHlasu(x: FloatArray, od: Int, po: Int): Double? {
        val ram = 640; val krok = 320; val minLag = SR / 400; val maxLag = SR / 70
        val f0 = mutableListOf<Double>()
        var i = od
        while (i + ram + maxLag < po) {
            var e = 0.0
            for (k in 0 until ram) e += x[i + k] * x[i + k]
            if (e / ram > 1e-4) {
                var naj = 0.0; var najLag = 0
                for (lag in minLag..maxLag) {
                    var c = 0.0; var e2 = 0.0
                    for (k in 0 until ram) { c += x[i + k] * x[i + k + lag]; e2 += x[i + k + lag] * x[i + k + lag] }
                    val r = c / Math.sqrt(e * e2 + 1e-12)
                    if (r > naj) { naj = r; najLag = lag }
                }
                if (naj > 0.55 && najLag > 0) f0 += SR.toDouble() / najLag
            }
            i += krok
        }
        if (f0.size < 5) return null
        f0.sort()
        return f0[f0.size / 2]
    }

    // ---------------- úseky reči ----------------

    /** ticho (ffmpeg silencedetect na pásme reči) → úseky reči; dlhé sa delia na ≤ 20 s (Whisper berie 30 s) */
    private fun useky(ff: String, vstup: List<String>, dlzka: Double): List<Replika> {
        val p = ProcessBuilder(listOf(ff, "-v", "info") + vstup + listOf("-vn", "-ac", "1",
            "-af", "highpass=f=200,lowpass=f=3500,silencedetect=noise=-32dB:d=0.3", "-f", "null", "-")).redirectErrorStream(true).start()
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

    private const val PREKLADAC = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"

    /**
     * Ak nie je zvolený chatový model, appka si ho zariadi sama: najprv použije Qwen3 4B, ktorý už leží pri Z-Image,
     * inak stiahne Qwen3-4B-Instruct (Q4_K_M, ~2,5 GB) – vie dobre po česky aj slovensky.
     */
    private fun pripravPrekladac(p: (Float) -> Unit) {
        val n = ChatJadro.nastavenia
        if (n.vzdialene || n.aktivnyModel?.let { File(it).exists() } == true) return
        val existujuci = (ObrazkyJadro.priecinokModelov().walkTopDown() + Modely.priecinok().walkTopDown())
            .firstOrNull { it.isFile && it.name.startsWith("Qwen3-4B-Instruct", ignoreCase = true) && it.extension.equals("gguf", true) && it.length() > 1_500_000_000 }
        val f = existujuci ?: Modely.stiahni(PREKLADAC, p, { zrusit })
        val l = java.util.concurrent.CountDownLatch(1)
        Platforma.naHlavnom { ChatJadro.nacitajModel(f.path); l.countDown() }
        l.await()
    }

    /**
     * Pred prekladom si model prečíta celý prepis: o čom to je, kto sú postavy (pohlavie, tykanie / vykanie)
     * a ako preložiť mená a pojmy – aby boli rovnaké celé video.
     */
    private fun kontextPrekladu(reci: List<Replika>, jazyk: String): String {
        val jazykMeno = if (jazyk == "cs") "Czech" else "Slovak"
        val prepis = StringBuilder()
        for (r in reci) { val l = "${spk(r)}: ${r.text}\n"; if (prepis.length + l.length > 7000) break; prepis.append(l) }
        val sys = "You prepare a film dubbing into $jazykMeno. Read the transcript (speaker labels S1, S2… or ? when unknown) and reply in English, briefly, in exactly this form:\n" +
            "SUMMARY: one or two sentences about what happens\n" +
            "CHARACTERS: one line per speaker label – gender, role, how they address the others in $jazykMeno (informal ty / formal vy)\n" +
            "GLOSSARY: names and key terms → how to write them in $jazykMeno (keep personal names unchanged), separated by semicolons\nNothing else."
        val sb = StringBuilder()
        ChatJadro.generujPreSiet(listOf("system" to sys, "user" to prepis.toString()), 450) { b -> sb.append(String(b, Charsets.UTF_8)); !zrusit }
        return ChatJadro.ocistiPrompt(sb.toString()).trim().take(1800)
    }

    private fun spk(r: Replika) = if (r.hovoriaci >= 0) "S${r.hovoriaci + 1}" else "?"

    private fun prelozVsetko(reci: List<Replika>, jazyk: String, kontext: String, priebeh: (Int) -> Unit) {
        val jazykMeno = if (jazyk == "cs") "Czech" else "Slovak"
        val emocie = PREDNES.keys.joinToString("/")
        val sys = "You are a professional film dubbing translator writing a $jazykMeno dubbing script. Each input line is N|seconds|speaker|text, " +
            "where seconds is how long the original line lasts and speaker is the character label. Translate into natural, spoken, colloquial $jazykMeno that a voice actor can say " +
            "comfortably within that time (about 14 characters per second) – shorten, simplify and rephrase when needed, keep the meaning, tone and emotion. " +
            "Use the correct grammatical gender of the speaker and keep ty / vy, names and terms consistent with the context. " +
            "For every line also pick how it should be acted: one of $emocie. " +
            "Return exactly the same numbering, one line per number, in the format N|emotion|translation, nothing else." +
            (if (kontext.isNotBlank()) "\n\nContext of the whole video:\n$kontext" else "")
        var hotovych = 0
        val hotove = mutableListOf<Replika>()
        for (davka in reci.chunked(12)) {
            val predtym = hotove.takeLast(3).filter { it.preklad.isNotBlank() }
            val uvod = if (predtym.isEmpty()) "" else "Previous lines (already translated, for continuity only – do not output them):\n" +
                predtym.joinToString("\n") { "${spk(it)}: ${it.text} → ${it.preklad}" } + "\n\nTranslate:\n"
            val vstup = uvod + davka.mapIndexed { i, r -> "${i + 1}|${"%.1f".format(java.util.Locale.ROOT, r.po - r.od)}|${spk(r)}|${r.text}" }.joinToString("\n")
            val sb = StringBuilder()
            ChatJadro.generujPreSiet(listOf("system" to sys, "user" to vstup), 1400) { b -> sb.append(String(b, Charsets.UTF_8)); !zrusit }
            val riadky = ChatJadro.ocistiPrompt(sb.toString()).lines()
            for (l in riadky) {
                val m = Regex("^\\s*(\\d+)\\s*[|:.)-]\\s*(.+)$").find(l) ?: continue
                val i = m.groupValues[1].toInt() - 1
                if (i !in davka.indices) continue
                var t = m.groupValues[2].trim().replace(Regex("^[0-9]+([.,][0-9]+)?\\s*s?\\s*\\|\\s*"), "")
                // ak model zopakoval označenie hovoriaceho
                t = t.replace(Regex("^(S\\d+|\\?)\\s*\\|\\s*"), "")
                // emócia
                val e = Regex("^\\s*\\[?([A-Za-z]+)\\]?\\s*\\|\\s*(.+)$").find(t)
                if (e != null && e.groupValues[1].lowercase() in PREDNES) { davka[i].emocia = e.groupValues[1].lowercase(); t = e.groupValues[2].trim() }
                davka[i].preklad = t
            }
            // čo sa nepreložilo v dávke, skúsi sa po jednom
            for (r in davka.filter { it.preklad.isBlank() }) {
                val s1 = StringBuilder()
                ChatJadro.generujPreSiet(listOf("system" to "Translate into natural spoken $jazykMeno for film dubbing. Reply with the translation only." +
                    (if (kontext.isNotBlank()) "\nContext:\n$kontext" else ""), "user" to r.text), 200) { b -> s1.append(String(b, Charsets.UTF_8)); !zrusit }
                r.preklad = ChatJadro.ocistiPrompt(s1.toString()).lines().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            }
            hotove += davka
            hotovych += davka.size; priebeh(hotovych)
        }
        println("dabing: emócie ${reci.groupingBy { it.emocia }.eachCount()}")
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

    private fun zapisF32(f: File, x: FloatArray) {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.asFloatBuffer().put(x); f.writeBytes(b.array())
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

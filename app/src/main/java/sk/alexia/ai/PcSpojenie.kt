package sk.alexia.ai

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * Spojenie mobilu s PC (Alexia AI na PC so zapnutým „Zdieľanie v sieti“).
 * Funkcie, ktoré robí PC (videá, rozhýbanie, galéria PC), sú v mobile zamknuté, kým PC neodpovie.
 */
object PcSpojenie {
    var info by mutableStateOf<Klient.Info?>(null)
        private set
    var kontroluje by mutableStateOf(false)
        private set
    var chyba by mutableStateOf("")
        private set
    val pripojene get() = info != null

    /** stav radu videí na PC (posledná odpoveď) */
    var stav by mutableStateOf<JSONObject?>(null)
        private set
    /** sťahovanie videí: id → priebeh; stiahnuté: id → uri v mobile */
    val stahuje = mutableStateMapOf<String, Float>()
    val stiahnute = mutableStateMapOf<String, String>()

    private val adr get() = ChatJadro.nastavenia.vzdialenaAdresa
    private val pin get() = ChatJadro.nastavenia.vzdialenyPin
    private val prefs get() = Platforma.app.getSharedPreferences("pc_videa", 0)

    fun skontroluj() {
        if (kontroluje) return
        if (adr.isBlank()) { info = null; chyba = T("pc_bez_adresy"); return }
        kontroluje = true
        Thread {
            val r = runCatching { Klient.info(adr, pin) }
            Platforma.naHlavnom {
                kontroluje = false
                info = r.getOrNull()
                chyba = r.exceptionOrNull()?.let { it.message ?: it.toString() } ?: ""
                if (stiahnute.isEmpty()) prefs.all.forEach { (k, v) -> if (v is String) stiahnute[k] = v }
            }
        }.start()
    }

    /** obnoví stav radu; pri chybe spojenia zamkne funkcie */
    fun obnovStav() {
        if (adr.isBlank()) return
        Thread {
            val r = runCatching { Klient.videoStav(adr, pin) }
            Platforma.naHlavnom {
                r.onSuccess { stav = it }.onFailure { info = null; chyba = it.message ?: it.toString() }
            }
        }.start()
    }

    fun pridaj(popis: String, sekundy: Int, cezObrazok: Boolean, predloha: String?, hotovo: (String?) -> Unit) {
        Thread {
            val r = runCatching { Klient.videoPridaj(adr, pin, popis, sekundy, cezObrazok, predloha) }
            Platforma.naHlavnom { hotovo(r.exceptionOrNull()?.let { it.message ?: it.toString() }); obnovStav() }
        }.start()
    }

    /** videoklip na PC: skladba z mobilu (uri) + obrázky z galérie PC */
    fun pridajKlip(uri: android.net.Uri, fotky: List<String>, tempo: Int, hotovo: (String?) -> Unit) {
        Thread {
            val r = runCatching {
                val cr = Platforma.app.contentResolver
                val meno = runCatching { cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: "skladba.mp3"
                val data = cr.openInputStream(uri)!!.use { it.readBytes() }
                if (data.size > 200_000_000) throw IllegalStateException("Skladba je príliš veľká")
                Klient.klipPridaj(adr, pin, meno, data, fotky, tempo)
            }
            Platforma.naHlavnom { hotovo(r.exceptionOrNull()?.let { it.message ?: it.toString() }); obnovStav() }
        }.start()
    }

    fun zrus(id: String) {
        Thread { runCatching { Klient.videoZrus(adr, pin, id) }; Platforma.naHlavnom { stiahnute.remove(id); prefs.edit().remove(id).apply(); obnovStav() } }.start()
    }

    /** stiahne hotové video do Filmy/Alexia AI */
    fun stiahni(id: String) {
        if (stahuje.containsKey(id)) return
        stahuje[id] = 0f
        Thread {
            val r = runCatching {
                val meno = "alexia_video_$id.mp4"
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, meno)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Alexia AI")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val cr = Platforma.app.contentResolver
                    val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                    try {
                        cr.openOutputStream(uri)!!.use { o -> Klient.videoStiahni(adr, pin, id, o) { p -> Platforma.naHlavnom { stahuje[id] = p } } }
                        cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) { cr.delete(uri, null, null); throw e }
                    uri.toString()
                } else {
                    val d = File(Platforma.app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Alexia AI").apply { mkdirs() }
                    val f = File(d, meno)
                    f.outputStream().use { o -> Klient.videoStiahni(adr, pin, id, o) { p -> Platforma.naHlavnom { stahuje[id] = p } } }
                    Uri.fromFile(f).toString()
                }
            }
            Platforma.naHlavnom {
                stahuje.remove(id)
                r.onSuccess { stiahnute[id] = it; prefs.edit().putString(id, it).apply(); Platforma.oznam(T("pc_stiahnute")) }
                    .onFailure { Platforma.oznam("⚠ " + (it.message ?: it.toString())) }
            }
        }.start()
    }

    fun galeria(hotovo: (List<Triple<String, String, Boolean>>?, String) -> Unit) {
        Thread {
            val r = runCatching { Klient.galeria(adr, pin) }
            Platforma.naHlavnom { hotovo(r.getOrNull(), r.exceptionOrNull()?.message ?: "") }
        }.start()
    }

    fun nahlad(meno: String): ByteArray? = runCatching { Klient.nahlad(adr, pin, meno) }.getOrNull()

    /** uloží obrázok z PC do Obrázky/Alexia AI */
    fun ulozObrazok(meno: String) {
        Thread {
            val r = runCatching {
                val tmp = File(Platforma.app.cacheDir, File(meno).name)
                tmp.outputStream().use { Klient.obrazokZPc(adr, pin, meno, it) }
                val ok = ObrazkyJadro.ulozDoGalerie(tmp); tmp.delete()
                if (!ok) throw IllegalStateException(T("pc_neulozene"))
            }
            Platforma.naHlavnom { Platforma.oznam(r.exceptionOrNull()?.let { "⚠ " + (it.message ?: it.toString()) } ?: T("pc_obrazok_ulozeny")) }
        }.start()
    }
}

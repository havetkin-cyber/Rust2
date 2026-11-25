package sk.alexia.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Kontrola novej verzie na GitHube (vydania v1.2.N, N = versionCode).
 * APK sa stiahne priamo v appke a nainštaluje cez PackageInstaller (aktualizácia priamo, bez prehliadača).
 */
object Aktualizacie {
    private const val API = "https://api.github.com/repos/havetkin-cyber/rust2/releases/latest"
    const val APK = "https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI.apk"

    var novaVerzia by mutableStateOf<String?>(null)
    private var skontrolovane = false

    var kontroluje by mutableStateOf(false)
        private set

    /** rucne = z Nastavení: ukáže aj preskočenú verziu a napíše, keď je appka aktuálna */
    fun skontroluj(c: Context, rucne: Boolean = false) {
        if (skontrolovane && !rucne) return
        if (kontroluje) return
        skontrolovane = true; kontroluje = true
        val moja = runCatching { PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0)) }.getOrDefault(0L)
        val preskocena = c.getSharedPreferences("aktualizacie", Context.MODE_PRIVATE).getLong("preskocit", 0L)
        thread {
            val r = runCatching {
                val k = URL(API).openConnection() as HttpURLConnection
                k.connectTimeout = 10_000; k.readTimeout = 10_000
                k.setRequestProperty("Accept", "application/vnd.github+json")
                k.setRequestProperty("User-Agent", "AlexiaAI")
                val tag = JSONObject(k.inputStream.bufferedReader().readText()).getString("tag_name")   // v1.0.N
                val n = tag.substringAfterLast('.').toLong()
                val v = tag.removePrefix("v")
                Platforma.naHlavnom {
                    if (n > moja && !rucne && n > preskocena && ChatJadro.nastavenia.autoAktualizacie) stiahniNaPozadi(c, v)
                    else if (n > moja && (rucne || n > preskocena)) novaVerzia = v
                    else if (rucne) Platforma.oznam(T("akt_najnovsia", "1.2.$moja"))
                }
            }
            Platforma.naHlavnom { kontroluje = false; if (rucne) r.onFailure { Platforma.oznam(T("akt_nepodarilo")) } }
        }
    }

    /** stará cesta (DownloadManager) – už sa nepoužíva, ostáva kvôli dialógu */
    var stiahnuta by mutableStateOf<String?>(null)

    /** priebeh sťahovania priamo v appke (null = nesťahuje sa) */
    var priebeh by mutableStateOf<Float?>(null)
        private set
    var chyba by mutableStateOf("")

    /** automaticky: stiahne a nainštaluje bez pýtania (systém sa opýta len ak treba – napr. prvýkrát) */
    private fun stiahniNaPozadi(c: Context, v: String) = instaluj(c, v, rucne = false)

    /** tlačidlo Aktualizovať */
    fun stiahni(c: Context) { novaVerzia?.let { instaluj(c, it) } }

    /**
     * Stiahne APK priamo do appky a nainštaluje ho cez PackageInstaller.
     * Prvýkrát treba povoliť „Inštalovať neznáme aplikácie“ pre Alexia AI; od Androidu 12 potom ďalšie
     * aktualizácie prebehnú bez potvrdzovania (appka je sama sebe inštalátorom).
     */
    fun instaluj(c: Context, v: String, rucne: Boolean = true) {
        if (priebeh != null) return
        val app = c.applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            novaVerzia = v
            chyba = T("akt_povol_instalaciu")
            // nastavenie otvoríme len po stlačení Aktualizovať, nie samé pri štarte
            if (rucne) runCatching {
                app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        priebeh = 0f; chyba = ""; novaVerzia = v
        thread {
            val r = runCatching {
                val apk = java.io.File(app.cacheDir, "aktualizacia.apk")
                var url = URL(APK); var k: HttpURLConnection; var skoky = 0
                while (true) {
                    k = url.openConnection() as HttpURLConnection
                    k.instanceFollowRedirects = false; k.connectTimeout = 20_000; k.readTimeout = 60_000
                    k.setRequestProperty("User-Agent", "AlexiaAI")
                    if (k.responseCode in 300..399 && skoky++ < 8) { url = URL(url, k.getHeaderField("Location")); k.disconnect(); continue }
                    break
                }
                if (k.responseCode != 200) throw IllegalStateException("HTTP ${k.responseCode}")
                val spolu = k.contentLengthLong; var h = 0L
                k.inputStream.use { i -> apk.outputStream().use { o ->
                    val b = ByteArray(1 shl 16); var n: Int
                    while (i.read(b).also { n = it } > 0) { o.write(b, 0, n); h += n; if (spolu > 0) { val p = h.toFloat() / spolu; Platforma.naHlavnom { priebeh = p } } }
                } }
                if (spolu > 0 && apk.length() != spolu) throw IllegalStateException(T("akt_nepodarilo"))
                val pi = app.packageManager.packageInstaller
                val par = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                par.setAppPackageName(app.packageName)
                if (android.os.Build.VERSION.SDK_INT >= 31) par.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = pi.createSession(par)
                pi.openSession(id).use { ses ->
                    ses.openWrite("alexia.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; ses.fsync(o) }
                    val zamer = Intent(app, AktualizaciaPrijimac::class.java)
                    val pend = android.app.PendingIntent.getBroadcast(app, id, zamer,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0))
                    ses.commit(pend.intentSender)
                }
            }
            Platforma.naHlavnom {
                priebeh = null
                r.onFailure { chyba = it.message ?: T("akt_nepodarilo") }
            }
        }
    }

    fun preskoc(c: Context) {
        novaVerzia?.substringAfterLast('.')?.toLongOrNull()?.let {
            c.getSharedPreferences("aktualizacie", Context.MODE_PRIVATE).edit().putLong("preskocit", it).apply()
        }
        novaVerzia = null
    }
}

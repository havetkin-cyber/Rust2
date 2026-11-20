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
 * Kontrola novej verzie na GitHube (vydania v1.0.N, N = versionCode).
 * APK stiahne prehliadač a nainštaluje systém – appka nemá oprávnenie inštalovať (kvôli Play Protect).
 */
object Aktualizacie {
    private const val API = "https://api.github.com/repos/havetkin-cyber/rust2/releases/latest"
    const val APK = "https://github.com/havetkin-cyber/rust2/releases/latest/download/AlexiaAI.apk"

    var novaVerzia by mutableStateOf<String?>(null)
    private var skontrolovane = false

    fun skontroluj(c: Context) {
        if (skontrolovane) return
        skontrolovane = true
        val moja = runCatching { PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0)) }.getOrDefault(0L)
        val preskocena = c.getSharedPreferences("aktualizacie", Context.MODE_PRIVATE).getLong("preskocit", 0L)
        thread {
            runCatching {
                val k = URL(API).openConnection() as HttpURLConnection
                k.connectTimeout = 10_000; k.readTimeout = 10_000
                k.setRequestProperty("Accept", "application/vnd.github+json")
                k.setRequestProperty("User-Agent", "AlexiaAI")
                val tag = JSONObject(k.inputStream.bufferedReader().readText()).getString("tag_name")   // v1.0.N
                val n = tag.substringAfterLast('.').toLong()
                if (n > moja && n > preskocena) { val v = tag.removePrefix("v"); android.os.Handler(android.os.Looper.getMainLooper()).post { novaVerzia = v } }
            }
        }
    }

    fun stiahni(c: Context) {
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        novaVerzia = null
    }

    fun preskoc(c: Context) {
        novaVerzia?.substringAfterLast('.')?.toLongOrNull()?.let {
            c.getSharedPreferences("aktualizacie", Context.MODE_PRIVATE).edit().putLong("preskocit", it).apply()
        }
        novaVerzia = null
    }
}

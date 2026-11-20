package sk.alexia.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Spoločné pomôcky (rovnaké názvy ako v počítačovej verzii, aby sa kód dal zdieľať). */
object Platforma {
    private val hlavne = Handler(Looper.getMainLooper())
    lateinit var app: Context
        private set

    fun init(c: Context) { if (!::app.isInitialized) app = c.applicationContext }
    fun naHlavnom(f: () -> Unit) { hlavne.post(f) }
    fun oznam(t: String) = naHlavnom { Toast.makeText(app, t, Toast.LENGTH_SHORT).show() }
    val priecinok get() = app.filesDir

    /** koľko RAM má mobil (na rozhodnutie, či sa chat aj obrázky zmestia naraz) */
    fun ramBajtov(): Long = runCatching {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }.getOrDefault(4L shl 30)
}

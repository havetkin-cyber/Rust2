package sk.alexia.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Nastavenia appky. Systémový prompt je predvolene prázdny – appka modelu nič nepridáva. */
class Nastavenia(c: Context) {
    private val p = c.getSharedPreferences("nastavenia", Context.MODE_PRIVATE)

    var systemovyPrompt by mutableStateOf(p.getString("system", "") ?: "")
    var teplota by mutableFloatStateOf(p.getFloat("teplota", 0.7f))
    var kontext by mutableIntStateOf(p.getInt("kontext", 4096))
    var maxTokenov by mutableIntStateOf(p.getInt("maxTokenov", 1024))
    var vlakna by mutableIntStateOf(p.getInt("vlakna", predvoleneVlakna()))
    var aktivnyModel by mutableStateOf(p.getString("model", null))

    fun uloz() {
        p.edit()
            .putString("system", systemovyPrompt)
            .putFloat("teplota", teplota)
            .putInt("kontext", kontext)
            .putInt("maxTokenov", maxTokenov)
            .putInt("vlakna", vlakna)
            .putString("model", aktivnyModel)
            .apply()
    }

    companion object {
        /** výkonné jadrá: v mobiloch býva 8 jadier, z toho 4 rýchle */
        fun predvoleneVlakna() = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else maxOf(1, it - 1) }
    }
}

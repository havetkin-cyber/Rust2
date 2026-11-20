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
    var citatNahlas by mutableStateOf(p.getBoolean("citatNahlas", false))
    var jazykOdpovedi by mutableStateOf(p.getString("jazykOdpovedi", null) ?: if (java.util.Locale.getDefault().language in setOf("sk", "cs")) "sk" else "auto")
    // pozadie chatu (kópia obrázka v priečinku appky)
    var pozadieChatu by mutableStateOf(p.getString("pozadieChatu", null))
    var pozadieVerzia by mutableIntStateOf(0)
    var stmavenie by mutableIntStateOf(p.getInt("stmavenie", 60))
    // prepojenie: model beží na inom zariadení (Legion Go S, PC)
    var vzdialene by mutableStateOf(p.getBoolean("vzdialene", false))
    var vzdialenaAdresa by mutableStateOf(p.getString("vzdialenaAdresa", "") ?: "")
    var vzdialenyPin by mutableStateOf(p.getString("vzdialenyPin", "") ?: "")

    fun uloz() {
        p.edit()
            .putString("system", systemovyPrompt)
            .putFloat("teplota", teplota)
            .putInt("kontext", kontext)
            .putInt("maxTokenov", maxTokenov)
            .putInt("vlakna", vlakna)
            .putString("model", aktivnyModel)
            .putString("jazykOdpovedi", jazykOdpovedi)
            .putBoolean("citatNahlas", citatNahlas)
            .putString("pozadieChatu", pozadieChatu)
            .putInt("stmavenie", stmavenie)
            .putBoolean("vzdialene", vzdialene)
            .putString("vzdialenaAdresa", vzdialenaAdresa)
            .putString("vzdialenyPin", vzdialenyPin)
            .apply()
    }

    /** systémový prompt + jazyk odpovedí (nastavuje používateľ; "auto" = nič nepridá) */
    fun systemSJazykom(): String {
        val j = when (jazykOdpovedi) { "sk" -> "Odpovedaj vždy po slovensky."; "en" -> "Always answer in English."; else -> "" }
        return listOf(systemovyPrompt.trim(), j).filter { it.isNotEmpty() }.joinToString("\n")
    }

    companion object {
        /** výkonné jadrá: v mobiloch býva 8 jadier, z toho 4 rýchle */
        fun predvoleneVlakna() = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else maxOf(1, it - 1) }
    }
}

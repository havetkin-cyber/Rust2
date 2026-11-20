package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Properties

/** Nastavenia appky (~/.local/share/AlexiaAI/nastavenia.properties). Systémový prompt je predvolene prázdny. */
class Nastavenia {
    private val subor = File(Platforma.priecinok, "nastavenia.properties")
    private val p = Properties().apply { if (subor.exists()) subor.inputStream().use { load(it) } }

    var systemovyPrompt by mutableStateOf(p.getProperty("system", ""))
    var teplota by mutableFloatStateOf(p.getProperty("teplota", "0.7").toFloatOrNull() ?: 0.7f)
    var kontext by mutableIntStateOf(p.getProperty("kontext", "8192").toIntOrNull() ?: 8192)
    var maxTokenov by mutableIntStateOf(p.getProperty("maxTokenov", "1024").toIntOrNull() ?: 1024)
    var vlakna by mutableIntStateOf(p.getProperty("vlakna")?.toIntOrNull() ?: predvoleneVlakna())
    var aktivnyModel by mutableStateOf(p.getProperty("model"))
    var aktivnyObrazkovyModel by mutableStateOf(p.getProperty("obrazkovyModel"))
    var posunKlavesnica by mutableStateOf(p.getProperty("posunKlavesnica")?.let { it == "true" } ?: Klavesnica.jeSteamOS)
    var vyskaKlavesnice by mutableIntStateOf(p.getProperty("vyskaKlavesnice")?.toIntOrNull() ?: 42)
    var otvoritKlavesnicu by mutableStateOf(p.getProperty("otvoritKlavesnicu") == "true")

    init { p.getProperty("anglicky")?.let { T.anglicky = it == "true" } ?: run { T.anglicky = false } }

    fun uloz() {
        p.setProperty("system", systemovyPrompt)
        p.setProperty("teplota", teplota.toString())
        p.setProperty("kontext", kontext.toString())
        p.setProperty("maxTokenov", maxTokenov.toString())
        p.setProperty("vlakna", vlakna.toString())
        p.setProperty("anglicky", T.anglicky.toString())
        p.setProperty("posunKlavesnica", posunKlavesnica.toString())
        p.setProperty("vyskaKlavesnice", vyskaKlavesnice.toString())
        p.setProperty("otvoritKlavesnicu", otvoritKlavesnicu.toString())
        aktivnyModel?.let { p.setProperty("model", it) } ?: p.remove("model")
        aktivnyObrazkovyModel?.let { p.setProperty("obrazkovyModel", it) } ?: p.remove("obrazkovyModel")
        runCatching { subor.outputStream().use { p.store(it, "Alexia AI") } }
    }

    companion object {
        /** Z2 Go má 8 vlákien – necháme 2 pre systém; model beží hlavne na grafike */
        fun predvoleneVlakna() = maxOf(1, Runtime.getRuntime().availableProcessors() - 2)
    }
}

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
    /** vymenený textový kodér pre obrázkovú sadu (id sady → URL alebo cesta) */
    private val kodery = mutableMapOf<String, String>().apply {
        p.stringPropertyNames().filter { it.startsWith("koder.") }.forEach { put(it.removePrefix("koder."), p.getProperty(it)) }
    }
    var koderVerzia by mutableIntStateOf(0)
    fun koder(sada: String): String? = kodery[sada]
    fun nastavKoder(sada: String, v: String?) { if (v == null) kodery.remove(sada) else kodery[sada] = v; koderVerzia++; uloz() }
    var autoAktualizacie by mutableStateOf(p.getProperty("autoAktualizacie") != "false")
    var aktualnyChat by mutableStateOf(p.getProperty("aktualnyChat"))
    var hudbaVidea by mutableStateOf(p.getProperty("hudbaVidea"))
    var citatNahlas by mutableStateOf(p.getProperty("citatNahlas") == "true")
    var hlas by mutableStateOf(p.getProperty("hlas") ?: "lili")
    /** dĺžka AI videa v sekundách a šetrenie VRAM (kodér a VAE videa v RAM) */
    var dlzkaVidea by mutableIntStateOf(p.getProperty("dlzkaVidea")?.toIntOrNull() ?: 2)
    var setritVram by mutableStateOf(p.getProperty("setritVram") == "true")
    /** video: najprv presný obrázok (FLUX…), potom ho Wan 2.2 rozhýbe */
    var videoCezObrazok by mutableStateOf(p.getProperty("videoCezObrazok") == "true")
    var hlasRychlost by mutableFloatStateOf(p.getProperty("hlasRychlost")?.toFloatOrNull() ?: 1f)
    var hlasVyska by mutableFloatStateOf(p.getProperty("hlasVyska")?.toFloatOrNull() ?: 1f)
    var hlasHrubka by mutableFloatStateOf(p.getProperty("hlasHrubka")?.toFloatOrNull() ?: 1f)
    var hlasPlnost by mutableFloatStateOf(p.getProperty("hlasPlnost")?.toFloatOrNull() ?: 0f)
    var mixA by mutableIntStateOf(p.getProperty("mixA")?.toIntOrNull() ?: 0)
    var mixB by mutableIntStateOf(p.getProperty("mixB")?.toIntOrNull() ?: 4)
    var mixPomer by mutableFloatStateOf(p.getProperty("mixPomer")?.toFloatOrNull() ?: 0.5f)
    var jazykOdpovedi by mutableStateOf(p.getProperty("jazykOdpovedi") ?: if (java.util.Locale.getDefault().language in setOf("sk", "cs")) "sk" else "auto")
    var aktivnyObrazkovyModel by mutableStateOf(p.getProperty("obrazkovyModel"))
    var posunKlavesnica by mutableStateOf(p.getProperty("posunKlavesnica")?.let { it == "true" } ?: Klavesnica.jeSteamOS)
    var vyskaKlavesnice by mutableIntStateOf(p.getProperty("vyskaKlavesnice")?.toIntOrNull() ?: 42)
    /** pozadie chatu (kópia obrázka v priečinku appky); verzia sa zvýši pri zmene, aby sa obrázok znova načítal */
    var pozadieChatu by mutableStateOf(p.getProperty("pozadieChatu"))
    var pozadieVerzia by mutableIntStateOf(0)
    var stmavenie by mutableIntStateOf(p.getProperty("stmavenie")?.toIntOrNull() ?: 60)
    // prepojenie v sieti
    var pinServera by mutableStateOf(p.getProperty("pinServera", ""))
    var zdielat by mutableStateOf(p.getProperty("zdielat") == "true")
    var vzdialene by mutableStateOf(p.getProperty("vzdialene") == "true")
    var vzdialenaAdresa by mutableStateOf(p.getProperty("vzdialenaAdresa", ""))
    var vzdialenyPin by mutableStateOf(p.getProperty("vzdialenyPin", ""))
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
        pozadieChatu?.let { p.setProperty("pozadieChatu", it) } ?: p.remove("pozadieChatu")
        p.setProperty("stmavenie", stmavenie.toString())
        p.setProperty("pinServera", pinServera)
        p.setProperty("jazykOdpovedi", jazykOdpovedi)
        p.setProperty("citatNahlas", citatNahlas.toString())
        p.setProperty("hlas", hlas)
        p.setProperty("dlzkaVidea", dlzkaVidea.toString())
        p.setProperty("setritVram", setritVram.toString())
        p.setProperty("videoCezObrazok", videoCezObrazok.toString())
        p.setProperty("hlasRychlost", hlasRychlost.toString())
        p.setProperty("hlasVyska", hlasVyska.toString())
        p.setProperty("hlasHrubka", hlasHrubka.toString())
        p.setProperty("hlasPlnost", hlasPlnost.toString())
        p.setProperty("mixA", mixA.toString()); p.setProperty("mixB", mixB.toString()); p.setProperty("mixPomer", mixPomer.toString())
        aktualnyChat?.let { p.setProperty("aktualnyChat", it) }
        p.setProperty("autoAktualizacie", autoAktualizacie.toString())
        p.stringPropertyNames().filter { it.startsWith("koder.") }.forEach { p.remove(it) }
        kodery.forEach { (k, v) -> p.setProperty("koder.$k", v) }
        hudbaVidea?.let { p.setProperty("hudbaVidea", it) } ?: p.remove("hudbaVidea")
        p.setProperty("zdielat", zdielat.toString())
        p.setProperty("vzdialene", vzdialene.toString())
        p.setProperty("vzdialenaAdresa", vzdialenaAdresa)
        p.setProperty("vzdialenyPin", vzdialenyPin)
        aktivnyModel?.let { p.setProperty("model", it) } ?: p.remove("model")
        aktivnyObrazkovyModel?.let { p.setProperty("obrazkovyModel", it) } ?: p.remove("obrazkovyModel")
        runCatching { subor.outputStream().use { p.store(it, "Alexia AI") } }
    }

    /** systémový prompt + jazyk odpovedí (nastavuje používateľ; "auto" = nič nepridá) */
    fun systemSJazykom(): String {
        val j = when (jazykOdpovedi) { "sk" -> "Odpovedaj vždy po slovensky."; "en" -> "Always answer in English."; else -> "" }
        return listOf(systemovyPrompt.trim(), j).filter { it.isNotEmpty() }.joinToString("\n")
    }

    companion object {
        /** Z2 Go má 8 vlákien – necháme 2 pre systém; model beží hlavne na grafike */
        fun predvoleneVlakna() = maxOf(1, Runtime.getRuntime().availableProcessors() - 2)
    }
}

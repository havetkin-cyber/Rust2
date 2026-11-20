package sk.alexia.ai

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import java.io.File

/**
 * Klávesnica na obrazovke (Steam / SteamOS) sa kreslí cez okno appky a o jej veľkosti nevieme.
 * Keď píšeš do poľa, appka preto posunie obsah nahor o nastavenú výšku, aby pole bolo nad klávesnicou.
 */
object Klavesnica {
    private var aktivnych by mutableIntStateOf(0)
    val pise get() = aktivnych > 0

    /** SteamOS → predvolene zapnuté */
    val jeSteamOS: Boolean = runCatching { File("/etc/os-release").readText().contains("steamos", ignoreCase = true) }.getOrDefault(false)

    internal fun zmen(o: Int) { aktivnych = maxOf(0, aktivnych + o) }

    /** otvorí klávesnicu Steamu (funguje, keď beží Steam) */
    fun otvorSteam() { runCatching { ProcessBuilder("xdg-open", "steam://open/keyboard").start() } }
}

/** pridaj na každé textové pole: keď je v ňom kurzor, obsah sa posunie nad klávesnicu */
fun Modifier.nadKlavesnicou(): Modifier = composed {
    val stav = remember { BooleanArray(1) }
    DisposableEffect(Unit) { onDispose { if (stav[0]) { stav[0] = false; Klavesnica.zmen(-1) } } }
    onFocusChanged { f ->
        if (f.isFocused != stav[0]) {
            stav[0] = f.isFocused
            Klavesnica.zmen(if (f.isFocused) 1 else -1)
            if (f.isFocused && ChatJadro.nastavenia.otvoritKlavesnicu) Klavesnica.otvorSteam()
        }
    }
}

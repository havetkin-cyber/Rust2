package sk.alexia.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Všetko, čo je na Linuxe iné ako na Androide. Dáta appky sú len v ~/.local/share/AlexiaAI. */
object Platforma {
    val jeWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    /** dáta appky: Linux ~/.local/share/AlexiaAI, Windows %LOCALAPPDATA%\\AlexiaAI */
    val priecinok: File = (if (jeWindows)
        File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "\\AppData\\Local"), "AlexiaAI")
    else File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share"), "AlexiaAI")).apply { mkdirs() }

    /** otvorí odkaz v prehliadači */
    fun otvorOdkaz(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            .onFailure { runCatching { ProcessBuilder("xdg-open", url).start() } }
    }

    /** priečinok Obrázky používateľa (~/Pictures alebo podľa XDG) */
    val obrazkyPouzivatela: File = File(System.getProperty("user.home"), "Pictures")

    fun naHlavnom(f: () -> Unit) = SwingUtilities.invokeLater(f)

    /** natívne knižnice sú v resources appky (compose.application.resources.dir) */
    fun nacitajKniznicu(meno: String) {
        val dir = System.getProperty("compose.application.resources.dir")
        val f = dir?.let { File(it, System.mapLibraryName(meno)) }  // libX.so / X.dll
        if (f != null && f.exists()) System.load(f.absolutePath) else System.loadLibrary(meno)
    }

    var oznam by mutableStateOf<String?>(null)
    fun oznam(t: String) { naHlavnom { oznam = t } }

    /** výber súboru priamo v okne appky (systémové okno sa na SteamOS často neukáže) */
    var ziadostOVyber by mutableStateOf<ZiadostOVyber?>(null)
    fun vyberSubor(nadpis: String, pripony: Set<String>, hotovo: (File) -> Unit) { ziadostOVyber = ZiadostOVyber(nadpis, pripony, hotovo) }
    val PRIPONY_FOTIEK = setOf("jpg", "jpeg", "png", "bmp", "gif")

    fun nacitajObrazok(f: File, zmensenie: Int = 1): ImageBitmap? = runCatching {
        val img = ImageIO.read(f) ?: return null
        // 4K/8K obrázky na obrazovke zobrazíme zmenšené (súbor ostáva v plnej veľkosti)
        val zm = if (zmensenie <= 1 && maxOf(img.width, img.height) > 4096) (maxOf(img.width, img.height) + 2047) / 2048 else zmensenie
        if (zm <= 1) return img.toComposeImageBitmap()
        val w = maxOf(1, img.width / zm); val h = maxOf(1, img.height / zm)
        val m = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        m.createGraphics().apply { drawImage(img, 0, 0, w, h, null); dispose() }
        m.toComposeImageBitmap()
    }.getOrNull()
}

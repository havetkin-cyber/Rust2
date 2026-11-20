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
    val priecinok: File = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share"), "AlexiaAI").apply { mkdirs() }

    /** priečinok Obrázky používateľa (~/Pictures alebo podľa XDG) */
    val obrazkyPouzivatela: File = File(System.getProperty("user.home"), "Pictures")

    fun naHlavnom(f: () -> Unit) = SwingUtilities.invokeLater(f)

    /** natívne knižnice sú v resources appky (compose.application.resources.dir) */
    fun nacitajKniznicu(meno: String) {
        val dir = System.getProperty("compose.application.resources.dir")
        val f = dir?.let { File(it, "lib$meno.so") }
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
        if (zmensenie <= 1) return img.toComposeImageBitmap()
        val w = maxOf(1, img.width / zmensenie); val h = maxOf(1, img.height / zmensenie)
        val m = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        m.createGraphics().apply { drawImage(img, 0, 0, w, h, null); dispose() }
        m.toComposeImageBitmap()
    }.getOrNull()
}

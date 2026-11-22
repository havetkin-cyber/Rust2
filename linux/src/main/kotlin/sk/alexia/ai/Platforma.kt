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

    // ---------------- Dokumenty/Alexia AI – tvoje výtvory (modely a nastavenia ostávajú v priečinku appky)

    /** priečinok Dokumenty (na Windows aj presmerovaný, napr. do OneDrive) */
    val dokumenty: File = runCatching {
        if (jeWindows) javax.swing.filechooser.FileSystemView.getFileSystemView().defaultDirectory
        else runCatching {
            val p = ProcessBuilder("xdg-user-dir", "DOCUMENTS").redirectErrorStream(true).start()
            File(p.inputStream.bufferedReader().readText().trim()).takeIf { p.waitFor() == 0 && it.path.isNotBlank() && it.path != System.getProperty("user.home") }
        }.getOrNull() ?: File(System.getProperty("user.home"), "Documents")
    }.getOrNull()?.takeIf { it.path.isNotBlank() } ?: File(System.getProperty("user.home"), "Documents")

    /** Dokumenty/Alexia AI */
    val alexia: File get() = File(dokumenty, "Alexia AI").apply { mkdirs() }
    private fun pod(meno: String) = File(alexia, meno).apply { mkdirs() }
    val rozhovory get() = pod("Rozhovory")
    val obrazky get() = pod("Obrazky")
    val videa get() = pod("Videa")
    val hlasy get() = pod("Hlasy")
    val logy get() = pod("Logy")

    /** staré umiestnenia → nové (pre cesty uložené v chatoch a v rade videí) */
    private val presuny get() = listOf(File(priecinok, "obrazky") to obrazky, File(priecinok, "videa") to videa, File(priecinok, "historia") to rozhovory)

    /** jednorazovo presunie tvoje výtvory z priečinka appky do Dokumenty/Alexia AI */
    fun presunDoDokumentov() = runCatching {
        presuny.forEach { (stary, novy) ->
            if (!stary.isDirectory || stary.canonicalPath == novy.canonicalPath) return@forEach
            stary.listFiles()?.forEach { f ->
                val ciel = File(novy, f.name)
                if (ciel.exists()) return@forEach
                runCatching { java.nio.file.Files.move(f.toPath(), ciel.toPath()) }
                    .onFailure { runCatching { f.copyRecursively(ciel, overwrite = false); f.deleteRecursively() } }
            }
            if (stary.listFiles()?.isEmpty() == true) stary.delete()
        }
    }

    /** cesta k súboru, ktorý sa medzičasom presunul do Dokumenty/Alexia AI */
    fun cesta(p: String?): String? {
        if (p == null || File(p).exists()) return p
        presuny.forEach { (stary, novy) ->
            val sp = stary.path
            if (p.startsWith(sp + File.separator)) return File(novy, p.substring(sp.length + 1)).path
        }
        return p
    }

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

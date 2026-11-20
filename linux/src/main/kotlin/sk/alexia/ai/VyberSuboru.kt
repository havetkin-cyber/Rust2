package sk.alexia.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/** žiadosť o výber súboru – zobrazí ju [VyberSuboru] priamo v okne appky */
class ZiadostOVyber(val nadpis: String, val pripony: Set<String>, val hotovo: (File) -> Unit)

private val home = File(System.getProperty("user.home"))
private var poslednyPriecinok: File? = null

/** rýchle miesta: Obrázky, Stiahnuté, Plocha, Domov, SD karta / USB */
private fun miesta(): List<Pair<String, File>> {
    val z = mutableListOf<Pair<String, File>>()
    listOf("pr_obrazky" to "Pictures", "pr_stiahnute" to "Downloads", "pr_plocha" to "Desktop").forEach { (k, d) ->
        File(home, d).takeIf { it.isDirectory }?.let { z += T(k) to it }
    }
    z += T("pr_domov") to home
    // SD karta a USB (SteamOS ich pripája do /run/media)
    listOf(File("/run/media"), File("/run/media/" + System.getProperty("user.name"))).forEach { r ->
        r.listFiles()?.filter { it.isDirectory && it.canRead() && it.name != System.getProperty("user.name") }?.forEach { z += it.name to it }
    }
    return z.distinctBy { it.second.absolutePath }
}

@Composable
fun VyberSuboru(z: ZiadostOVyber, zavri: () -> Unit) {
    val miesta = remember { miesta() }
    var priecinok by remember { mutableStateOf(poslednyPriecinok?.takeIf { it.isDirectory } ?: miesta.first().second) }
    val polozky = remember(priecinok) {
        val vsetko = priecinok.listFiles()?.filter { !it.name.startsWith(".") && it.canRead() } ?: emptyList()
        vsetko.filter { it.isDirectory }.sortedBy { it.name.lowercase() } +
            vsetko.filter { it.isFile && it.extension.lowercase() in z.pripony }.sortedByDescending { it.lastModified() }
    }
    Dialog(onDismissRequest = zavri, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(0.92f).fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(24.dp)).background(Farby.plocha).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(z.nadpis, color = Farby.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = zavri) { Icon(Icons.Default.Close, T("zrusit"), tint = Farby.text) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                miesta.forEach { (nazov, f) ->
                    FilterChip(selected = priecinok == f, onClick = { priecinok = f }, label = { Text(nazov) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = androidx.compose.ui.graphics.Color.White, labelColor = Farby.text))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { priecinok.parentFile?.let { priecinok = it } }, enabled = priecinok.parentFile != null) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, T("spat"), tint = Farby.text)
                }
                Text(priecinok.absolutePath, color = Farby.tlmene, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f)) {
                if (polozky.isEmpty()) Text(T("pr_prazdny", z.pripony.joinToString(", ") { it.uppercase() }), color = Farby.tlmene, modifier = Modifier.padding(16.dp))
                val st = androidx.compose.foundation.lazy.rememberLazyListState(); PosunOvladacom(st)
                LazyColumn(state = st) {
                    items(polozky, key = { it.absolutePath }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                if (f.isDirectory) priecinok = f else { poslednyPriecinok = priecinok; zavri(); z.hotovo(f) }
                            }.padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null,
                                tint = if (f.isDirectory) Farby.fialova else Farby.ruzova, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(f.name, color = Farby.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (f.isFile) Text(velkost(f.length()), color = Farby.tlmene, fontSize = 12.sp)
                        }
                    }
                }
                Posuvnik(st)
            }
        }
    }
}

private fun velkost(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toFloat())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toFloat())
    else -> "${b / 1024} kB"
}

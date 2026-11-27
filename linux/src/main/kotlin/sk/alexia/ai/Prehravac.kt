package sk.alexia.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.DataInputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Prehrávač videí priamo v appke (PC aj Linux) – bez inštalovania VLC.
 * Obraz aj zvuk dekóduje ffmpeg (ten appka už má), zvuk riadi čas a obraz sa k nemu dorovnáva.
 */
object Prehravac {
    var subor by mutableStateOf<File?>(null)
    fun otvor(f: File) { if (f.exists()) subor = f else Platforma.oznam(T("preh_nenajdene")) }
}

private class PrehravacStav(val f: File) {
    var snimok by mutableStateOf<ImageBitmap?>(null)
    var pozicia by mutableStateOf(0.0)
    var dlzka by mutableStateOf(0.0)
    var hra by mutableStateOf(false)
    var hlasitost by mutableFloatStateOf(1f)
    var chyba by mutableStateOf("")
    private var sirka = 1280; private var vyska = 720; private var maZvuk = true
    @Volatile private var bezi = false
    private val procesy = mutableListOf<Process>()
    @Volatile private var linka: SourceDataLine? = null
    private var vlakno: Thread? = null
    private val FPS = 30

    fun info(ff: String) {
        val p = ProcessBuilder(ff, "-hide_banner", "-i", f.path).redirectErrorStream(true).start()
        val t = p.inputStream.bufferedReader().readText(); p.waitFor()
        Regex("Duration: (\\d+):(\\d+):([0-9.]+)").find(t)?.let { m ->
            dlzka = m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toDouble()
        }
        Regex("Video:.*?(\\d{2,5})x(\\d{2,5})").find(t)?.let { m ->
            val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
            val k = minOf(1.0, 1280.0 / maxOf(w, h))
            sirka = ((w * k).toInt() / 2 * 2).coerceAtLeast(2); vyska = ((h * k).toInt() / 2 * 2).coerceAtLeast(2)
        }
        maZvuk = t.contains("Audio:")
    }

    fun hraj(od: Double) {
        zastav()
        val ff = VideoJadro.ffmpeg() ?: run { chyba = T("nahovor_ffmpeg"); return }
        bezi = true; hra = true; pozicia = od
        vlakno = Thread {
            runCatching { prehravaj(ff, od) }.onFailure { e -> if (bezi) Platforma.naHlavnom { chyba = e.message ?: "" } }
            if (bezi) Platforma.naHlavnom { hra = false; bezi = false; pozicia = 0.0 }
        }.apply { isDaemon = true; start() }
    }

    fun pauza() { val p = pozicia; zastav(); pozicia = p; hra = false }

    fun zastav() {
        bezi = false
        synchronized(procesy) { procesy.forEach { it.destroy() }; procesy.clear() }
        linka?.let { runCatching { it.stop(); it.flush(); it.close() } }; linka = null
        vlakno?.join(800); vlakno = null
    }

    private fun spusti(vararg a: String): Process {
        val p = ProcessBuilder(*a).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        synchronized(procesy) { procesy += p }
        return p
    }

    private fun prehravaj(ff: String, od: Double) {
        val ss = "%.3f".format(java.util.Locale.ROOT, od)
        val w = sirka; val h = vyska
        val v = spusti(ff, "-v", "error", "-ss", ss, "-i", f.path, "-an", "-vf", "scale=$w:$h,fps=$FPS", "-f", "rawvideo", "-pix_fmt", "rgba", "-")
        // zvuk: vlastné vlákno, čas prehrávania = hodiny pre obraz
        var startNano = 0L
        if (maZvuk) {
            val a = spusti(ff, "-v", "error", "-ss", ss, "-i", f.path, "-vn", "-f", "s16le", "-ac", "2", "-ar", "44100", "-")
            val fmt = AudioFormat(44100f, 16, 2, true, false)
            val l = AudioSystem.getSourceDataLine(fmt); l.open(fmt, 44100 * 4 / 5); l.start(); linka = l
            Thread {
                val buf = ByteArray(8192)
                runCatching {
                    a.inputStream.use { i ->
                        while (bezi) {
                            val n = i.read(buf); if (n <= 0) break
                            val g = hlasitost
                            if (g < 0.999f) for (k in 0 until n - 1 step 2) {
                                val s = ((buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xff)).toShort() * g
                                val x = s.toInt().coerceIn(-32768, 32767); buf[k] = x.toByte(); buf[k + 1] = (x shr 8).toByte()
                            }
                            l.write(buf, 0, n)
                        }
                    }
                }
                if (bezi) runCatching { l.drain() }
            }.apply { isDaemon = true; start() }
        }
        fun hodiny(): Double {
            val l = linka
            return if (l != null) l.microsecondPosition / 1e6
            else { if (startNano == 0L) startNano = System.nanoTime(); (System.nanoTime() - startNano) / 1e9 }
        }
        val velkost = w * h * 4
        val ram = ByteArray(velkost)
        val info = org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
        DataInputStream(v.inputStream.buffered(velkost * 2)).use { vstup ->
            var i = 0
            while (bezi) {
                runCatching { vstup.readFully(ram) }.getOrElse { break }
                val t = i.toDouble() / FPS; i++
                // čakať na zvuk; ak obraz mešká o viac ako 0,1 s, snímok sa preskočí
                while (bezi && hodiny() < t - 0.005) Thread.sleep(3)
                if (hodiny() - t > 0.1) continue
                val img = org.jetbrains.skia.Image.makeRaster(info, ram.copyOf(), w * 4).toComposeImageBitmap()
                val poz = od + t
                Platforma.naHlavnom { if (bezi) { snimok = img; pozicia = poz } }
            }
        }
    }
}

private fun casText(s: Double) = Cas.text((s * 1000).toLong())

/** okno prehrávača – zobrazí sa nad appkou, keď je [Prehravac.subor] nastavený */
@Composable
fun PrehravacOkno() {
    val f = Prehravac.subor ?: return
    val st = remember(f) { PrehravacStav(f) }
    var tahanie by remember(f) { mutableStateOf<Float?>(null) }
    LaunchedEffect(f) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VideoJadro.ffmpeg()?.let { st.info(it) } }
        st.hraj(0.0)
    }
    DisposableEffect(f) { onDispose { st.zastav() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = { Prehravac.subor = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.9f).clip(RoundedCornerShape(18.dp)).background(Color.Black).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, color = Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                IconButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(f) }.onFailure { Platforma.otvorOdkaz(f.toURI().toString()) } }) {
                    Icon(Icons.Default.OpenInNew, T("preh_externe"), tint = Color.White)
                }
                IconButton(onClick = { Prehravac.subor = null }) { Icon(Icons.Default.Close, T("zavriet"), tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                st.snimok?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: CircularProgressIndicator(color = Farby.ruzova)
                if (st.chyba.isNotBlank()) Text("⚠ " + st.chyba, color = Farby.cervena, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                IconButton(onClick = { if (st.hra) st.pauza() else st.hraj(if (st.dlzka > 0 && st.pozicia >= st.dlzka - 0.2) 0.0 else st.pozicia) }) {
                    Icon(if (st.hra) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Text(casText(tahanie?.toDouble() ?: st.pozicia), color = Color.White, fontSize = 12.sp)
                Slider(value = (tahanie ?: st.pozicia.toFloat()).coerceIn(0f, maxOf(st.dlzka.toFloat(), 0.01f)),
                    onValueChange = { tahanie = it }, onValueChangeFinished = { tahanie?.let { st.hraj(it.toDouble()) }; tahanie = null },
                    valueRange = 0f..maxOf(st.dlzka.toFloat(), 0.01f), modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.ruzova, inactiveTrackColor = Color(0x55FFFFFF)))
                Text(casText(st.dlzka), color = Color.White, fontSize = 12.sp)
                Icon(Icons.Default.VolumeUp, null, tint = Color.White, modifier = Modifier.padding(start = 12.dp).size(18.dp))
                Slider(value = st.hlasitost, onValueChange = { st.hlasitost = it }, modifier = Modifier.width(110.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x55FFFFFF)))
            }
        }
    }
}

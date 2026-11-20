package sk.alexia.ai

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.io.FileInputStream

/**
 * Ovládač Legion Go S (a hocijaký iný gamepad) cez Linux joystick API (/dev/input/js*), bez knižníc.
 * L1/R1 a L2/R2 = prepínanie záložiek, páčky a D-pad = posúvanie, B = späť do chatu.
 */
object Ovladac {
    enum class Akcia { DALSIA, PREDOSLA, SPAT }

    /** posun aktuálnej obrazovky v px (+ dole, − hore) */
    val posun = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    val akcie = MutableSharedFlow<Akcia>(extraBufferCapacity = 16)

    // stav osí pre plynulé posúvanie
    @Volatile private var lavaY = 0f
    @Volatile private var pravaY = 0f
    @Volatile private var dpadY = 0f
    private var spustene = false

    fun spusti() {
        if (spustene) return
        spustene = true
        // zariadenia sa môžu objaviť neskôr (Steam vytvorí virtuálny ovládač) – kontrolujeme každé 3 s
        val otvorene: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        Thread {
            while (true) {
                (0..15).map { File("/dev/input/js$it") }.filter { it.exists() && it.canRead() && it.path !in otvorene }.forEach { f ->
                    otvorene += f.path
                    Thread({ citaj(f); otvorene -= f.path }, "ovladac-${f.name}").apply { isDaemon = true }.start()
                }
                Thread.sleep(3000)
            }
        }.apply { isDaemon = true; name = "ovladac-hladanie" }.start()
        // plynulé posúvanie, kým držíš páčku
        Thread {
            while (true) {
                Thread.sleep(16)
                val v = listOf(lavaY, pravaY, dpadY).maxByOrNull { kotlin.math.abs(it) } ?: 0f
                if (kotlin.math.abs(v) > 0.25f) {
                    val krok = v * kotlin.math.abs(v) * 28f
                    Platforma.naHlavnom { posun.tryEmit(krok) }
                }
            }
        }.apply { isDaemon = true; name = "ovladac-posun" }.start()
    }

    private fun citaj(f: File) = runCatching {
        val spust = BooleanArray(8) // spúšť stlačená (osi 2 a 5)
        FileInputStream(f).use { vstup ->
            val b = ByteArray(8)
            while (true) {
                var n = 0
                while (n < 8) { val r = vstup.read(b, n, 8 - n); if (r < 0) return@runCatching; n += r }
                val hodnota = ((b[4].toInt() and 0xff) or (b[5].toInt() shl 8)).toShort().toInt()
                val typ = b[6].toInt() and 0x7f          // 0x80 = počiatočný stav – ignorujeme ho len pri tlačidlách
                val pociatocny = (b[6].toInt() and 0x80) != 0
                val cislo = b[7].toInt() and 0xff
                when (typ) {
                    1 -> if (hodnota == 1 && !pociatocny) when (cislo) { // tlačidlá (rozloženie Xbox)
                        4 -> posli(Akcia.PREDOSLA)   // L1 / LB
                        5 -> posli(Akcia.DALSIA)     // R1 / RB
                        1 -> posli(Akcia.SPAT)       // B
                    }
                    2 -> {
                        val v = hodnota / 32767f
                        when (cislo) {
                            1 -> lavaY = v
                            4 -> pravaY = v
                            7 -> dpadY = v
                            2, 5 -> { // L2 / R2 – spúšte (v pokoji −1, stlačené +1)
                                val dole = v > 0.3f
                                if (dole && !spust[cislo] && !pociatocny) posli(if (cislo == 5) Akcia.DALSIA else Akcia.PREDOSLA)
                                spust[cislo] = dole
                            }
                        }
                    }
                }
            }
        }
    }

    private fun posli(a: Akcia) = Platforma.naHlavnom { akcie.tryEmit(a) }
}

/** posúvanie obrazovky ovládačom a klávesmi PageUp/PageDown */
@Composable
fun PosunOvladacom(stav: ScrollableState) {
    LaunchedEffect(stav) { Ovladac.posun.collect { stav.scrollBy(it) } }
}

private val stylPosuvnika = ScrollbarStyle(
    minimalHeight = 32.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 200,
    unhoverColor = androidx.compose.ui.graphics.Color(0x66A58BFF), hoverColor = androidx.compose.ui.graphics.Color(0xCCFF4DC4),
)

/** posuvník vpravo (dá sa chytiť myšou, prstom aj trackpadom) */
@Composable
fun BoxScope.Posuvnik(stav: LazyListState) {
    VerticalScrollbar(rememberScrollbarAdapter(stav), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp), style = stylPosuvnika)
}

@Composable
fun BoxScope.Posuvnik(stav: ScrollState) {
    VerticalScrollbar(rememberScrollbarAdapter(stav), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp), style = stylPosuvnika)
}

/** posúvateľný stĺpec obrazovky: myš, dotyk, posuvník, ovládač aj PageUp/PageDown */
@Composable
fun ColumnScope.PosuvnyStlpec(obsah: @Composable ColumnScope.() -> Unit) {
    val sc = rememberScrollState(); PosunOvladacom(sc)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize().verticalScroll(sc).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = obsah)
        Posuvnik(sc)
    }
}

@Composable
fun ColumnScope.PosuvnyZoznam(obsah: LazyListScope.() -> Unit) {
    val st = rememberLazyListState(); PosunOvladacom(st)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = st, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = obsah)
        Posuvnik(st)
    }
}

package sk.alexia.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    ChatJadro.init()
    ObrazkyJadro.init()
    Aktualizacie.skontroluj()
    Ovladac.spusti()
    if (ChatJadro.nastavenia.zdielat) Server.spusti()
    application {
        val stav = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 800.dp))
        Window(onCloseRequest = ::exitApplication, title = "Alexia AI", state = stav, onPreviewKeyEvent = {
            // Esc = späť
            if (it.type != KeyEventType.KeyDown) false
            else when {
                it.key == Key.Escape -> { naspat?.invoke(); naspat != null }
                it.key == Key.Tab && it.isCtrlPressed -> { prepni?.invoke(if (it.isShiftPressed) -1 else 1); true }
                it.key == Key.PageDown -> { Ovladac.posun.tryEmit(500f); true }
                it.key == Key.PageUp -> { Ovladac.posun.tryEmit(-500f); true }
                it.key in listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5) -> { chodNa?.invoke(listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5).indexOf(it.key)); true }
                else -> false
            }
        }) {
            AlexiaTema { Appka() }
        }
    }
}

/** čo urobí Esc (späť do chatu) */
private var naspat: (() -> Unit)? = null
/** prepnutie záložky o +1 / −1 (Ctrl+Tab, L1/R1, L2/R2) alebo priamo F1–F5 */
private var prepni: ((Int) -> Unit)? = null
private var chodNa: ((Int) -> Unit)? = null

private enum class Obrazovka { CHAT, OBRAZKY, VYLEPSIT, MODELY, NASTAVENIA }

@Composable
private fun Appka() {
    var obrazovka by remember { mutableStateOf(Obrazovka.CHAT) }
    naspat = if (obrazovka != Obrazovka.CHAT) ({ obrazovka = Obrazovka.CHAT }) else null
    val vsetky = Obrazovka.entries
    prepni = { d -> obrazovka = vsetky[(obrazovka.ordinal + d + vsetky.size) % vsetky.size] }
    chodNa = { i -> obrazovka = vsetky[i] }
    LaunchedEffect(Unit) {
        Ovladac.akcie.collect { a ->
            when (a) {
                Ovladac.Akcia.DALSIA -> prepni?.invoke(1)
                Ovladac.Akcia.PREDOSLA -> prepni?.invoke(-1)
                Ovladac.Akcia.SPAT -> naspat?.invoke()
            }
        }
    }
    val snack = remember { SnackbarHostState() }
    val oznam = Platforma.oznam
    LaunchedEffect(oznam) { oznam?.let { snack.showSnackbar(it); Platforma.oznam = null } }
    // v pamäti je vždy len jeden model: obrázky alebo chat
    LaunchedEffect(obrazovka) {
        when (obrazovka) {
            Obrazovka.OBRAZKY -> ObrazkyJadro.aktivnyModel?.let { m ->
                if (File(m).exists() || ObrazkyJadro.aktivnaSada()?.let { ObrazkyJadro.mamSadu(it) } == true) ObrazkyJadro.nacitaj(m)
            }
            Obrazovka.CHAT -> { ObrazkyJadro.uvolniPamat(); VylepsenieJadro.uvolniPamat(); ChatJadro.obnov() }
            Obrazovka.VYLEPSIT -> ObrazkyJadro.uvolniPamat()
            else -> {}
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Farby.pozadie)
            .background(Brush.radialGradient(listOf(Farby.fialova.copy(alpha = 0.16f), Color.Transparent), radius = 900f))
    ) {
        // pozadie chatu (obrázok, ktorý si vytvoril)
        val nn = ChatJadro.nastavenia
        if (obrazovka == Obrazovka.CHAT && nn.pozadieChatu != null) {
            val bmp = remember(nn.pozadieChatu, nn.pozadieVerzia) { nn.pozadieChatu?.let { Platforma.nacitajObrazok(File(it)) } }
            if (bmp != null) {
                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = nn.stmavenie / 100f)))
            }
        }
        // na širokej obrazovke obsah v strede, aby sa dobre čítal
        Aktualizacia()
        val n = ChatJadro.nastavenia
        val spodok by androidx.compose.animation.core.animateDpAsState(
            if (n.posunKlavesnica && Klavesnica.pise) maxHeight * (n.vyskaKlavesnice / 100f) else 0.dp, label = "klavesnica")
        Column(Modifier.fillMaxHeight().widthIn(max = 960.dp).fillMaxWidth().align(Alignment.TopCenter).padding(bottom = spodok)) {
        Zalozky(obrazovka) { obrazovka = it }
        Box(Modifier.weight(1f)) {
        when (obrazovka) {
            Obrazovka.CHAT -> ChatObrazovka(
                naModely = { obrazovka = Obrazovka.MODELY },
                naNastavenia = { obrazovka = Obrazovka.NASTAVENIA },
                naObrazky = { obrazovka = Obrazovka.OBRAZKY },
                naVylepsit = { obrazovka = Obrazovka.VYLEPSIT },
            )
            Obrazovka.MODELY -> ModelyObrazovka { obrazovka = Obrazovka.CHAT }
            Obrazovka.NASTAVENIA -> NastaveniaObrazovka { obrazovka = Obrazovka.CHAT }
            Obrazovka.OBRAZKY -> ObrazkyObrazovka { obrazovka = Obrazovka.CHAT }
            Obrazovka.VYLEPSIT -> VylepsenieObrazovka { obrazovka = Obrazovka.CHAT }
        }
        }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        Platforma.ziadostOVyber?.let { z -> VyberSuboru(z) { Platforma.ziadostOVyber = null } }
    }
}

// ---------------------------------------------------------------- záložky (ako v hre: L1/R1)

@Composable
private fun Zalozky(aktivna: Obrazovka, zvol: (Obrazovka) -> Unit) {
    val polozky = listOf(
        Triple(Obrazovka.CHAT, Icons.Default.Forum, "chat"),
        Triple(Obrazovka.OBRAZKY, Icons.Default.Image, "obrazky"),
        Triple(Obrazovka.VYLEPSIT, Icons.Default.AutoFixHigh, "vylepsit_kratko"),
        Triple(Obrazovka.MODELY, Icons.Default.Memory, "modely"),
        Triple(Obrazovka.NASTAVENIA, Icons.Default.Tune, "nastavenia"),
    )
    val vsetky = Obrazovka.entries
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Tlacidlo("L1") { zvol(vsetky[(aktivna.ordinal - 1 + vsetky.size) % vsetky.size]) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
            polozky.forEach { (o, ikona, kluc) ->
                val je = o == aktivna
                Row(
                    Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(50))
                        .background(if (je) Farby.prechod else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                        .clickable { zvol(o) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ikona, null, tint = if (je) Color.White else Farby.tlmene, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(T(kluc), color = if (je) Color.White else Farby.tlmene, fontSize = 14.sp, fontWeight = if (je) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
        Tlacidlo("R1") { zvol(vsetky[(aktivna.ordinal + 1) % vsetky.size]) }
    }
}

/** malé „tlačidlo ovládača“ na okraji záložiek */
@Composable
private fun Tlacidlo(nazov: String, klik: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Farby.okraj, RoundedCornerShape(8.dp)).background(Farby.plocha)
            .clickable(onClick = klik).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(nazov, color = Farby.text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

// ---------------------------------------------------------------- spoločné prvky

@Composable
private fun GradientText(text: String, size: Int, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = TextStyle(brush = Farby.prechod, fontSize = size.sp, fontWeight = FontWeight.Bold))
}

@Composable
private fun Hlavicka(nadpis: String, podnadpis: String? = null, spat: (() -> Unit)? = null, akcie: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (spat != null) {
            IconButton(onClick = spat) { Icon(Icons.AutoMirrored.Filled.ArrowBack, T("spat"), tint = Farby.text) }
        } else {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(36.dp).clip(CircleShape).background(Farby.prechod), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            if (spat == null) GradientText(nadpis, 20) else Text(nadpis, color = Farby.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!podnadpis.isNullOrBlank()) Text(podnadpis, color = Farby.tlmene, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        akcie()
    }
}

@Composable
private fun GradientTlacidlo(text: String, ikona: androidx.compose.ui.graphics.vector.ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Farby.prechod else Brush.linearGradient(listOf(Farby.plocha2, Farby.plocha2)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ikona != null) { Icon(ikona, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Karta(modifier: Modifier = Modifier, obsah: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Farby.plocha)
            .border(1.dp, Farby.okraj, RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = obsah,
    )
}

// ---------------------------------------------------------------- chat

@Composable
private fun ChatObrazovka(naModely: () -> Unit, naNastavenia: () -> Unit, naObrazky: () -> Unit, naVylepsit: () -> Unit) {
    val j = ChatJadro
    val zoznam = rememberLazyListState()
    var vstup by remember { mutableStateOf("") }
    var rezimObrazka by remember { mutableStateOf(false) }
    var rezimVidea by remember { mutableStateOf(false) }
    val o = ObrazkyJadro
    val posledny = j.spravy.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(j.spravy.size, posledny) { if (j.spravy.isNotEmpty()) zoznam.scrollToItem(j.spravy.lastIndex, Int.MAX_VALUE / 2) }
    PosunOvladacom(zoznam)
    // klávesnica sa otvorila → posledná správa ostane viditeľná
    LaunchedEffect(Klavesnica.pise) { if (Klavesnica.pise && j.spravy.isNotEmpty()) { kotlinx.coroutines.delay(250); zoznam.scrollToItem(j.spravy.lastIndex, Int.MAX_VALUE / 2) } }

    Column(Modifier.fillMaxSize()) {
        val pod = when (j.stav) {
            StavModelu.PRIPRAVENY -> if (j.generuje && j.tokenovZaSekundu > 0) "${j.nazovModelu()} · ${"%.1f".format(j.tokenovZaSekundu)} tok/s" else j.nazovModelu()
            StavModelu.NACITAVA -> T("nacitavam_model")
            StavModelu.CHYBA -> T("chyba_modelu")
            StavModelu.ZIADNY -> T("ziadny_model_kratko")
        }
        Hlavicka(T("app_name"), pod) {
            IconButton(onClick = { j.novyChat() }, enabled = j.spravy.isNotEmpty()) { Icon(Icons.Default.AddComment, T("novy_chat"), tint = Farby.text) }
        }

        Box(Modifier.weight(1f)) {
            if (j.spravy.isEmpty()) {
                Uvod(naModely) { vstup = it }
            } else {
                LazyColumn(state = zoznam, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(j.spravy) { i, s ->
                        if (s.rola == "user") BublinaPouzivatel(s.text)
                        else if (s.jeObrazkova && (s.video != null || s.stavText.isNotEmpty())) BublinaVideo(s)
                        else if (s.jeObrazkova) BublinaObrazok(s)
                        else BublinaAI(s.text, pise = j.generuje && i == j.spravy.lastIndex, posledna = i == j.spravy.lastIndex && !j.generuje)
                    }
                }
                Posuvnik(zoznam)
            }
        }

        // vstup
        Row(
            Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Farby.plocha)
                .border(1.dp, Farby.okraj, RoundedCornerShape(28.dp)).padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { rezimObrazka = !rezimObrazka; if (rezimObrazka) rezimVidea = false }) {
                Icon(Icons.Default.Brush, T("chat_obr_rezim"), tint = if (rezimObrazka) Farby.ruzova else Farby.tlmene)
            }
            IconButton(onClick = { rezimVidea = !rezimVidea; if (rezimVidea) rezimObrazka = false }) {
                Icon(Icons.Default.Movie, T("video_rezim"), tint = if (rezimVidea) Farby.ruzova else Farby.tlmene)
            }
            TextField(
                value = vstup, onValueChange = { vstup = it },
                placeholder = { Text(T(if (rezimVidea) "video_popis" else if (rezimObrazka) "chat_obr_popis" else if (j.stav == StavModelu.PRIPRAVENY) "napis_spravu" else "ziadny_model_kratko"), color = Farby.tlmene) },
                modifier = Modifier.weight(1f).nadKlavesnicou(), maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    cursorColor = Farby.ruzova, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text,
                ),
            )
            val jeObr = rezimObrazka || rezimVidea || popisObrazka(vstup) != null || popisVidea(vstup) != null
            val moze = vstup.isNotBlank() && (if (jeObr) !o.generuje && !VideoJadro.pracuje else j.stav == StavModelu.PRIPRAVENY && !j.generuje)
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (j.generuje) Brush.linearGradient(listOf(Farby.cervena, Farby.cervena)) else if (moze) Farby.prechod else Brush.linearGradient(listOf(Farby.plocha2, Farby.plocha2)))
                    .clickable(enabled = j.generuje || moze) {
                        if (j.generuje) j.zastav() else { j.posli(vstup, rezimObrazka, rezimVidea); vstup = "" }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (j.generuje) Icon(Icons.Default.Stop, T("zastavit"), tint = Color.White)
                else Icon(Icons.AutoMirrored.Filled.Send, T("poslat"), tint = if (moze) Color.White else Farby.tlmene, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Uvod(naModely: () -> Unit, navrh: (String) -> Unit) {
    val j = ChatJadro
    val pulz = rememberInfiniteTransition(label = "pulz")
    val a by pulz.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).alpha(a).clip(CircleShape).background(Farby.prechod), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        GradientText(T("ahoj_nadpis"), 26)
        Spacer(Modifier.height(6.dp))
        Text(T("ahoj_text"), color = Farby.tlmene, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        when (j.stav) {
            StavModelu.PRIPRAVENY -> {
                listOf("navrh_1", "navrh_2", "navrh_3").forEach { r ->
                    val t = T(r)
                    Box(
                        Modifier.padding(vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Farby.prechodJemny)
                            .border(1.dp, Farby.okraj, RoundedCornerShape(16.dp)).clickable { navrh(t) }.padding(14.dp),
                    ) { Text(t, color = Farby.text) }
                }
            }
            StavModelu.NACITAVA -> {
                CircularProgressIndicator(color = Farby.ruzova, strokeWidth = 3.dp)
                Spacer(Modifier.height(10.dp)); Text(T("nacitavam_model"), color = Farby.tlmene)
            }
            else -> Karta {
                Text(T(if (j.stav == StavModelu.CHYBA) "chyba_modelu" else "ziadny_model"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                if (j.stav == StavModelu.CHYBA && j.chyba.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(j.chyba, color = Farby.cervena, fontSize = 13.sp) }
                Spacer(Modifier.height(12.dp))
                GradientTlacidlo(T("vybrat_model"), Icons.Default.Memory, onClick = naModely)
            }
        }
    }
}

@Composable
private fun BublinaPouzivatel(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SelectionContainer {
            Text(
                text, color = Color.White, fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(Farby.prechod).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** rozdelí odpoveď na premýšľanie (<think>…</think>, napr. Qwen3) a samotnú odpoveď */
private fun rozdel(t: String): Pair<String?, String> {
    val z = t.indexOf("<think>"); if (z < 0) return null to t
    val k = t.indexOf("</think>", z)
    return if (k < 0) t.substring(z + 7).trim() to "" else t.substring(z + 7, k).trim() to (t.substring(0, z) + t.substring(k + 8)).trim()
}

@Composable
private fun BublinaAI(text: String, pise: Boolean, posledna: Boolean) {
    val schranka = LocalClipboardManager.current
    val skopirovane = T("skopirovane")
    val (myslienky, odpoved) = rozdel(text)
    var ukazMyslienky by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Farby.prechod), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (myslienky != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Farby.plocha2).clickable { ukazMyslienky = !ukazMyslienky }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Psychology, null, tint = Farby.tlmene, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(T("premysla"), color = Farby.tlmene, fontSize = 12.sp)
                    Icon(if (ukazMyslienky) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                }
                AnimatedVisibility(ukazMyslienky) {
                    Text(myslienky, color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                }
                Spacer(Modifier.height(6.dp))
            }
            if (odpoved.isEmpty() && pise) {
                Bodky()
            } else {
                val kusy = remember(odpoved) { rozdelKod(odpoved) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kusy.forEachIndexed { i, k ->
                        val koniec = if (pise && i == kusy.lastIndex) " ▍" else ""
                        if (k.jazyk != null) BlokKodu(k.text, k.jazyk)
                        else if (k.text.isNotBlank() || koniec.isNotEmpty()) SelectionContainer {
                            Text(k.text.trim('\n') + koniec, color = Farby.text, fontSize = 15.sp, lineHeight = 22.sp)
                        }
                    }
                }
            }
            if (!pise && odpoved.isNotEmpty()) {
                Row(Modifier.padding(top = 2.dp)) {
                    IconButton(onClick = { schranka.setText(AnnotatedString(odpoved)); Platforma.oznam(skopirovane) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, T("kopirovat"), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { if (Hlas.hovori == text) Hlas.zastav() else Hlas.hovor(odpoved) }, modifier = Modifier.size(32.dp)) {
                        Icon(if (Hlas.hovori == text) Icons.Default.StopCircle else Icons.Default.VolumeUp, T("hlas_precitat"), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                    }
                    if (posledna) {
                        IconButton(onClick = { ChatJadro.znova() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, T("znova"), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BublinaVideo(s: Sprava) {
    val v = VideoJadro
    val cesta = s.video
    var znova by remember { mutableStateOf<Float?>(null) }   // priebeh pri zmene hudby
    var verzia by remember { mutableStateOf(0) }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Farby.prechod), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Movie, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (cesta != null && File(cesta).isDirectory) {
                val proj = remember(cesta) { VideoJadro.Projekt(File(cesta)) }
                key(verzia) { VideoPrehravac(proj) }
                val z = znova
                if (z != null) {
                    Text(T("video_skladam", (z * 100).roundToInt()), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    LinearProgressIndicator(progress = { z }, modifier = Modifier.width(300.dp).height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ulozene = T("video_ulozene")
                    OutlinedButton(onClick = { val f = v.uloz(proj); Platforma.oznam(if (f != null) ulozene + " " + f.parent else T("obr_neulozene")) },
                        enabled = proj.mp4.exists(), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.SaveAlt, null, tint = Farby.text, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(T("video_ulozit"), color = Farby.text, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = {
                        Platforma.vyberSubor(T("video_hudba_vyber"), setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus")) { f ->
                            proj.hudba = f.path; ChatJadro.nastavenia.hudbaVidea = f.path; ChatJadro.nastavenia.uloz()
                            znova = 0f
                            v.exportuj(proj, { p -> znova = p }) { ok, e -> znova = null; verzia++; if (!ok) Platforma.oznam("⚠ $e") }
                        }
                    }, enabled = znova == null && !v.pracuje, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.MusicNote, null, tint = Farby.text, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(T("video_hudba"), color = Farby.text, fontSize = 13.sp)
                    }
                    IconButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(proj.mp4) }.onFailure { Platforma.otvorOdkaz(proj.mp4.toURI().toString()) } }, enabled = proj.mp4.exists()) {
                        Icon(Icons.Default.OpenInNew, T("video_otvorit"), tint = Farby.tlmene, modifier = Modifier.size(18.dp))
                    }
                    proj.obrazky.firstOrNull()?.let { f ->
                        IconButton(onClick = { ObrazkyJadro.nastavPozadieChatu(f) }) { Icon(Icons.Default.Wallpaper, T("poz_chat_tlacidlo"), tint = Farby.tlmene, modifier = Modifier.size(18.dp)) }
                    }
                }
            } else if (s.text.isNotEmpty()) Text(s.text, color = Farby.cervena, fontSize = 14.sp)
            else Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Farby.plocha).padding(14.dp)) {
                val ff = v.priebehFfmpeg
                Text(if (ff != null) T("video_ffmpeg", (ff * 100).roundToInt()) else s.stavText, color = Farby.text, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                val p = ff ?: s.priebeh
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                if (ObrazkyJadro.generuje) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { ObrazkyJadro.priebeh }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)), color = Farby.fialova, trackColor = Farby.plocha2)
                    TextButton(onClick = { ObrazkyJadro.zrus() }) { Text(T("zrusit"), color = Farby.tlmene) }
                }
                Text(T("video_info"), color = Farby.tlmene, fontSize = 12.sp)
            }
        }
    }
}

/** náhľad videa priamo v chate – rovnaké efekty ako v MP4, vykresľované naživo (menšie rozlíšenie) */
@Composable
private fun VideoPrehravac(proj: VideoJadro.Projekt) {
    val obr = remember(proj) {
        proj.obrazky.mapNotNull { f -> runCatching {
            val i = javax.imageio.ImageIO.read(f)
            val k = 600.0 / i.width
            java.awt.image.BufferedImage((i.width * k).toInt(), (i.height * k).toInt(), java.awt.image.BufferedImage.TYPE_INT_RGB).also { b ->
                val g = b.createGraphics(); g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(i, 0, 0, b.width, b.height, null); g.dispose()
            }
        }.getOrNull() }
    }
    if (obr.isEmpty()) return
    val r = remember(obr) { VideoJadro.Renderer(obr, proj.seed, 432, 768) }
    var hra by remember { mutableStateOf(false) }
    var cas by remember { mutableStateOf(0.0) }
    var snimka by remember { mutableStateOf<ImageBitmap?>(null) }
    val dlzka = proj.dlzka
    LaunchedEffect(Unit) { snimka = r.snimka(0.6).toComposeImageBitmap() }
    LaunchedEffect(hra) {
        if (!hra) { VideoJadro.zastavHudbu(); return@LaunchedEffect }
        proj.hudba?.takeIf { File(it).exists() }?.let { VideoJadro.hrajHudbu(it) }
        val start = System.nanoTime()
        while (hra) {
            withFrameNanos { }
            cas = (System.nanoTime() - start) / 1e9
            if (cas >= dlzka) { hra = false; cas = 0.0; break }
            snimka = withContext(Dispatchers.Default) { r.snimka(cas).toComposeImageBitmap() }
        }
        VideoJadro.zastavHudbu()
    }
    DisposableEffect(Unit) { onDispose { VideoJadro.zastavHudbu() } }
    Box(Modifier.width(270.dp).height(480.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black).clickable { hra = !hra }, contentAlignment = Alignment.Center) {
        snimka?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (!hra) Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, T("video_prehrat"), tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(((cas / dlzka).toFloat()).coerceIn(0f, 1f)).height(3.dp).background(Farby.ruzova))
    }
}

@Composable
private fun BublinaObrazok(s: Sprava) {
    val o = ObrazkyJadro
    val cesta = s.obrazok
    val obr = remember(cesta) { cesta?.let { Platforma.nacitajObrazok(File(it)) } }
    val ulozene = T("obr_ulozene"); val neulozene = T("obr_neulozene")
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Farby.prechod), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Brush, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            when {
                obr != null -> {
                    Image(obr, null, Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(obr.width.toFloat() / obr.height).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
                    Row(Modifier.padding(top = 2.dp)) {
                        IconButton(onClick = { Platforma.oznam(if (o.ulozDoGalerie(File(cesta!!))) ulozene else neulozene) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.SaveAlt, T("obr_ulozit"), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { o.nastavPozadieChatu(File(cesta!!)) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Wallpaper, T("poz_chat_tlacidlo"), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                s.text.isNotEmpty() -> Text(s.text, color = Farby.cervena, fontSize = 14.sp)
                cesta != null -> Text(T("chat_obr_chyba"), color = Farby.tlmene, fontSize = 14.sp)
                else -> Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Farby.plocha).padding(14.dp)) {
                    val nacitava = o.stav == StavModelu.NACITAVA
                    Text(T(if (nacitava) "chat_obr_nacitavam" else "chat_obr_kreslim", (o.priebeh * 100).roundToInt()), color = Farby.text, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    if (nacitava || o.priebeh <= 0f) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    else LinearProgressIndicator(progress = { o.priebeh }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    TextButton(onClick = { o.zrus() }) { Text(T("zrusit"), color = Farby.tlmene) }
                }
            }
        }
    }
}

/** kus odpovede: obyčajný text (jazyk = null) alebo kód medzi ``` */
private class KusTextu(val text: String, val jazyk: String?)

private fun rozdelKod(t: String): List<KusTextu> {
    val z = mutableListOf<KusTextu>(); var i = 0
    while (i < t.length) {
        val a = t.indexOf("```", i)
        if (a < 0) { z += KusTextu(t.substring(i), null); break }
        if (a > i) z += KusTextu(t.substring(i, a), null)
        val riadok = t.indexOf('\n', a + 3)
        if (riadok < 0) { z += KusTextu("", t.substring(a + 3).trim()); break } // ešte píše prvý riadok
        val jazyk = t.substring(a + 3, riadok).trim()
        val b = t.indexOf("```", riadok + 1)
        if (b < 0) { z += KusTextu(t.substring(riadok + 1), jazyk); break } // kód sa ešte píše
        z += KusTextu(t.substring(riadok + 1, b).trimEnd('\n'), jazyk)
        i = b + 3
    }
    return z
}

/** tmavé okno s kódom ako vo VS Code – s názvom jazyka a tlačidlom Kopírovať */
@Composable
private fun BlokKodu(kod: String, jazyk: String) {
    val schranka = LocalClipboardManager.current
    val skopirovane = T("skopirovane")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1E1E)).border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF2D2D2D)).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(jazyk.ifBlank { "kód" }, color = Color(0xFF9CDCFE), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
            TextButton(onClick = { schranka.setText(AnnotatedString(kod)); Platforma.oznam(skopirovane) }) {
                Icon(Icons.Default.ContentCopy, null, tint = Color(0xFFCCCCCC), modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                Text(T("kopirovat"), color = Color(0xFFCCCCCC), fontSize = 12.sp)
            }
        }
        SelectionContainer {
            Text(kod, color = Color(0xFFD4D4D4), fontSize = 13.sp, lineHeight = 19.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
        }
    }
}

@Composable
private fun Bodky() {
    val t = rememberInfiniteTransition(label = "bodky")
    Row(Modifier.padding(vertical = 8.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.padding(end = 6.dp).size(8.dp).alpha(a).clip(CircleShape).background(Farby.ruzova))
        }
    }
}

// ---------------------------------------------------------------- modely

@Composable
private fun ModelyObrazovka(spat: () -> Unit) {
    val j = ChatJadro
    var subory by remember { mutableStateOf(Modely.zoznam()) }
    var priebeh by remember { mutableStateOf<Float?>(null) }
    var cinnost by remember { mutableStateOf("") }
    var zrusit by remember { mutableStateOf(false) }
    var odkaz by remember { mutableStateOf("") }
    var naZmazanie by remember { mutableStateOf<File?>(null) }
    val chybaPrefix = T("chyba")
    val textStahujem = T("stahujem")
    val textKopirujem = T("kopirujem")

    fun hotovo(f: File?, e: Throwable?) {
        priebeh = null; subory = Modely.zoznam()
        if (f != null) j.nacitajModel(f.path)
        if (e != null && e !is InterruptedException) Platforma.oznam("$chybaPrefix: ${e.message}")
    }

    fun stiahni(url: String) {
        if (priebeh != null) return
        priebeh = 0f; cinnost = textStahujem; zrusit = false
        thread {
            val r = runCatching { Modely.stiahni(url, { p -> priebeh = p }, { zrusit }) }
            Platforma.naHlavnom { hotovo(r.getOrNull(), r.exceptionOrNull()) }
        }
    }

    val nadpisVyberu = T("zo_suboru")
    fun vyberZoSuboru() {
        if (priebeh != null) return
        Platforma.vyberSubor(nadpisVyberu, setOf("gguf")) { f ->
        priebeh = 0f; cinnost = textKopirujem; zrusit = false
        thread {
            val r = runCatching { Modely.importuj(f, priebeh = { p -> priebeh = p }) }
            Platforma.naHlavnom { hotovo(r.getOrNull(), r.exceptionOrNull()) }
        }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Hlavicka(T("modely"), spat = spat)
        PosuvnyZoznam {
            priebeh?.let { p ->
                item {
                    Karta {
                        Text("$cinnost ${(p * 100).roundToInt()} %", color = Farby.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                        TextButton(onClick = { zrusit = true }) { Text(T("zrusit"), color = Farby.tlmene) }
                    }
                }
            }

            item { Text(T("modely_moje"), color = Farby.tlmene, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            if (subory.isEmpty()) item { Text(T("modely_ziadne"), color = Farby.tlmene) }
            items(subory, key = { it.path }) { f ->
                val aktivny = j.jeAktivny(f)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (aktivny) Farby.prechodJemny else Brush.linearGradient(listOf(Farby.plocha, Farby.plocha)))
                        .border(1.dp, if (aktivny) Farby.fialova else Farby.okraj, RoundedCornerShape(18.dp))
                        .clickable(enabled = !aktivny && j.stav != StavModelu.NACITAVA) { j.nacitajModel(f.path) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (aktivny) Icons.Default.CheckCircle else Icons.Default.Memory, null, tint = if (aktivny) Farby.zelena else Farby.tlmene)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.nameWithoutExtension, color = Farby.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val info = if (aktivny && j.stav == StavModelu.NACITAVA) T("nacitavam_model")
                            else if (aktivny) "${T("aktivny")} · ${Modely.velkost(f)}" else Modely.velkost(f)
                        Text(info, color = Farby.tlmene, fontSize = 12.sp)
                    }
                    IconButton(onClick = { naZmazanie = f }) { Icon(Icons.Default.DeleteOutline, T("zmazat"), tint = Farby.tlmene) }
                }
            }

            item {
                OutlinedButton(onClick = { vyberZoSuboru() }, enabled = priebeh == null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                    Icon(Icons.Default.FolderOpen, null, tint = Farby.text); Spacer(Modifier.width(8.dp)); Text(T("zo_suboru"), color = Farby.text)
                }
            }

            item { Spacer(Modifier.height(6.dp)); Text(T("modely_odporucane"), color = Farby.tlmene, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            items(Modely.odporucane) { m ->
                val uz = subory.any { it.name.equals(Modely.posledny(m.url), ignoreCase = true) }
                Karta {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.nazov, color = Farby.text, fontWeight = FontWeight.SemiBold)
                            Text("${m.popis} · ~${m.mb} MB", color = Farby.tlmene, fontSize = 12.sp)
                            if (m.ram == "na hrane") Text(T("ram_na_hrane"), color = Color(0xFFFFB74D), fontSize = 12.sp)
                        }
                        if (uz) Icon(Icons.Default.DownloadDone, null, tint = Farby.zelena)
                        else GradientTlacidlo(T("stiahnut"), Icons.Default.Download, enabled = priebeh == null) { stiahni(m.url) }
                    }
                }
            }

            item {
                Karta {
                    Text(T("modely_vlastny"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = odkaz, onValueChange = { odkaz = it }, singleLine = true, modifier = Modifier.fillMaxWidth().nadKlavesnicou(),
                        placeholder = { Text("https://huggingface.co/…/model.gguf", color = Farby.tlmene, fontSize = 13.sp) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                    )
                    Spacer(Modifier.height(10.dp))
                    GradientTlacidlo(T("stiahnut"), Icons.Default.Download, enabled = priebeh == null && (odkaz.trim().startsWith("http") || odkaz.contains("huggingface.co") || odkaz.contains("hf.co"))) { stiahni(odkaz) }
                }
            }
            item { Text(T("modely_info"), color = Farby.tlmene, fontSize = 12.sp) }
        }
    }

    naZmazanie?.let { f ->
        AlertDialog(
            onDismissRequest = { naZmazanie = null },
            containerColor = Farby.plocha,
            title = { Text(T("zmazat_model_otazka", f.nameWithoutExtension), color = Farby.text) },
            confirmButton = { TextButton(onClick = { j.zmazModel(f); subory = subory - f; naZmazanie = null }) { Text(T("zmazat"), color = Farby.cervena) } },
            dismissButton = { TextButton(onClick = { naZmazanie = null }) { Text(T("zrusit"), color = Farby.tlmene) } },
        )
    }
}

// ---------------------------------------------------------------- nastavenia

@Composable
private fun NastaveniaObrazovka(spat: () -> Unit) {
    val n = ChatJadro.nastavenia
    val j = ChatJadro
    val jadra = Runtime.getRuntime().availableProcessors()
    fun uloz() { n.uloz(); j.aktualizujVzorkovanie() }
    Column(Modifier.fillMaxSize()) {
        Hlavicka(T("nastavenia"), spat = spat)
        PosuvnyStlpec {
            Karta { PrepojenieKarta() }
            Karta {
                Text(T("hlas_karta"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Text(T("hlas_jazyk"), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto", "sk", "en").forEach { k ->
                        FilterChip(selected = n.jazykOdpovedi == k, onClick = { n.jazykOdpovedi = k; n.uloz() }, label = { Text(T("jazyk_$k")) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
                Text(T("hlas_jazyk_info"), color = Farby.tlmene, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().clickable { n.citatNahlas = !n.citatNahlas; n.uloz() }.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(T("hlas_citat"), color = Farby.text, modifier = Modifier.weight(1f))
                    Switch(checked = n.citatNahlas, onCheckedChange = { n.citatNahlas = it; n.uloz() }, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                }
                Text(T("hlas_lili", Hlas.MB), color = Farby.tlmene, fontSize = 12.sp)
                val p = Hlas.priebeh
                when {
                    p != null -> LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    Hlas.mam() -> Text(T("hlas_mam"), color = Farby.zelena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    else -> GradientTlacidlo(T("hlas_stiahnut"), Icons.Default.Download, Modifier.padding(top = 6.dp)) { Hlas.stiahni() }
                }
                if (Hlas.chyba.isNotBlank()) Text(Hlas.chyba, color = Farby.cervena, fontSize = 13.sp)
            }
            Karta {
                Text(T("akt_karta"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Text(T("akt_teraz", Aktualizacie.mojaVerzia), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                if (Aktualizacie.kontroluje) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                else GradientTlacidlo(T("akt_skontrolovat"), Icons.Default.SystemUpdate, Modifier.fillMaxWidth()) { Aktualizacie.skontroluj(rucne = true) }
            }
            Karta {
                Text(T("poz_chat_karta"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                val bmp = remember(n.pozadieChatu, n.pozadieVerzia) { n.pozadieChatu?.let { Platforma.nacitajObrazok(File(it), 4) } }
                if (bmp == null) Text(T("poz_chat_ziadne"), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                else {
                    Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp))) {
                        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = n.stmavenie / 100f)))
                        Text(T("ahoj_nadpis"), color = Farby.text, modifier = Modifier.align(Alignment.Center))
                    }
                    Text(T("poz_chat_stmavenie", n.stmavenie), color = Farby.text, fontSize = 14.sp)
                    Slider(value = n.stmavenie.toFloat(), onValueChange = { n.stmavenie = it.roundToInt() }, onValueChangeFinished = { n.uloz() }, valueRange = 0f..90f,
                        colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                    OutlinedButton(onClick = { ObrazkyJadro.zrusPozadieChatu() }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Farby.tlmene); Spacer(Modifier.width(6.dp)); Text(T("poz_chat_zrusit"), color = Farby.tlmene)
                    }
                }
            }
            Karta {
                Text(T("kl_karta"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().clickable { n.posunKlavesnica = !n.posunKlavesnica; n.uloz() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(T("kl_posun"), color = Farby.text, modifier = Modifier.weight(1f))
                    Switch(checked = n.posunKlavesnica, onCheckedChange = { n.posunKlavesnica = it; n.uloz() }, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                }
                if (n.posunKlavesnica) {
                    Text(T("kl_vyska", n.vyskaKlavesnice), color = Farby.text, fontSize = 14.sp)
                    Slider(value = n.vyskaKlavesnice.toFloat(), onValueChange = { n.vyskaKlavesnice = it.roundToInt() }, onValueChangeFinished = { n.uloz() }, valueRange = 20f..60f,
                        colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                }
                Row(Modifier.fillMaxWidth().clickable { n.otvoritKlavesnicu = !n.otvoritKlavesnicu; n.uloz() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(T("kl_otvorit"), color = Farby.text, modifier = Modifier.weight(1f))
                    Switch(checked = n.otvoritKlavesnicu, onCheckedChange = { n.otvoritKlavesnicu = it; n.uloz() }, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                }
                Text(T("kl_info"), color = Farby.tlmene, fontSize = 12.sp)
            }
            Karta {
                Text(T("nast_system"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = n.systemovyPrompt, onValueChange = { n.systemovyPrompt = it; n.uloz() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).nadKlavesnicou(),
                    placeholder = { Text(T("nast_system_hint"), color = Farby.tlmene, fontSize = 13.sp) },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                )
            }
            Karta {
                Text(T("nast_teplota", n.teplota), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Slider(value = n.teplota, onValueChange = { n.teplota = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { uloz() }, valueRange = 0f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                Text(T("nast_teplota_info"), color = Farby.tlmene, fontSize = 12.sp)
            }
            Karta {
                Text(T("nast_max", n.maxTokenov), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Slider(value = n.maxTokenov.toFloat(), onValueChange = { n.maxTokenov = ((it / 128).roundToInt() * 128).coerceAtLeast(128) }, onValueChangeFinished = { uloz() }, valueRange = 128f..4096f,
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
            }
            Karta {
                Text(T("nast_kontext"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048, 4096, 8192, 16384).forEach { k ->
                        FilterChip(selected = n.kontext == k, onClick = { n.kontext = k; uloz() }, label = { Text("${k / 1024}k") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
                Text(T("nast_kontext_info"), color = Farby.tlmene, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text(T("nast_vlakna", n.vlakna), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Slider(value = n.vlakna.toFloat(), onValueChange = { n.vlakna = it.roundToInt() }, onValueChangeFinished = { uloz() }, valueRange = 1f..jadra.toFloat(), steps = (jadra - 2).coerceAtLeast(0),
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                if (j.potrebujeZnovuNacitat()) {
                    Spacer(Modifier.height(4.dp))
                    GradientTlacidlo(T("nast_znova"), Icons.Default.Refresh) { n.aktivnyModel?.let { j.nacitajModel(it) } }
                }
            }
            Karta {
                Text(T("jazyk"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Slovenčina", true to "English").forEach { (en, nazov) ->
                        FilterChip(selected = T.anglicky == en, onClick = { T.anglicky = en; n.uloz() }, label = { Text(nazov) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
            }
            Karta(Modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = Farby.tlmene, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(T("nast_info") + " " + T("gpu_info"), color = Farby.tlmene, fontSize = 13.sp)
                }
                if (j.popisModelu.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(j.popisModelu, color = Farby.tlmene, fontSize = 12.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- obrázky

@Composable
private fun ObrazkyObrazovka(spat: () -> Unit) {
    val o = ObrazkyJadro
    var modely by remember { mutableStateOf(o.modely()) }
    var sady by remember { mutableStateOf(o.sady()) }
    var popisStahovania by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var negativny by remember { mutableStateOf("blurry, low quality, deformed") }
    var pozadie by remember { mutableStateOf("bez") }
    var vlastnePozadie by remember { mutableStateOf("") }
    /** popis + vybrané pozadie (anglicky, tomu modely rozumejú najlepšie) */
    fun popisSPozadim(): String {
        val p = when (pozadie) {
            "bez" -> ""
            "vlastne" -> vlastnePozadie.trim()
            "nahodne" -> POZADIA.filter { it.first !in setOf("bez", "nahodne", "vlastne") }.random().second
            else -> POZADIA.first { it.first == pozadie }.second
        }
        return if (p.isEmpty()) prompt else "${prompt.trim().trimEnd(',', '.')}, background: $p"
    }
    var rozmer by remember { mutableIntStateOf(384) }
    var kroky by remember { mutableIntStateOf(20) }
    var cfg by remember { mutableFloatStateOf(7f) }
    var viac by remember { mutableStateOf(false) }
    var priebehStahovania by remember { mutableStateOf<Float?>(null) }
    var zrusit by remember { mutableStateOf(false) }
    var odkaz by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<File?>(null) }
    val chybaPrefix = T("chyba")
    val ulozene = T("obr_ulozene")
    val neulozene = T("obr_neulozene")

    fun hotovo(f: File?, e: Throwable?) {
        priebehStahovania = null; popisStahovania = ""; modely = o.modely(); sady = o.sady()
        if (f != null) o.nacitaj(f.path)
        if (e != null && e !is InterruptedException) Platforma.oznam("$chybaPrefix: ${e.message}")
    }
    /** predvolené nastavenia podľa modelu (Z-Image ~8 krokov a presnosť 1, SD 1.5 20 krokov a 7…) */
    fun pouziSadu(s: ObrazkovaSada) { kroky = s.kroky; cfg = s.cfg; rozmer = s.rozmer; o.nacitaj("sada:" + s.id) }
    fun stiahniSadu(s: ObrazkovaSada) {
        if (priebehStahovania != null) return
        priebehStahovania = 0f; zrusit = false
        thread {
            val r = runCatching { o.stiahniSadu(s, { p, t -> Platforma.naHlavnom { priebehStahovania = p; popisStahovania = t } }, { zrusit }) }
            Platforma.naHlavnom {
                hotovo(null, r.exceptionOrNull())
                if (r.isSuccess) pouziSadu(s)
            }
        }
    }
    fun stiahni(url: String) {
        if (priebehStahovania != null) return
        priebehStahovania = 0f; zrusit = false
        thread {
            val r = runCatching { Modely.stiahni(url, { p -> priebehStahovania = p }, { zrusit }, o.priecinokModelov(), true) }
            Platforma.naHlavnom { hotovo(r.getOrNull(), r.exceptionOrNull()) }
        }
    }
    val nadpisVyberu = T("zo_suboru")
    fun vyberZoSuboru() {
        if (priebehStahovania != null) return
        Platforma.vyberSubor(nadpisVyberu, setOf("gguf", "safetensors")) { f ->
        priebehStahovania = 0f; zrusit = false
        thread {
            val r = runCatching { Modely.importuj(f, { p -> priebehStahovania = p }, o.priecinokModelov(), true) }
            Platforma.naHlavnom { hotovo(r.getOrNull(), r.exceptionOrNull()) }
        }
        }
    }
    // prvý model sa načíta sám
    LaunchedEffect(modely, sady) {
        if (o.stav != StavModelu.ZIADNY) return@LaunchedEffect
        val aktivna = sady.find { o.jeAktivna(it) }
        when {
            aktivna != null -> pouziSadu(aktivna)
            modely.any { o.jeAktivny(it) } -> o.nacitaj(modely.first { o.jeAktivny(it) }.path)
            sady.isNotEmpty() -> pouziSadu(sady.first())
            modely.isNotEmpty() -> o.nacitaj(modely.first().path)
        }
    }
    // pri prvom otvorení nastav hodnoty podľa aktívneho modelu
    LaunchedEffect(Unit) { o.aktivnaSada()?.let { kroky = it.kroky; cfg = it.cfg; rozmer = it.rozmer } }

    val pod = when (o.stav) {
        StavModelu.PRIPRAVENY -> o.aktivnaSada()?.nazov ?: o.aktivnyModel?.let { File(it).nameWithoutExtension }
        StavModelu.NACITAVA -> T("nacitavam_model")
        StavModelu.CHYBA -> T("chyba_modelu")
        StavModelu.ZIADNY -> T("ziadny_model_kratko")
    }
    Column(Modifier.fillMaxSize()) {
        Hlavicka(T("obrazky"), pod, spat = spat)
        PosuvnyStlpec {
            priebehStahovania?.let { p ->
                Karta {
                    Text("${T("stahujem")} ${(p * 100).roundToInt()} %", color = Farby.text, fontWeight = FontWeight.SemiBold)
                    if (popisStahovania.isNotBlank()) Text(popisStahovania, color = Farby.tlmene, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    TextButton(onClick = { zrusit = true }) { Text(T("zrusit"), color = Farby.tlmene) }
                }
            }

            if (modely.isEmpty() && sady.isEmpty()) {
                Karta {
                    Text(T("obr_bez_modelu"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(T("obr_bez_modelu_info"), color = Farby.tlmene, fontSize = 13.sp)
                }
            } else {
                // výsledok
                o.posledny?.let { f ->
                    Karta {
                        val bmp = remember(f) { Platforma.nacitajObrazok(f) }
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(14.dp)).clickable { detail = f }, contentScale = ContentScale.Fit)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            run {
                                GradientTlacidlo(T("obr_ulozit"), Icons.Default.SaveAlt) {
                                    Platforma.oznam(if (o.ulozDoGalerie(f)) ulozene else neulozene)
                                }
                            }
                            OutlinedButton(onClick = { o.nastavPozadieChatu(f) }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                                Icon(Icons.Default.Wallpaper, null, tint = Farby.text); Spacer(Modifier.width(6.dp)); Text(T("poz_chat_tlacidlo"), color = Farby.text)
                            }
                            OutlinedButton(onClick = { o.zmazObrazok(f) }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                                Icon(Icons.Default.DeleteOutline, null, tint = Farby.tlmene); Spacer(Modifier.width(6.dp)); Text(T("zmazat"), color = Farby.tlmene)
                            }
                        }
                    }
                }

                Karta {
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).nadKlavesnicou(),
                        placeholder = { Text(T("obr_prompt_hint"), color = Farby.tlmene, fontSize = 13.sp) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                    )
                    Text(T("obr_anglicky"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(T("poz_nadpis"), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        POZADIA.forEach { (id, _) ->
                            FilterChip(selected = pozadie == id, onClick = { pozadie = id }, label = { Text(T("poz_$id")) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                        }
                    }
                    if (pozadie == "vlastne") OutlinedTextField(
                        value = vlastnePozadie, onValueChange = { vlastnePozadie = it }, modifier = Modifier.fillMaxWidth().nadKlavesnicou(), singleLine = true,
                        placeholder = { Text(T("poz_vlastne_hint"), color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                    )
                    TextButton(onClick = { viac = !viac }) {
                        Text(T("obr_viac"), color = Farby.ruzova)
                        Icon(if (viac) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Farby.ruzova)
                    }
                    AnimatedVisibility(viac) {
                        Column {
                            if (o.aktivnaSada()?.negativny != false) OutlinedTextField(
                                value = negativny, onValueChange = { negativny = it }, modifier = Modifier.fillMaxWidth().nadKlavesnicou(),
                                label = { Text(T("obr_negativny")) }, shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(T("obr_rozmer"), color = Farby.text, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(256, 384, 512, 768, 1024).forEach { r ->
                                    FilterChip(selected = rozmer == r, onClick = { rozmer = r }, label = { Text("${r}×$r") },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                                }
                            }
                            Text(T("obr_kroky", kroky), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                            Slider(value = kroky.toFloat(), onValueChange = { kroky = it.roundToInt() }, valueRange = 1f..40f,
                                colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                            Text(T("obr_cfg", cfg), color = Farby.text, fontSize = 14.sp)
                            Slider(value = cfg, onValueChange = { cfg = (it * 2).roundToInt() / 2f }, valueRange = 1f..12f,
                                colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (o.generuje) {
                        Text(T("obr_tvorim", (o.priebeh * 100).roundToInt()), color = Farby.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { o.priebeh }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                        TextButton(onClick = { o.zrus() }) { Text(T("zrusit"), color = Farby.tlmene) }
                    } else {
                        GradientTlacidlo(T("obr_vytvorit"), Icons.Default.AutoAwesome, Modifier.fillMaxWidth(),
                            enabled = o.stav == StavModelu.PRIPRAVENY && prompt.isNotBlank()) { o.generuj(popisSPozadim(), negativny, rozmer, kroky, cfg) }
                        Text(T("obr_cas"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (o.chyba.isNotBlank()) Text(o.chyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }

                // galéria
                if (o.galeria.isNotEmpty()) {
                    Text(T("obr_galeria"), color = Farby.tlmene, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    o.galeria.chunked(3).forEach { riadok ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            riadok.forEach { f ->
                                val nahlad = remember(f) { Platforma.nacitajObrazok(f, 4) }
                                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Farby.plocha2).clickable { detail = f }) {
                                    if (nahlad != null) Image(nahlad, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            }
                            repeat(3 - riadok.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            // modely na obrázky
            Text(T("obr_modely"), color = Farby.tlmene, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            sady.forEach { sd ->
                val aktivny = o.jeAktivna(sd)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (aktivny) Farby.prechodJemny else Brush.linearGradient(listOf(Farby.plocha, Farby.plocha)))
                        .border(1.dp, if (aktivny) Farby.fialova else Farby.okraj, RoundedCornerShape(18.dp))
                        .clickable(enabled = !aktivny && !o.generuje) { pouziSadu(sd) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (aktivny) Icons.Default.CheckCircle else Icons.Default.Image, null, tint = if (aktivny) Farby.zelena else Farby.tlmene)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sd.nazov, color = Farby.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (aktivny && o.stav == StavModelu.NACITAVA) T("nacitavam_model") else sd.popis, color = Farby.tlmene, fontSize = 12.sp)
                    }
                    IconButton(onClick = { o.zmazSadu(sd); sady = sady - sd }, enabled = !o.generuje) { Icon(Icons.Default.DeleteOutline, T("zmazat"), tint = Farby.tlmene) }
                }
            }
            modely.forEach { f ->
                val aktivny = o.jeAktivny(f)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (aktivny) Farby.prechodJemny else Brush.linearGradient(listOf(Farby.plocha, Farby.plocha)))
                        .border(1.dp, if (aktivny) Farby.fialova else Farby.okraj, RoundedCornerShape(18.dp))
                        .clickable(enabled = !aktivny && !o.generuje) { o.nacitaj(f.path) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (aktivny) Icons.Default.CheckCircle else Icons.Default.Image, null, tint = if (aktivny) Farby.zelena else Farby.tlmene)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.nameWithoutExtension, color = Farby.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Modely.velkost(f), color = Farby.tlmene, fontSize = 12.sp)
                    }
                    IconButton(onClick = { o.zmazModel(f); modely = modely - f }, enabled = !o.generuje) { Icon(Icons.Default.DeleteOutline, T("zmazat"), tint = Farby.tlmene) }
                }
            }
            Katalog.obrazky.filter { k -> sady.none { it.id == k.id } }.forEach { k ->
                Karta {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k.nazov, color = Farby.text, fontWeight = FontWeight.SemiBold)
                            Text("${k.popis} · ~${"%.1f".format(k.mb / 1000.0)} GB" + (if (k.subory.size > 1) " · ${T("obr_suborov", k.subory.size)}" else ""), color = Farby.tlmene, fontSize = 12.sp)
                        }
                        GradientTlacidlo(T("stiahnut"), Icons.Default.Download, enabled = priebehStahovania == null) { stiahniSadu(k) }
                    }
                }
            }
            Karta {
                Text(T("obr_vlastny"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = odkaz, onValueChange = { odkaz = it }, singleLine = true, modifier = Modifier.fillMaxWidth().nadKlavesnicou(),
                    placeholder = { Text("https://huggingface.co/…/model.gguf", color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GradientTlacidlo(T("stiahnut"), Icons.Default.Download, enabled = priebehStahovania == null && (odkaz.trim().startsWith("http") || odkaz.contains("huggingface.co") || odkaz.contains("hf.co"))) { stiahni(odkaz) }
                    OutlinedButton(onClick = { vyberZoSuboru() }, enabled = priebehStahovania == null, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.FolderOpen, null, tint = Farby.text); Spacer(Modifier.width(6.dp)); Text(T("zo_suboru_kratko"), color = Farby.text)
                    }
                }
            }
            Text(T("obr_info"), color = Farby.tlmene, fontSize = 12.sp)
        }
    }

    detail?.let { f ->
        val bmp = remember(f) { Platforma.nacitajObrazok(f) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { detail = null }) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Farby.plocha).padding(12.dp)) {
                if (bmp != null) Image(bmp, null, Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    run {
                        GradientTlacidlo(T("obr_ulozit"), Icons.Default.SaveAlt) {
                            Platforma.oznam(if (o.ulozDoGalerie(f)) ulozene else neulozene)
                        }
                    }
                    OutlinedButton(onClick = { o.nastavPozadieChatu(f) }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.Wallpaper, T("poz_chat_tlacidlo"), tint = Farby.text)
                    }
                    OutlinedButton(onClick = { o.zmazObrazok(f); detail = null }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Farby.tlmene)
                    }
                }
            }
        }
    }
}

/** pozadia pre tvorbu obrázkov: id → anglický popis */
private val POZADIA = listOf(
    "bez" to "",
    "nahodne" to "",
    "priroda" to "outdoors in nature, green meadow, sunny day",
    "les" to "in a forest, tall trees, soft sunlight through leaves",
    "plaz" to "on a sandy beach, turquoise sea, blue sky",
    "hory" to "mountains, alpine landscape, clear sky",
    "vidiek" to "countryside village, fields, old houses",
    "mesto" to "city street, modern buildings",
    "noc" to "city at night, neon lights, bokeh",
    "zapad" to "golden hour sunset sky",
    "zima" to "snowy winter landscape",
    "izba" to "cozy room interior, warm light",
    "studio" to "plain studio backdrop, soft studio lighting",
    "vesmir" to "outer space, stars and colorful nebula",
    "scifi" to "futuristic sci-fi city, spaceships, glowing lights",
    "cyberpunk" to "cyberpunk street at night, neon signs, rain",
    "fantasy" to "magical fantasy landscape, castle, glowing forest",
    "vesmirna_lod" to "inside a futuristic spaceship, control panels",
    "pust" to "desert dunes, hot sun",
    "more" to "underwater world, coral reef, fish",
    "postapo" to "post-apocalyptic ruined city, dust, overgrown buildings",
    "dzungla" to "tropical jungle, waterfall",
    "vlastne" to "",
)

// ---------------------------------------------------------------- prepojenie v sieti

@Composable
private fun PrepojenieKarta() {
    val n = ChatJadro.nastavenia
    val j = ChatJadro
    var najdene by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var hlada by remember { mutableStateOf(false) }
    val farbyPola = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text)
    Text(T("siet_karta"), color = Farby.text, fontWeight = FontWeight.SemiBold)

    // A) toto zariadenie ako server
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(T("siet_zdielat"), color = Farby.text)
            Text(T("siet_zdielat_info"), color = Farby.tlmene, fontSize = 12.sp)
        }
        Switch(checked = Server.bezi, onCheckedChange = { z ->
            if (z) Server.spusti()?.let { Platforma.oznam(it) } else Server.zastav()
            n.zdielat = Server.bezi; n.uloz()
        }, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
    }
    if (Server.bezi) {
        Column(Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Farby.plocha2).padding(12.dp)) {
            val adresy = remember { Server.adresy() }
            Text(T("siet_adresa"), color = Farby.tlmene, fontSize = 12.sp)
            SelectionContainer { Text(adresy.joinToString("\n") { "$it:${Klient.PORT}" }.ifBlank { "?" }, color = Farby.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            Spacer(Modifier.height(6.dp))
            Text(T("siet_pin"), color = Farby.tlmene, fontSize = 12.sp)
            var pin by remember { mutableStateOf(Server.pin) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer { Text(pin.chunked(4).joinToString(" "), color = Farby.ruzova, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { pin = Server.novyPin() }) { Text(T("siet_novy_pin"), color = Farby.tlmene) }
            }
            if (Server.pripojeni > 0) Text(T("siet_pripojeni", Server.pripojeni), color = Farby.zelena, fontSize = 13.sp)
            if (j.stav != StavModelu.PRIPRAVENY || n.vzdialene) Text(T("siet_server_bez_modelu"), color = Farby.cervena, fontSize = 12.sp)
        }
    }
    Text(T("siet_internet"), color = Farby.tlmene, fontSize = 12.sp)

    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Farby.okraj)

    // B) použiť model z iného zariadenia
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(T("siet_pouzit"), color = Farby.text)
            Text(T("siet_pouzit_info"), color = Farby.tlmene, fontSize = 12.sp)
        }
        Switch(checked = n.vzdialene, onCheckedChange = { j.nastavVzdialene(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
    }
    if (n.vzdialene) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = n.vzdialenaAdresa, onValueChange = { n.vzdialenaAdresa = it.trim(); n.uloz() }, singleLine = true,
            modifier = Modifier.fillMaxWidth().nadKlavesnicou(), label = { Text(T("siet_adresa_pole")) },
            placeholder = { Text("192.168.0.10:${Klient.PORT}", color = Farby.tlmene) }, shape = RoundedCornerShape(14.dp), colors = farbyPola)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = n.vzdialenyPin, onValueChange = { n.vzdialenyPin = it.filter { c -> c.isDigit() }; n.uloz() }, singleLine = true,
            modifier = Modifier.fillMaxWidth().nadKlavesnicou(), label = { Text(T("siet_pin")) }, shape = RoundedCornerShape(14.dp), colors = farbyPola)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GradientTlacidlo(T("siet_pripojit"), Icons.Default.Link, enabled = j.stav != StavModelu.NACITAVA) { j.pripoj() }
            OutlinedButton(onClick = {
                hlada = true
                thread { val r = Klient.najdi(); Platforma.naHlavnom { najdene = r; hlada = false } }
            }, enabled = !hlada, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                Icon(Icons.Default.Search, null, tint = Farby.text); Spacer(Modifier.width(6.dp)); Text(T(if (hlada) "siet_hladam" else "siet_najst"), color = Farby.text)
            }
        }
        najdene?.let { z ->
            if (z.isEmpty()) Text(T("siet_nic"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            z.forEach { (adr, meno) ->
                Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Farby.plocha2)
                    .clickable { n.vzdialenaAdresa = adr; n.uloz(); najdene = null }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Computer, null, tint = Farby.ruzova); Spacer(Modifier.width(10.dp))
                    Column { Text(meno, color = Farby.text); Text(adr, color = Farby.tlmene, fontSize = 12.sp) }
                }
            }
        }
        val stavText = when (j.stav) {
            StavModelu.PRIPRAVENY -> T("siet_pripojene", j.vzdialeneMeno, j.vzdialenyModel)
            StavModelu.NACITAVA -> T("siet_pripajam")
            StavModelu.CHYBA -> j.chyba
            StavModelu.ZIADNY -> ""
        }
        if (stavText.isNotBlank()) Text(stavText, color = if (j.stav == StavModelu.PRIPRAVENY) Farby.zelena else if (j.stav == StavModelu.CHYBA) Farby.cervena else Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------------------------------------------------------------- vylepšenie fotky (len Linux)

@Composable
private fun VylepsenieObrazovka(spat: () -> Unit) {
    val v = VylepsenieJadro
    var poVylepseni by remember { mutableStateOf(true) }
    val nadpisVyberu = T("vyl_vybrat")
    val ulozene = T("obr_ulozene"); val neulozene = T("obr_neulozene")
    val vstup = v.vstup; val vysledok = v.vysledok
    val obrVstup = remember(vstup) { vstup?.let { Platforma.nacitajObrazok(it) } }
    val obrVysledok = remember(vysledok) { vysledok?.let { Platforma.nacitajObrazok(it) } }

    Column(Modifier.fillMaxSize()) {
        Hlavicka(T("vylepsit"), spat = spat)
        PosuvnyStlpec {
            Karta {
                if (obrVstup == null) {
                    if (vstup != null) { Text(T("vyl_neotvori", vstup.name), color = Farby.cervena, fontSize = 13.sp); Spacer(Modifier.height(6.dp)) }
                    Text(T("vyl_prazdne"), color = Farby.tlmene)
                    Spacer(Modifier.height(10.dp))
                    GradientTlacidlo(T("vyl_vybrat"), Icons.Default.AddPhotoAlternate) {
                        Platforma.vyberSubor(nadpisVyberu, Platforma.PRIPONY_FOTIEK) { v.vstup = it }
                    }
                } else {
                    val ukaz = if (obrVysledok != null && poVylepseni) obrVysledok else obrVstup
                    Image(ukaz, null, Modifier.fillMaxWidth().heightIn(max = 520.dp).aspectRatio(ukaz.width.toFloat() / ukaz.height).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (obrVysledok != null) {
                            listOf(false to T("vyl_pred"), true to T("vyl_po")).forEach { (po, nazov) ->
                                FilterChip(selected = poVylepseni == po, onClick = { poVylepseni = po }, label = { Text(nazov) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                            }
                            Text("${obrVstup.width}×${obrVstup.height} → ${v.rozmerVysledku.first}×${v.rozmerVysledku.second}", color = Farby.tlmene, fontSize = 12.sp)
                        } else {
                            Text("${obrVstup.width}×${obrVstup.height}", color = Farby.tlmene, fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { Platforma.vyberSubor(nadpisVyberu, Platforma.PRIPONY_FOTIEK) { v.vstup = it } }, enabled = !v.pracuje) { Text(T("vyl_ina"), color = Farby.ruzova) }
                    }
                    if (vysledok != null) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GradientTlacidlo(T("obr_ulozit"), Icons.Default.SaveAlt) {
                                Platforma.oznam(if (ObrazkyJadro.ulozDoGalerie(vysledok)) ulozene else neulozene)
                            }
                        }
                    }
                }
            }

            Karta {
                Text(T("vyl_zvacsenie"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to T("vyl_bez"), 2 to "2×", 4 to "4×", VylepsenieJadro.K4 to "4K", VylepsenieJadro.K8 to "8K").forEach { (n, nazov) ->
                        FilterChip(selected = v.nasobok == n, onClick = { v.nasobok = n }, label = { Text(nazov) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
                if (v.nasobok > 1 && !v.mamModel()) {
                    Spacer(Modifier.height(8.dp))
                    Text(T("vyl_model", VylepsenieJadro.MB), color = Farby.tlmene, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    val p = v.priebehStahovania
                    if (p != null) {
                        Text("${T("stahujem")} ${(p * 100).roundToInt()} %", color = Farby.text, fontSize = 13.sp)
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    } else GradientTlacidlo(T("stiahnut"), Icons.Default.Download) { v.stiahniModel() }
                }
                Spacer(Modifier.height(10.dp))
                listOf(
                    Triple(T("vyl_jas"), v.autoJas) { b: Boolean -> v.autoJas = b },
                    Triple(T("vyl_farby"), v.farby) { b: Boolean -> v.farby = b },
                    Triple(T("vyl_ostrost"), v.doostrit) { b: Boolean -> v.doostrit = b },
                ).forEach { (nazov, hodnota, zmen) ->
                    Row(Modifier.fillMaxWidth().clickable { zmen(!hodnota) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(nazov, color = Farby.text, modifier = Modifier.weight(1f))
                        Switch(checked = hodnota, onCheckedChange = zmen, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (v.pracuje) {
                    Text(v.cinnost, color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                } else {
                    GradientTlacidlo(T("vyl_spustit"), Icons.Default.AutoFixHigh, Modifier.fillMaxWidth(),
                        enabled = v.vstup != null && (v.nasobok == 1 || v.mamModel()) && v.priebehStahovania == null) { poVylepseni = true; v.vylepsi() }
                    if (v.nasobok == VylepsenieJadro.K4 || v.nasobok == VylepsenieJadro.K8) Text(T(if (v.nasobok == VylepsenieJadro.K8) "vyl_cas_8k" else "vyl_cas_4k"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    if (v.nasobok == 4) Text(T("vyl_cas"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                if (v.chyba.isNotBlank()) Text(v.chyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Farby.tlmene, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(T("vyl_info"), color = Farby.tlmene, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Aktualizacia() {
    val v = Aktualizacie.novaVerzia ?: return
    val p = Aktualizacie.priebeh
    AlertDialog(
        onDismissRequest = { if (p == null) Aktualizacie.novaVerzia = null },
        containerColor = Farby.plocha,
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = Farby.ruzova) },
        title = { Text(T("akt_nadpis", v), color = Farby.text) },
        text = {
            Column {
                Text(T(if (Aktualizacie.samaSaVymeni) "akt_text_appimage" else "akt_text_zip"), color = Farby.tlmene)
                Text(T("akt_teraz", Aktualizacie.mojaVerzia), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                if (p != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                }
                if (Aktualizacie.chyba.isNotBlank()) Text(Aktualizacie.chyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { Aktualizacie.aktualizuj() }, enabled = p == null) { Text(T("akt_aktualizovat"), color = Farby.ruzova) } },
        dismissButton = { if (p == null) TextButton(onClick = { Aktualizacie.preskoc() }) { Text(T("akt_neskor"), color = Farby.tlmene) } },
    )
}

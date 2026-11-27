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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** všetko, čo appka vypíše (aj chyby), ide aj do súboru alexia.log v priečinku appky; starý sa premenuje na alexia-predtym.log */
private fun spustiLog() = runCatching {
    val f = File(Platforma.logy, "alexia.log")
    if (f.exists()) f.copyTo(File(Platforma.logy, "alexia-predtym.log"), overwrite = true)
    val subor = java.io.FileOutputStream(f, false)
    class Oba(val a: java.io.OutputStream) : java.io.OutputStream() {
        override fun write(b: Int) { runCatching { a.write(b) }; synchronized(subor) { subor.write(b) } }
        override fun write(b: ByteArray, off: Int, len: Int) { runCatching { a.write(b, off, len) }; synchronized(subor) { subor.write(b, off, len); subor.flush() } }
        override fun flush() { runCatching { a.flush() } }
    }
    System.setOut(java.io.PrintStream(Oba(System.out), true, "UTF-8"))
    System.setErr(java.io.PrintStream(Oba(System.err), true, "UTF-8"))
    println("Alexia AI ${Aktualizacie.mojaVerzia} – ${java.time.LocalDateTime.now()} – ${System.getProperty("os.name")}")
    Thread.setDefaultUncaughtExceptionHandler { t, e -> System.err.println("Chyba vo vlákne ${t.name}:"); e.printStackTrace() }
}

fun main(args: Array<String>) {
    // pomocný proces „postava spieva“ – ten istý program spustený s --spev (balík appky nemá samostatný java.exe)
    if (args.firstOrNull() == "--spev") { SpevProces.main(args.drop(1).toTypedArray()); return }
    Platforma.presunDoDokumentov()
    spustiLog()
    ChatJadro.init()
    ObrazkyJadro.init()
    Aktualizacie.skontrolujPripravenu()
    Aktualizacie.skontroluj()
    Ovladac.spusti()
    if (ChatJadro.nastavenia.zdielat) Server.spusti()
    application {
        val stav = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 800.dp))
        Window(onCloseRequest = {
            // zatvorenie = koniec celej appky: vlákna modelov by inak držali proces (a RAM) aj po zatvorení okna
            runCatching { ObrazkyJadro.zrus(); ChatJadro.zastav(); Hlas.zastav() }
            // stiahnutá aktualizácia sa nainštaluje pri zatvorení – pri ďalšom spustení je appka nová
            runCatching { Aktualizacie.instalujPriZatvoreni() }
            Runtime.getRuntime().halt(0)
        }, title = "Alexia AI", state = stav, onPreviewKeyEvent = {
            // Esc = späť
            if (it.type != KeyEventType.KeyDown) false
            else when {
                it.key == Key.Escape -> { naspat?.invoke(); naspat != null }
                it.key == Key.Tab && it.isCtrlPressed -> { prepni?.invoke(if (it.isShiftPressed) -1 else 1); true }
                it.key == Key.PageDown -> { Ovladac.posun.tryEmit(500f); true }
                it.key == Key.PageUp -> { Ovladac.posun.tryEmit(-500f); true }
                it.key in listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6) -> { chodNa?.invoke(listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6).indexOf(it.key)); true }
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

private enum class Obrazovka { CHAT, OBRAZKY, VIDEA, VYLEPSIT, DABING, PROMPT, LORA, MODELY, NASTAVENIA }

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
            Obrazovka.OBRAZKY, Obrazovka.VIDEA -> ObrazkyJadro.aktivnyModel?.let { m ->
                // načíta sa len model, ktorý patrí do tejto záložky (obrázkový / video)
                val s = ObrazkyJadro.aktivnaSada()
                val patri = if (s != null) (s.video != null) == (obrazovka == Obrazovka.VIDEA) else obrazovka == Obrazovka.OBRAZKY
                if (patri && (File(m).exists() || s?.let { ObrazkyJadro.mamSadu(it) } == true)) ObrazkyJadro.nacitaj(m)
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
        if (Sifra.zamknute) { Odomknutie(); return@Column }
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
            Obrazovka.OBRAZKY -> key("obrazky") { ObrazkyObrazovka(videa = false) { obrazovka = Obrazovka.CHAT } }
            Obrazovka.VIDEA -> key("videa") { ObrazkyObrazovka(videa = true) { obrazovka = Obrazovka.CHAT } }
            Obrazovka.VYLEPSIT -> VylepsenieObrazovka { obrazovka = Obrazovka.CHAT }
            Obrazovka.LORA -> LoraObrazovka { obrazovka = Obrazovka.CHAT }
            Obrazovka.PROMPT -> PromptObrazovka { obrazovka = Obrazovka.CHAT }
            Obrazovka.DABING -> DabingObrazovka { obrazovka = Obrazovka.CHAT }
        }
        }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        Platforma.ziadostOVyber?.let { z -> VyberSuboru(z) { Platforma.ziadostOVyber = null } }
        PrehravacOkno()
    }
}

/** výber textového kodéra pre obrázkovú sadu (napr. iná verzia Qwen3 4B pri Z-Image) */
@Composable
private fun KoderVolba(sd: ObrazkovaSada) {
    val o = ObrazkyJadro
    val n = ChatJadro.nastavenia
    @Suppress("UNUSED_VARIABLE") val v = n.koderVerzia
    val aktualny = n.koder(sd.id).orEmpty()
    val nadpis = T("koder_vlastny")
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp).clip(RoundedCornerShape(14.dp)).background(Farby.plocha2).padding(10.dp)) {
        Text(T("koder_nadpis"), color = Farby.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(T("koder_info"), color = Farby.tlmene, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            o.volbyKodera(sd).forEach { (nazov, url) ->
                FilterChip(selected = aktualny == url, onClick = { o.nastavKoder(sd, url) }, enabled = o.koderPriebeh == null && !o.generuje,
                    label = { Text(nazov, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
            }
            FilterChip(selected = aktualny.isNotEmpty() && !aktualny.startsWith("http"), enabled = o.koderPriebeh == null && !o.generuje,
                onClick = { Platforma.vyberSubor(nadpis, setOf("gguf", "safetensors")) { f -> o.nastavKoder(sd, f.path) } },
                label = { Text(T("koder_vlastny"), fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
        }
        // vlastný kodér z Hugging Face (odkaz na súbor alebo celý repozitár)
        var odkaz by remember { mutableStateOf("") }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = odkaz, onValueChange = { odkaz = it }, singleLine = true, modifier = Modifier.weight(1f).nadKlavesnicou(),
                placeholder = { Text(T("koder_hf"), color = Farby.tlmene, fontSize = 12.sp) }, shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { o.nastavKoder(sd, odkaz.trim()); odkaz = "" }, enabled = odkaz.contains("huggingface.co") || odkaz.contains("hf.co")) {
                Icon(Icons.Default.Download, T("stiahnut"), tint = Farby.ruzova)
            }
        }
        if (aktualny.isNotEmpty()) Text(T("koder_aktualny", File(aktualny.substringBefore('?')).name), color = Farby.tlmene, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        o.koderPriebeh?.let { p ->
            LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha)
        }
        if (o.chyba.isNotBlank() && o.koderPriebeh == null) Text(o.chyba, color = Farby.cervena, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------------------------------------------------------- záložky (ako v hre: L1/R1)

@Composable
private fun Zalozky(aktivna: Obrazovka, zvol: (Obrazovka) -> Unit) {
    val polozky = listOf(
        Triple(Obrazovka.CHAT, Icons.Default.Forum, "chat"),
        Triple(Obrazovka.OBRAZKY, Icons.Default.Image, "obrazky"),
        Triple(Obrazovka.VIDEA, Icons.Default.Movie, "videa"),
        Triple(Obrazovka.VYLEPSIT, Icons.Default.AutoFixHigh, "vylepsit_kratko"),
        Triple(Obrazovka.DABING, Icons.Default.RecordVoiceOver, "dab_zalozka"),
        Triple(Obrazovka.PROMPT, Icons.Default.Description, "prompt_zalozka"),
        Triple(Obrazovka.LORA, Icons.Default.Extension, "lora_zalozka"),
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
    var historia by remember { mutableStateOf(false) }
    if (historia) HistoriaDialog { historia = false }
    var rezimVidea by remember { mutableStateOf(false) }
    var citac by remember { mutableStateOf(false) }
    if (citac && Dokumenty.aktualny != null) CitacDialog { citac = false }
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
            IconButton(onClick = { historia = true }) { Icon(Icons.Default.History, T("hist_nadpis"), tint = Farby.text) }
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

        // vložený dokument (kniha, PDF…) – otázky sa pýtajú naň, dá sa čítať nahlas
        val dok = Dokumenty.aktualny
        if (dok != null || Dokumenty.nacitava) {
            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Farby.plocha2).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MenuBook, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                if (dok == null) Text(T("dok_nacitavam"), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                else {
                    Column(Modifier.weight(1f)) {
                        Text(dok.nazov, color = Farby.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(T("dok_info"), color = Farby.tlmene, fontSize = 11.sp)
                    }
                    TextButton(onClick = { Citac.otvor(); citac = true }) {
                        Icon(Icons.Default.VolumeUp, null, tint = Farby.ruzova, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(T("dok_citat"), color = Farby.ruzova, fontSize = 13.sp)
                    }
                    IconButton(onClick = { Dokumenty.zavri() }) { Icon(Icons.Default.Close, T("dok_zavriet"), tint = Farby.tlmene, modifier = Modifier.size(18.dp)) }
                }
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
            IconButton(onClick = { Platforma.vyberSubor(T("dok_vyber"), Dokumenty.PRIPONY) { Dokumenty.otvor(it) } }) {
                Icon(Icons.Default.AttachFile, T("dok_pridat"), tint = if (Dokumenty.aktualny != null) Farby.ruzova else Farby.tlmene)
            }
            TextField(
                value = vstup, onValueChange = { vstup = it },
                placeholder = { Text(T(if (Rec.nahrava) "rec_pocuvam" else if (Rec.spracuva) "rec_prepisujem" else if (rezimVidea) "video_popis" else if (rezimObrazka) "chat_obr_popis" else if (j.stav == StavModelu.PRIPRAVENY) "napis_spravu" else "ziadny_model_kratko"), color = Farby.tlmene) },
                modifier = Modifier.weight(1f).nadKlavesnicou(), maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    cursorColor = Farby.ruzova, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text,
                ),
            )
            // mikrofón: hovor → text do poľa (Whisper, offline)
            Box(contentAlignment = Alignment.Center) {
                val rp = Rec.priebeh
                if (rp != null) CircularProgressIndicator(progress = { rp }, modifier = Modifier.size(36.dp), color = Farby.ruzova, strokeWidth = 3.dp)
                else if (Rec.spracuva) CircularProgressIndicator(modifier = Modifier.size(36.dp), color = Farby.ruzova, strokeWidth = 3.dp)
                IconButton(onClick = {
                    if (!Rec.mam()) { if (Rec.priebeh == null) { Platforma.oznam(T("rec_stahujem", Rec.MB)); Rec.stiahni() } }
                    else Rec.prepni { t -> vstup = if (vstup.isBlank()) t else vstup.trimEnd() + " " + t }
                }) {
                    Icon(if (Rec.nahrava) Icons.Default.StopCircle else Icons.Default.Mic, T("rec_tlacidlo"),
                        tint = if (Rec.nahrava) Farby.cervena.copy(alpha = 0.6f + 0.4f * Rec.hlasitost) else Farby.tlmene)
                }
            }
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
            if (cesta != null && cesta.endsWith(".mp4") && File(cesta).exists()) {
                // video AI: náhľad + prehranie v prehrávači
                val f = File(cesta)
                val nahlad = remember(cesta) { s.obrazok?.let { Platforma.nacitajObrazok(File(it)) } }
                Box(Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)
                    .clickable { Prehravac.otvor(f) },
                    contentAlignment = Alignment.Center) {
                    nahlad?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height), contentScale = ContentScale.Fit) }
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, T("video_prehrat"), tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val d = File(System.getProperty("user.home"), "Videos/Alexia AI").apply { mkdirs() }
                        val u = runCatching { f.copyTo(File(d, f.name), overwrite = true) }.getOrNull()
                        Platforma.oznam(if (u != null) T("video_ulozene") + " " + d.path else T("obr_neulozene"))
                    }, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.SaveAlt, null, tint = Farby.text, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(T("video_ulozit"), color = Farby.text, fontSize = 13.sp)
                    }
                }
            } else if (cesta != null && File(cesta).isDirectory) {
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
                    IconButton(onClick = { Prehravac.otvor(proj.mp4) }, enabled = proj.mp4.exists()) {
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
                    Spacer(Modifier.height(4.dp)); Casovac(true, ObrazkyJadro.zaciatok, 0)
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

/** „Seed 12345“ pod obrázkom z appky – ťuknutím sa skopíruje príkaz na úpravu */
@Composable
private fun SeedStitok(f: File) {
    val seed = remember(f) { ObrazkyJadro.seedObrazka(f) } ?: return
    val schranka = LocalClipboardManager.current
    Row(Modifier.padding(start = 4.dp).clip(RoundedCornerShape(10.dp)).background(Farby.plocha2)
        .clickable { schranka.setText(AnnotatedString(T("upr_prikaz", seed))); Platforma.oznam(T("upr_skopirovane")) }
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Tag, null, tint = Farby.ruzova, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
        Text("Seed $seed", color = Farby.text, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
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
                        SeedStitok(File(cesta!!))
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

            item { SekciaHlavicka(T("mod_sek_moje", subory.size), Icons.Default.Memory, kluc = "mod_moje") }
            if (jeOtvorena("mod_moje")) {
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

            }
            item { SekciaHlavicka(T("modely_odporucane"), Icons.Default.Download, kluc = "mod_odpor") }
            if (jeOtvorena("mod_odpor")) items(Modely.odporucane) { m ->
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
                Sekcia(T("modely_vlastny"), ikona = Icons.Default.Link) {
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
            Sekcia(T("hist_karta")) { HesloKarta() }
            Sekcia(T("siet_karta")) { PrepojenieKarta() }
            Sekcia(T("hlas_karta")) {
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
                Text(T("hlas_vyber"), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                Zalamuj {
                    Hlas.HLASY.forEach { (id, meno) ->
                        FilterChip(selected = n.hlas == id, onClick = { Hlas.zastav(); n.hlas = id; n.uloz() }, label = { Text(meno) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
                if (n.hlas == "mix") {
                    val zeny = listOf("Ema", "Nina", "Sofia", "Zara", "Hana")
                    for ((nadpis, vybrany, nastav) in listOf(
                        Triple(T("mix_a"), n.mixA) { i: Int -> n.mixA = i },
                        Triple(T("mix_b"), n.mixB) { i: Int -> n.mixB = i },
                    )) {
                        Text(nadpis, color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        Zalamuj {
                            zeny.forEachIndexed { i, meno ->
                                FilterChip(selected = vybrany == i, onClick = { Hlas.zastav(); nastav(i); n.uloz() }, label = { Text(meno) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                            }
                        }
                    }
                    val pa = ((1 - n.mixPomer) * 100).roundToInt()
                    Text(T("mix_pomer", zeny[n.mixA.coerceIn(0, 4)], pa, zeny[n.mixB.coerceIn(0, 4)], 100 - pa), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Slider(value = n.mixPomer, onValueChange = { n.mixPomer = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { Hlas.zastav(); n.uloz() }, valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                }
                Text(T("hlas_st_info", Hlas.ST_MB), color = Farby.tlmene, fontSize = 12.sp)
                val p = Hlas.priebeh
                when {
                    p != null -> LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    Hlas.mam(n.hlas) -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(T("hlas_mam"), color = Farby.zelena, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        val vzorka = T("hlas_vzorka", Hlas.meno(n.hlas))
                        GradientTlacidlo(if (Hlas.hovori == vzorka) T("hlas_stop") else T("hlas_skusit"), if (Hlas.hovori == vzorka) Icons.Default.StopCircle else Icons.Default.VolumeUp) {
                            if (Hlas.hovori == vzorka) Hlas.zastav() else Hlas.hovor(vzorka)
                        }
                    }
                    else -> GradientTlacidlo(T("hlas_stiahnut"), Icons.Default.Download, Modifier.padding(top = 6.dp)) { Hlas.stiahni(n.hlas) }
                }
                if (Hlas.chyba.isNotBlank()) Text(Hlas.chyba, color = Farby.cervena, fontSize = 13.sp)
                Text(T("hlas_rychlost", "%.2f".format(n.hlasRychlost)), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                Slider(value = n.hlasRychlost, onValueChange = { n.hlasRychlost = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { n.uloz() }, valueRange = 0.5f..2f,
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                Text(T("hlas_vyska", "%.2f".format(n.hlasVyska)), color = Farby.text, fontSize = 14.sp)
                Slider(value = n.hlasVyska, onValueChange = { n.hlasVyska = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { n.uloz() }, valueRange = 0.7f..1.4f,
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                run {
                    Text(T("hlas_hrubka", if (n.hlasHrubka > 1.005f) T("hlas_hrubsi") else if (n.hlasHrubka < 0.995f) T("hlas_tensi") else T("hlas_normal")), color = Farby.text, fontSize = 14.sp)
                    Slider(value = n.hlasHrubka, onValueChange = { n.hlasHrubka = (it * 100).roundToInt() / 100f }, onValueChangeFinished = { n.uloz() }, valueRange = 0.85f..1.15f,
                        colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                    Text(T("hlas_plnost", (n.hlasPlnost * 100).roundToInt()), color = Farby.text, fontSize = 14.sp)
                    Slider(value = n.hlasPlnost, onValueChange = { n.hlasPlnost = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { n.uloz() }, valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                    Text(T("hlas_uprava_info"), color = Farby.tlmene, fontSize = 12.sp)
                }
                TextButton(onClick = { n.hlasRychlost = 1f; n.hlasVyska = 1f; n.hlasHrubka = 1f; n.hlasPlnost = 0f; n.uloz() }) { Text(T("hlas_predvolene"), color = Farby.ruzova) }
            }
            Sekcia(T("nahovor_karta")) {
                Text(T("nahovor_info", Hlas.meno(n.hlas)), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                var nText by remember { mutableStateOf("") }
                var nVysledok by remember { mutableStateOf<File?>(null) }
                var nChyba by remember { mutableStateOf("") }
                OutlinedTextField(value = nText, onValueChange = { nText = it; nVysledok = null; nChyba = "" }, minLines = 3, maxLines = 10,
                    placeholder = { Text(T("nahovor_hint"), color = Farby.tlmene) }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
                val np = Hlas.nahrava
                if (np != null) {
                    Text(T("nahovor_robim", (np * 100).roundToInt()), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    LinearProgressIndicator(progress = { np }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                } else Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ok: (File?, String) -> Unit = { f, e -> nVysledok = f; nChyba = if (f == null) e else "" }
                    GradientTlacidlo("WAV", Icons.Default.SaveAlt, Modifier.weight(1f), enabled = nText.isNotBlank()) { Hlas.nahovorDoSuboru(nText, mp3 = false, hotovo = ok) }
                    GradientTlacidlo("MP3", Icons.Default.SaveAlt, Modifier.weight(1f), enabled = nText.isNotBlank()) {
                        if (VideoJadro.ffmpeg() == null) VideoJadro.stiahniFfmpeg { Hlas.nahovorDoSuboru(nText, mp3 = true, hotovo = ok) }
                        else Hlas.nahovorDoSuboru(nText, mp3 = true, hotovo = ok)
                    }
                }
                nVysledok?.let { f ->
                    Text(T("nahovor_hotovo", f.path), color = Farby.zelena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
                        .clickable { runCatching { java.awt.Desktop.getDesktop().open(f) }.onFailure { Platforma.otvorOdkaz(f.toURI().toString()) } })
                }
                if (nChyba.isNotBlank()) Text(nChyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Sekcia(T("akt_karta")) {
                Text(T("akt_teraz", Aktualizacie.mojaVerzia), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                TextButton(onClick = {
                    runCatching { java.awt.Desktop.getDesktop().open(Platforma.logy) }.onFailure { Platforma.otvorOdkaz(Platforma.logy.toURI().toString()) }
                }) { Icon(Icons.Default.Description, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(T("log_otvorit"), color = Farby.ruzova) }
                if (Aktualizacie.kontroluje) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                else GradientTlacidlo(T("akt_skontrolovat"), Icons.Default.SystemUpdate, Modifier.fillMaxWidth()) { Aktualizacie.skontroluj(rucne = true) }
                Row(Modifier.fillMaxWidth().clickable { n.autoAktualizacie = !n.autoAktualizacie; n.uloz() }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(T("akt_auto"), color = Farby.text, modifier = Modifier.weight(1f))
                    Switch(checked = n.autoAktualizacie, onCheckedChange = { n.autoAktualizacie = it; n.uloz() }, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                }
            }
            Sekcia(T("poz_chat_karta")) {
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
            Sekcia(T("kl_karta")) {
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
            Sekcia(T("nast_system")) {
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
            Sekcia(T("nast_kontext")) {
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
            Sekcia(T("jazyk")) {
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
private fun ObrazkyObrazovka(videa: Boolean, spat: () -> Unit) {
    val o = ObrazkyJadro
    // záložka Obrázky = obrázkové modely (aj vlastné súbory), záložka Videá = video modely (Wan)
    fun mojeSady() = o.sady().filter { (it.video != null) == videa }
    var modely by remember { mutableStateOf(if (videa) emptyList() else o.modely()) }
    var sady by remember { mutableStateOf(mojeSady()) }
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
    var typ by remember { mutableStateOf("obrazok") }
    var aiVideo by remember { mutableStateOf<File?>(null) }
    var pocet by remember { mutableIntStateOf(1) }
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
        priebehStahovania = null; popisStahovania = ""; modely = if (videa) emptyList() else o.modely(); sady = mojeSady()
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
    // prvý model sa načíta sám; ak je zvolený model z druhej záložky (obrázky ↔ video), prepne sa
    LaunchedEffect(modely, sady) {
        if (o.generuje) return@LaunchedEffect
        val akt = o.aktivnaSada()
        val cudzi = if (akt != null) (akt.video != null) != videa else videa && o.aktivnyModel != null
        if (cudzi) { if (sady.isNotEmpty()) pouziSadu(sady.first()) else o.zabudniModel(); return@LaunchedEffect }
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
        Hlavicka(T(if (videa) "videa" else "obrazky"), pod, spat = spat)
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
                    Text(T(if (videa) "vid_bez_modelu" else "obr_bez_modelu"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(T(if (videa) "vid_bez_modelu_info" else "obr_bez_modelu_info"), color = Farby.tlmene, fontSize = 13.sp)
                }
            } else {
                // výsledok
                if (!videa) o.posledny?.let { f ->
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
                            SeedStitok(f)
                        }
                    }
                }

                Sekcia(T(if (videa) "vid_sek_vytvorit" else "obr_sek_vytvorit"), ikona = if (videa) Icons.Default.Movie else Icons.Default.AutoAwesome) {
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).nadKlavesnicou(),
                        placeholder = { Text(T("obr_prompt_hint"), color = Farby.tlmene, fontSize = 13.sp) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                    )
                    Text(T(if (videa) "vid_anglicky" else "obr_anglicky"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    // AI napíše presný anglický prompt z krátkeho nápadu (aj po slovensky)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ChatJadro.pisePrompt) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Farby.ruzova, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                            Text(T("prompt_pise"), color = Farby.text, fontSize = 13.sp)
                        } else OutlinedButton(onClick = {
                            ChatJadro.napisPrompt(prompt, videa) { p, e -> if (p != null) prompt = p else Platforma.oznam("⚠ $e") }
                        }, enabled = prompt.isNotBlank(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.fialova)) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(T("prompt_ai"), color = Farby.text)
                        }
                    }
                    Text(T("prompt_ai_info"), color = Farby.tlmene, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    if (!videa) {
                    // tvar obrázka (na výšku = celá postava, na šírku = ležiaca postava, krajina)
                    Text(T("obr_tvar"), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ns = ChatJadro.nastavenia
                        listOf("stvorec", "vyska", "sirka", "nahodne", "vlastne").forEach { t ->
                            val popis = when (t) {
                                "nahodne", "vlastne" -> T("obr_tvar_$t")
                                else -> o.rozmerPomeru(rozmer, t).let { (w, h) -> T("obr_tvar_$t") + "  ${w}×$h" }
                            }
                            FilterChip(selected = ns.obrPomer == t, onClick = { ns.obrPomer = t; ns.uloz() }, label = { Text(popis) },
                                leadingIcon = { Icon(when (t) { "vyska" -> Icons.Default.CropPortrait; "sirka" -> Icons.Default.CropLandscape; "nahodne" -> Icons.Default.Shuffle; "vlastne" -> Icons.Default.Edit; else -> Icons.Default.CropSquare }, null, modifier = Modifier.size(18.dp)) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text, selectedLeadingIconColor = Color.White, iconColor = Farby.text))
                        }
                    }
                    val ns = ChatJadro.nastavenia
                    if (ns.obrPomer == "vlastne") Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var sTxt by remember { mutableStateOf(ns.obrSirka.toString()) }
                        var vTxt by remember { mutableStateOf(ns.obrVyska.toString()) }
                        val farbyPola = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text)
                        OutlinedTextField(value = sTxt, onValueChange = { t -> sTxt = t.filter { it.isDigit() }.take(4); sTxt.toIntOrNull()?.let { ns.obrSirka = it; ns.uloz() } },
                            singleLine = true, label = { Text(T("obr_sirka")) }, modifier = Modifier.width(120.dp).nadKlavesnicou(), shape = RoundedCornerShape(12.dp), colors = farbyPola)
                        Text("×", color = Farby.text)
                        OutlinedTextField(value = vTxt, onValueChange = { t -> vTxt = t.filter { it.isDigit() }.take(4); vTxt.toIntOrNull()?.let { ns.obrVyska = it; ns.uloz() } },
                            singleLine = true, label = { Text(T("obr_vyska")) }, modifier = Modifier.width(120.dp).nadKlavesnicou(), shape = RoundedCornerShape(12.dp), colors = farbyPola)
                        val (w, h) = o.rozmerPomeru(rozmer, "vlastne")
                        Text(T("obr_vlastne_info", w, h), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
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
                    // pre hry: typ obrázka a sada variantov
                    Text(T("hra_typ"), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("obrazok" to Icons.Default.Image, "textura" to Icons.Default.GridOn, "sprite" to Icons.Default.Layers).forEach { (t, ikona) ->
                            FilterChip(selected = typ == t, onClick = { typ = t }, label = { Text(T("hra_$t")) },
                                leadingIcon = { Icon(ikona, null, modifier = Modifier.size(16.dp)) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White, labelColor = Farby.text, iconColor = Farby.text))
                        }
                    }
                    if (typ != "obrazok") Text(T("hra_${typ}_info"), color = Farby.tlmene, fontSize = 12.sp)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(T("hra_pocet"), color = Farby.text, fontSize = 14.sp)
                        listOf(1, 4, 8).forEach { n ->
                            FilterChip(selected = pocet == n, onClick = { pocet = n }, label = { Text("$n") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                        }
                    }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (o.generuje) {
                        if (o.sadaHotovo.second > 1) Text(T("hra_sada", o.sadaHotovo.first + 1, o.sadaHotovo.second), color = Farby.tlmene, fontSize = 13.sp)
                        if (o.kusText.isNotEmpty()) Text(o.kusText, color = Farby.tlmene, fontSize = 13.sp)
                        if (videa && o.skladam) {
                            Text(T("vid_skladam_p", (o.priebeh * 100).roundToInt()), color = Farby.text, fontWeight = FontWeight.SemiBold)
                            Text(T("vid_skladam_info"), color = Farby.tlmene, fontSize = 12.sp)
                        } else Text(T(if (videa) "vid_tvorim" else "obr_tvorim", (o.priebeh * 100).roundToInt()), color = Farby.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { o.priebeh }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                        Spacer(Modifier.height(4.dp)); Casovac(true, o.zaciatok, 0)
                        TextButton(onClick = { o.zrus() }) { Text(T("zrusit"), color = Farby.tlmene) }
                    } else if (videa) {
                        Casovac(false, 0, o.posledneTrvanie)
                        Text(T("vai_dlzka"), color = Farby.text, fontSize = 14.sp)
                        Row(Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val ns = ChatJadro.nastavenia
                            (listOf(1, 2, 3, 5) + (if (o.i2vSada() != null) o.DLHE else emptyList())).forEach { sek ->
                                FilterChip(selected = ns.dlzkaVidea == sek, onClick = { ns.dlzkaVidea = sek; ns.uloz() }, label = { Text("$sek s") },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                            }
                        }
                        if (ChatJadro.nastavenia.dlzkaVidea > 5) Text(T("vai_nadvazovanie"), color = Farby.tlmene, fontSize = 12.sp)
                        else if (ChatJadro.nastavenia.dlzkaVidea >= 3) Text(T("vai_dlho"), color = Farby.tlmene, fontSize = 12.sp)
                        if (o.i2vSada() == null) Text(T("vai_dlhe_wan22"), color = Farby.tlmene, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().clickable { val ns = ChatJadro.nastavenia; ns.setritVram = !ns.setritVram; ns.uloz() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(T("vai_setrit"), color = Farby.text, fontSize = 14.sp)
                                Text(T("vai_setrit_info"), color = Farby.tlmene, fontSize = 12.sp)
                            }
                            Switch(checked = ChatJadro.nastavenia.setritVram, onCheckedChange = { val ns = ChatJadro.nastavenia; ns.setritVram = it; ns.uloz() },
                                colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                        }
                        Row(Modifier.fillMaxWidth().clickable { val ns = ChatJadro.nastavenia; ns.videoKusy = !ns.videoKusy; ns.uloz() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(T("vai_kusy"), color = Farby.text, fontSize = 14.sp)
                                Text(T("vai_kusy_info"), color = Farby.tlmene, fontSize = 12.sp)
                            }
                            Switch(checked = ChatJadro.nastavenia.videoKusy, onCheckedChange = { val ns = ChatJadro.nastavenia; ns.videoKusy = it; ns.uloz() },
                                colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                        }
                        // skladanie snímok: VAE (kvalita) / TAE / LightTAE (rýchle)
                        val nsk = ChatJadro.nastavenia
                        if (o.aktivnaSada()?.let { o.maSkladanie(it) } == true || o.videoSada()?.let { o.maSkladanie(it) } == true) {
                            Text(T("vid_skladanie"), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("vae", "lighttae", "tae").forEach { t ->
                                    FilterChip(selected = nsk.skladanie == t, onClick = { if (!o.generuje) o.nastavSkladanie(t) }, label = { Text(T("vid_skl_$t")) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                                }
                            }
                            o.skladaniePriebeh?.let { sp -> LinearProgressIndicator(progress = { sp }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = Farby.ruzova, trackColor = Farby.plocha2) }
                            Text(T("vid_skl_info_${nsk.skladanie}"), color = Farby.tlmene, fontSize = 12.sp)
                        }
                        // AI zväčšenie rovno pri tvorbe (zväčšujú sa len skutočné snímky)
                        Text(T("vid_zvacsenie"), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to T("vid_zv_nie"), 1080 to "Full HD", 2160 to "4K").forEach { (r, nazov) ->
                                FilterChip(selected = nsk.videoZvacsenie == r, onClick = {
                                    nsk.videoZvacsenie = r; nsk.uloz()
                                    if (r > 0 && !VylepsenieJadro.mamModel()) VylepsenieJadro.stiahniModel()
                                }, label = { Text(nazov) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                            }
                        }
                        VylepsenieJadro.priebehStahovania?.let { sp -> LinearProgressIndicator(progress = { sp }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = Farby.ruzova, trackColor = Farby.plocha2) }
                        if (nsk.videoZvacsenie > 0) Text(T("vid_zv_info"), color = Farby.tlmene, fontSize = 12.sp)
                        // najprv presný obrázok (FLUX…), potom ho Wan 2.2 rozhýbe
                        val ns2 = ChatJadro.nastavenia
                        val mozeCezObr = o.i2vSada() != null && o.obrazkovaPreVideo() != null
                        Row(Modifier.fillMaxWidth().clickable(enabled = mozeCezObr) { ns2.videoCezObrazok = !ns2.videoCezObrazok; ns2.uloz() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(T("vid_cez_obr"), color = if (mozeCezObr) Farby.text else Farby.tlmene, fontSize = 14.sp)
                                Text(if (mozeCezObr) T("vid_cez_obr_info", o.obrazkovaPreVideo()?.nazov ?: "") else T("vid_cez_obr_nejde"), color = Farby.tlmene, fontSize = 12.sp)
                            }
                            Switch(checked = ns2.videoCezObrazok && mozeCezObr, enabled = mozeCezObr, onCheckedChange = { ns2.videoCezObrazok = it; ns2.uloz() },
                                colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                        }
                        // podľa obrázka: do okienka seed obrázka z galérie ALEBO popis, podľa ktorého FLUX… najprv nakreslí postavu;
                        // hlavný popis je potom len pohyb (nepovinný)
                        var podlaObr by remember { mutableStateOf(false) }
                        var obrText by remember { mutableStateOf("") }
                        val mozePodla = o.i2vSada() != null
                        Row(Modifier.fillMaxWidth().clickable(enabled = mozePodla) { podlaObr = !podlaObr }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = podlaObr && mozePodla, onCheckedChange = { podlaObr = it }, enabled = mozePodla,
                                colors = CheckboxDefaults.colors(checkedColor = Farby.fialova, uncheckedColor = Farby.tlmene))
                            Column(Modifier.weight(1f)) {
                                Text(T("vid_seed"), color = if (mozePodla) Farby.text else Farby.tlmene, fontSize = 14.sp)
                                Text(T(if (mozePodla) "vid_seed_info" else "vai_ziadny_i2v"), color = Farby.tlmene, fontSize = 12.sp)
                            }
                        }
                        val zapnutePodla = podlaObr && mozePodla
                        val seed = obrText.trim().takeIf { t -> t.isNotEmpty() && t.all { it.isDigit() } }?.toLongOrNull()
                        val seedObr = if (zapnutePodla && seed != null) remember(seed, o.galeria.size) { o.najdiPodlaSeedu(seed) } else null
                        val popisPostavy = obrText.trim().takeIf { zapnutePodla && seed == null && it.isNotEmpty() }
                        if (zapnutePodla) {
                            OutlinedTextField(value = obrText, onValueChange = { obrText = it }, minLines = 2, maxLines = 6,
                                label = { Text(T("vid_seed_pole")) }, placeholder = { Text(T("vid_seed_hint"), color = Farby.tlmene, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth().nadKlavesnicou(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                when {
                                    seedObr != null -> {
                                        val nb = remember(seedObr) { Platforma.nacitajObrazok(seedObr) }
                                        if (nb != null) Image(nb, null, Modifier.height(72.dp).aspectRatio(nb.width.toFloat() / nb.height).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                                        Text(T("vid_seed_najdeny"), color = Farby.zelena, fontSize = 12.sp)
                                    }
                                    seed != null -> Text(T("vid_seed_nenajdeny"), color = Farby.cervena, fontSize = 12.sp)
                                    popisPostavy != null -> Text(if (mozeCezObr) T("vid_seed_kresli", o.obrazkovaPreVideo()?.nazov ?: "") else T("vid_cez_obr_nejde"),
                                        color = if (mozeCezObr) Farby.zelena else Farby.cervena, fontSize = 12.sp)
                                }
                            }
                        }
                        val cezObr = !zapnutePodla && ns2.videoCezObrazok && mozeCezObr
                        val mozeVytvorit = when {
                            zapnutePodla && seed != null -> seedObr != null
                            zapnutePodla && popisPostavy != null -> mozeCezObr
                            zapnutePodla -> false
                            else -> prompt.isNotBlank() && (cezObr || (o.stav == StavModelu.PRIPRAVENY && o.jeVideoModel))
                        }
                        GradientTlacidlo(T("vai_vytvorit"), Icons.Default.Movie, Modifier.fillMaxWidth(), enabled = mozeVytvorit && !o.generuje) {
                            val hotovoV: (File?, String) -> Unit = { f, e -> if (f != null) { aiVideo = f; Platforma.oznam(T("vai_hotovo")) } else Platforma.oznam("⚠ $e") }
                            when {
                                seedObr != null -> {
                                    if (Podpis.zakazane(prompt)) { Platforma.oznam(T("upr_zakazane")); return@GradientTlacidlo }
                                    val popis = listOf(o.popisObrazka(seedObr).orEmpty(), prompt.trim().ifBlank { "natural subtle motion, cinematic" }).filter { it.isNotBlank() }.joinToString(", ")
                                    o.natocAI(popis, ns2.dlzkaVidea, seedObr, hotovoV)
                                }
                                popisPostavy != null -> o.natocCezObrazok(popisPostavy, ns2.dlzkaVidea, pohyb = prompt, hotovo = hotovoV)
                                cezObr -> o.natocCezObrazok(prompt, ns2.dlzkaVidea, hotovo = hotovoV)
                                else -> o.natocAI(prompt, ns2.dlzkaVidea, hotovo = hotovoV)
                            }
                        }
                        Text(T("vai_info"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        aiVideo?.let { f ->
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GradientTlacidlo(T("video_prehrat"), Icons.Default.PlayArrow) { Prehravac.otvor(f) }
                                OutlinedButton(onClick = {
                                    val d = File(System.getProperty("user.home"), "Videos/Alexia AI").apply { mkdirs() }
                                    val u = runCatching { f.copyTo(File(d, f.name), overwrite = true) }.getOrNull()
                                    Platforma.oznam(if (u != null) T("video_ulozene") + " " + d.path else T("obr_neulozene"))
                                }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) { Text(T("video_ulozit"), color = Farby.text) }
                            }
                        }
                    } else {
                        GradientTlacidlo(T("obr_vytvorit"), Icons.Default.AutoAwesome, Modifier.fillMaxWidth(),
                            enabled = o.stav == StavModelu.PRIPRAVENY && !o.jeVideoModel && prompt.isNotBlank()) { o.generujHerne(if (typ == "obrazok") popisSPozadim() else prompt, negativny, rozmer, kroky, cfg, typ, pocet, ChatJadro.nastavenia.obrPomer) }
                        Text(T("obr_cas"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (o.chyba.isNotBlank()) Text(o.chyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }

                // prenos štýlu (FLUX.3 Klein): obrázok z appky prekreslí v štýle iného obrázka
                if (!videa) PrenosStyluKarta()
                // videoklip na skladbu z obrázkov appky
                if (videa) VideoklipKarta()

                // galéria
                if (!videa && o.galeria.isNotEmpty()) Sekcia(T("obr_sek_galeria", o.galeria.size), ikona = Icons.Default.PhotoLibrary) {
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
            Sekcia(T(if (videa) "vid_modely" else "obr_modely"), ikona = Icons.Default.Memory) {
            sady.forEach { sd ->
                val aktivny = o.jeAktivna(sd)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (aktivny) Farby.prechodJemny else Brush.linearGradient(listOf(Farby.plocha, Farby.plocha)))
                        .border(1.dp, if (aktivny) Farby.fialova else Farby.okraj, RoundedCornerShape(18.dp))
                        .clickable(enabled = !aktivny && !o.generuje) { pouziSadu(sd) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (aktivny) Icons.Default.CheckCircle else if (videa) Icons.Default.Movie else Icons.Default.Image, null, tint = if (aktivny) Farby.zelena else Farby.tlmene)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sd.nazov, color = Farby.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (aktivny && o.stav == StavModelu.NACITAVA) T("nacitavam_model") else sd.popis, color = Farby.tlmene, fontSize = 12.sp)
                    }
                    IconButton(onClick = { o.zmazSadu(sd); sady = sady - sd }, enabled = !o.generuje) { Icon(Icons.Default.DeleteOutline, T("zmazat"), tint = Farby.tlmene) }
                }
                if (aktivny && o.koderKluc(sd) != null) KoderVolba(sd)
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
            Katalog.obrazky.filter { k -> (k.video != null) == videa && sady.none { it.id == k.id } }.forEach { k ->
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
            if (!videa) Karta {
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
            if (!videa) Text(T("obr_info"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    detail?.let { f ->
        val bmp = remember(f) { Platforma.nacitajObrazok(f) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { detail = null }) {
            // okno sa dá posúvať a obrázok má obmedzenú výšku – inak obrázok na výšku vytlačí rozhýbanie mimo obrazovky
            Column(Modifier.heightIn(max = 900.dp).clip(RoundedCornerShape(20.dp)).background(Farby.plocha).verticalScroll(rememberScrollState()).padding(12.dp)) {
                if (bmp != null) Image(bmp, null, Modifier.heightIn(max = 520.dp).aspectRatio(bmp.width.toFloat() / bmp.height, matchHeightConstraintsFirst = true).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
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
                    SeedStitok(f)
                }
                InfoObrazka(f)
                Model3DRiadok(f)
                RozhybatRiadok(f)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SportsEsports, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp))
                    Text(T("hra_export"), color = Farby.text, fontSize = 13.sp)
                    val hotovo = T("hra_exportovane")
                    listOf(512, 1024, 2048, 4096).forEach { n ->
                        AssistChip(onClick = { o.exportujPreHru(f, n) { e -> Platforma.oznam(if (e != null) "$hotovo ${e.path}" else T("obr_neulozene")) } },
                            label = { Text("$n", fontSize = 12.sp) }, colors = AssistChipDefaults.assistChipColors(labelColor = Farby.text))
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

private var rezimVideaVyl by mutableStateOf(false)

@Composable
private fun ColumnScope.VideoVylepsenie() {
    val v = VylepsenieVidea
    val i = v.info
    val nadpis = T("vv_vybrat")
    PosuvnyStlpec {
        Karta {
            val f = v.vstup
            if (f == null) {
                Text(T("vv_prazdne"), color = Farby.tlmene)
                Spacer(Modifier.height(10.dp))
                GradientTlacidlo(T("vv_vybrat"), Icons.Default.VideoFile) { Platforma.vyberSubor(nadpis, setOf("mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp")) { v.nastavVstup(it) } }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Movie, null, tint = Farby.ruzova); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = Farby.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (i != null) Text("${i.sirka}×${i.vyska} · ${"%.0f".format(i.fpsCislo)} fps · ${(i.dlzka / 60).toInt()}:${"%02d".format((i.dlzka % 60).toInt())}", color = Farby.tlmene, fontSize = 12.sp)
                        else if (v.chyba.isEmpty()) Text(T("vv_citam"), color = Farby.tlmene, fontSize = 12.sp)
                    }
                    TextButton(onClick = { Platforma.vyberSubor(nadpis, setOf("mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp")) { v.nastavVstup(it) } }, enabled = !v.pracuje) { Text(T("vyl_ina"), color = Farby.ruzova) }
                }
            }
            VideoJadro.priebehFfmpeg?.let { p ->
                Text(T("video_ffmpeg", (p * 100).roundToInt()), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
            }
        }
        Karta {
            Text(T("vyl_zvacsenie"), color = Farby.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to T("vyl_bez"), 2 to "2× AI", 1080 to "Full HD AI", 2160 to "4K AI").forEach { (n, nazov) ->
                    FilterChip(selected = v.ciel == n, onClick = { v.ciel = n }, label = { Text(nazov) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                }
            }
            if (i != null && v.ciel != 0) v.cielovyRozmer(i).let { (w, h) -> Text("→ ${w}×$h", color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            if (v.ciel != 0) Text(T("vv_ai_cas"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (v.ciel != 0 && !VylepsenieJadro.mamModel()) {
                Spacer(Modifier.height(8.dp))
                Text(T("vyl_model", VylepsenieJadro.MB), color = Farby.tlmene, fontSize = 13.sp)
                val p = VylepsenieJadro.priebehStahovania
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                else GradientTlacidlo(T("stiahnut"), Icons.Default.Download, Modifier.padding(top = 6.dp)) { VylepsenieJadro.stiahniModel() }
            }
            Spacer(Modifier.height(10.dp))
            listOf(
                Triple(T("vv_ostrost"), v.ostrost) { b: Boolean -> v.ostrost = b },
                Triple(T("vv_sum"), v.sum) { b: Boolean -> v.sum = b },
                Triple(T("vv_farby"), v.farby) { b: Boolean -> v.farby = b },
                Triple(T("vv_stabil"), v.stabilizacia) { b: Boolean -> v.stabilizacia = b },
                Triple(T("vv_60fps"), v.plynulost) { b: Boolean -> v.plynulost = b },
                Triple(T("vv_zvuk"), v.zvuk) { b: Boolean -> v.zvuk = b },
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
                LinearProgressIndicator(progress = { v.priebeh }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                TextButton(onClick = { v.zrus() }) { Text(T("zrusit"), color = Farby.tlmene) }
            } else {
                GradientTlacidlo(T("vv_spustit"), Icons.Default.AutoFixHigh, Modifier.fillMaxWidth(),
                    enabled = i != null && (v.ciel == 0 || VylepsenieJadro.mamModel()) && VideoJadro.priebehFfmpeg == null) { v.vylepsi() }
            }
            if (v.chyba.isNotBlank()) Text(v.chyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            v.vysledok?.let { r ->
                Spacer(Modifier.height(10.dp))
                Text(T("vv_hotovo", r.path), color = Farby.zelena, fontSize = 13.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientTlacidlo(T("video_otvorit"), Icons.Default.PlayArrow) {
                        Prehravac.otvor(r)
                    }
                    OutlinedButton(onClick = { val u = v.ulozDoVidei(); Platforma.oznam(if (u != null) T("video_ulozene") + " " + u.parent else T("obr_neulozene")) },
                        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) { Text(T("vv_do_videi"), color = Farby.text) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = Farby.tlmene, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(T("vv_info"), color = Farby.tlmene, fontSize = 12.sp)
        }
    }
}

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
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to T("vv_fotka"), true to T("vv_video")).forEach { (je, nazov) ->
                FilterChip(selected = rezimVideaVyl == je, onClick = { rezimVideaVyl = je }, label = { Text(nazov) },
                    leadingIcon = { Icon(if (je) Icons.Default.Movie else Icons.Default.Image, null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White, labelColor = Farby.text, iconColor = Farby.text))
            }
        }
        if (rezimVideaVyl) { VideoVylepsenie(); return@Column }
        PosuvnyStlpec {
            Sekcia(T("vyl_sek_fotka"), ikona = Icons.Default.AddPhotoAlternate) {
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

            Sekcia(T("vyl_zvacsenie"), ikona = Icons.Default.AutoFixHigh) {
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
                if (!v.pracuje) Casovac(false, 0, v.posledneTrvanie)
                if (v.pracuje) {
                    Text(v.cinnost, color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Casovac(true, v.zaciatok, 0)
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
    Aktualizacie.pripravena?.let { pv ->
        var neskor by remember { mutableStateOf(false) }
        if (!neskor) AlertDialog(
            onDismissRequest = { neskor = true }, containerColor = Farby.plocha,
            icon = { Icon(Icons.Default.SystemUpdate, null, tint = Farby.zelena) },
            title = { Text(T("akt_pripravena", pv), color = Farby.text) },
            text = { Text(T("akt_pripravena_text"), color = Farby.tlmene) },
            confirmButton = { TextButton(onClick = { Aktualizacie.restartuj() }) { Text(T("akt_restart"), color = Farby.ruzova) } },
            dismissButton = { TextButton(onClick = { neskor = true }) { Text(T("akt_neskor"), color = Farby.tlmene) } },
        )
        return
    }
    val v = Aktualizacie.novaVerzia ?: return
    val p = Aktualizacie.priebeh
    AlertDialog(
        onDismissRequest = { if (p == null) Aktualizacie.novaVerzia = null },
        containerColor = Farby.plocha,
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = Farby.ruzova) },
        title = { Text(T("akt_nadpis", v), color = Farby.text) },
        text = {
            Column {
                Text(T(if (Aktualizacie.samaSaVymeni || Aktualizacie.moze()) "akt_text_sama" else "akt_text_zip"), color = Farby.tlmene)
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

// ---------------------------------------------------------------- história chatov (šifrovaná)

/** riadok, ktorý sa zalomí na ďalší (žiadne skryté položky za okrajom) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Zalamuj(obsah: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { obsah() }
}

@Composable
private fun CitacDialog(zavri: () -> Unit) {
    val d = Dokumenty.aktualny ?: return
    val n = ChatJadro.nastavenia
    androidx.compose.ui.window.Dialog(onDismissRequest = zavri) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).background(Farby.plocha).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MenuBook, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(d.nazov, color = Farby.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = zavri) { Icon(Icons.Default.Close, T("zrusit"), tint = Farby.text) }
            }
            Text(T("citac_info", Hlas.meno(n.hlas), Citac.index + 1, d.odseky.size), color = Farby.tlmene, fontSize = 12.sp)
            // aktuálny odsek + pár ďalších
            val stav = rememberScrollState()
            LaunchedEffect(Citac.index) { stav.scrollTo(0) }
            Column(Modifier.weight(1f, fill = false).padding(vertical = 10.dp).verticalScroll(stav)) {
                for (i in Citac.index until minOf(Citac.index + 4, d.odseky.size)) {
                    Text(d.odseky[i], color = if (i == Citac.index) Farby.text else Farby.tlmene, fontSize = if (i == Citac.index) 16.sp else 14.sp,
                        modifier = Modifier.padding(bottom = 10.dp).clickable { Citac.chodNa(i) })
                }
            }
            var pos by remember(Citac.index) { mutableStateOf(Citac.index.toFloat()) }
            Slider(value = pos, onValueChange = { pos = it }, onValueChangeFinished = { Citac.chodNa(pos.roundToInt()) }, valueRange = 0f..(d.odseky.size - 1).coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Citac.posun(-1) }) { Icon(Icons.Default.SkipPrevious, null, tint = Farby.text) }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(56.dp).clip(CircleShape).background(Farby.prechod).clickable { if (Citac.cita) Citac.zastav() else Citac.spusti() }, contentAlignment = Alignment.Center) {
                    Icon(if (Citac.cita) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { Citac.posun(1) }) { Icon(Icons.Default.SkipNext, null, tint = Farby.text) }
            }
            if (!Hlas.mam()) Text(T("hlas_najprv"), color = Farby.cervena, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HistoriaDialog(zavri: () -> Unit) {
    val j = ChatJadro
    var naZmazanie by remember { mutableStateOf<Historia.Zaznam?>(null) }
    val datum = remember { java.text.SimpleDateFormat("d. M. yyyy HH:mm", java.util.Locale.getDefault()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = zavri) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).background(Farby.plocha).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(T("hist_nadpis"), color = Farby.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = zavri) { Icon(Icons.Default.Close, T("zrusit"), tint = Farby.text) }
            }
            Text(T("hist_info"), color = Farby.tlmene, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            GradientTlacidlo(T("novy_chat"), Icons.Default.AddComment, Modifier.fillMaxWidth()) { j.novyChat(); zavri() }
            Spacer(Modifier.height(8.dp))
            if (Historia.zoznam.isEmpty()) Text(T("hist_prazdna"), color = Farby.tlmene, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(Historia.zoznam.toList(), key = { it.id }) { z ->
                    val je = z.id == Historia.aktualne
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (je) Farby.fialova.copy(alpha = 0.25f) else Farby.plocha2)
                        .clickable { j.otvor(z.id); zavri() }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(z.nazov, color = Farby.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(datum.format(java.util.Date(z.cas)), color = Farby.tlmene, fontSize = 12.sp)
                        }
                        IconButton(onClick = { naZmazanie = z }) { Icon(Icons.Default.DeleteOutline, T("hist_zmazat"), tint = Farby.tlmene) }
                    }
                }
            }
        }
    }
    naZmazanie?.let { z ->
        AlertDialog(
            onDismissRequest = { naZmazanie = null }, containerColor = Farby.plocha,
            title = { Text(T("hist_zmazat_otazka"), color = Farby.text) },
            text = { Text(z.nazov, color = Farby.tlmene) },
            confirmButton = { TextButton(onClick = { j.zmazChat(z.id); naZmazanie = null }) { Text(T("hist_zmazat"), color = Farby.cervena) } },
            dismissButton = { TextButton(onClick = { naZmazanie = null }) { Text(T("zrusit"), color = Farby.text) } },
        )
    }
}

@Composable
private fun Odomknutie() {
    var heslo by remember { mutableStateOf("") }
    var zle by remember { mutableStateOf(false) }
    var pracuje by remember { mutableStateOf(false) }
    val skus = {
        pracuje = true
        val h = heslo
        thread { val ok = ChatJadro.odomkni(h); Platforma.naHlavnom { pracuje = false; zle = !ok; if (ok) heslo = "" } }
        Unit
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Farby.prechod), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(T("hist_zamknute"), color = Farby.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = heslo, onValueChange = { heslo = it; zle = false }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                label = { Text(T("hist_heslo")) }, isError = zle, modifier = Modifier.fillMaxWidth().nadKlavesnicou()
                    .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { skus(); true } else false },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
            if (zle) Text(T("hist_zle_heslo"), color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            if (pracuje) CircularProgressIndicator(color = Farby.ruzova)
            else GradientTlacidlo(T("hist_odomknut"), Icons.Default.LockOpen, Modifier.fillMaxWidth(), enabled = heslo.isNotEmpty()) { skus() }
        }
    }
}

@Composable
private fun HesloKarta() {
    var nove by remember { mutableStateOf("") }
    var znova by remember { mutableStateOf("") }
    var ma by remember { mutableStateOf(Sifra.maHeslo()) }
    val farbyPola = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text)
    Text(T(if (ma) "hist_heslo_ma" else "hist_heslo_nema"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    OutlinedTextField(value = nove, onValueChange = { nove = it }, singleLine = true, label = { Text(T("hist_nove_heslo")) },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().nadKlavesnicou(), shape = RoundedCornerShape(14.dp), colors = farbyPola)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(value = znova, onValueChange = { znova = it }, singleLine = true, label = { Text(T("hist_heslo_znova")) },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), isError = znova.isNotEmpty() && znova != nove,
        modifier = Modifier.fillMaxWidth().nadKlavesnicou(), shape = RoundedCornerShape(14.dp), colors = farbyPola)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GradientTlacidlo(T(if (ma) "hist_zmenit" else "hist_nastavit"), Icons.Default.Lock, enabled = nove.length >= 4 && nove == znova) {
            runCatching { Sifra.nastavHeslo(nove) }.onSuccess { ma = true; nove = ""; znova = ""; Platforma.oznam(T("hist_heslo_ok")) }
        }
        if (ma) OutlinedButton(onClick = { runCatching { Sifra.nastavHeslo(null) }.onSuccess { ma = false; Platforma.oznam(T("hist_heslo_zrusene")) } },
            shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) { Text(T("hist_zrusit_heslo"), color = Farby.tlmene) }
    }
    Text(T("hist_upozornenie"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

/** 3D model z obrázka (len obrázky z appky so seedom) */
@Composable
private fun Model3DRiadok(f: File) {
    val m = Model3D
    if (remember(f) { ObrazkyJadro.seedObrazka(f) } == null) return
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Farby.plocha2).padding(10.dp)) {
        val jeTento = m.zdroj == f
        when {
            m.pripravuje || (m.pracuje && jeTento) -> {
                Text(m.cinnost, color = Farby.text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { m.priebeh }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha)
            }
            jeTento && m.vysledok != null -> {
                val v = m.vysledok!!
                Text(T("m3d_hotovo", v.parentFile.path), color = Farby.zelena, fontSize = 13.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GradientTlacidlo(T("m3d_otvorit"), Icons.Default.ViewInAr) { m.otvor(v) }
                    OutlinedButton(onClick = { m.otvor(v.parentFile) }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) { Text(T("m3d_priecinok"), color = Farby.text, fontSize = 13.sp) }
                    OutlinedButton(onClick = { Platforma.otvorOdkaz("https://www.mixamo.com") }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) { Text(T("m3d_rig"), color = Farby.text, fontSize = 13.sp) }
                }
                Text(T("m3d_rig_info"), color = Farby.tlmene, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ViewInAr, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(T("m3d_nadpis"), color = Farby.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    AssistChip(onClick = { m.vytvor(f) }, enabled = !m.pracuje, label = { Text(T("m3d_vytvorit"), fontSize = 12.sp) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = Farby.text))
                }
                if (!m.jePripravene()) Text(T("m3d_prvy", Model3D.MB / 1000.0), color = Farby.tlmene, fontSize = 11.sp)
            }
        }
        if (m.chyba.isNotBlank() && (m.zdroj == f || m.zdroj == null)) Text(m.chyba, color = Farby.cervena, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** rozhýbať obrázok z appky (video AI Wan 2.2) */
@Composable
private fun RozhybatRiadok(f: File) {
    val o = ObrazkyJadro
    if (remember(f) { o.seedObrazka(f) } == null || o.i2vSada() == null) return
    var vysledok by remember { mutableStateOf<File?>(null) }
    var pohyb by remember { mutableStateOf("") }
    var pracuje by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Farby.plocha2).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Movie, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(T("vai_rozhybat"), color = Farby.text, fontSize = 13.sp)
        }
        OutlinedTextField(value = pohyb, onValueChange = { pohyb = it }, singleLine = true, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().nadKlavesnicou(),
            placeholder = { Text(T("vai_pohyb_hint"), color = Farby.tlmene, fontSize = 12.sp) }, shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
        if (pracuje) {
            Text(T("vai_tvorim"), color = Farby.text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            LinearProgressIndicator(progress = { o.priebeh }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha)
        } else Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = {
                if (Podpis.zakazane(pohyb)) { Platforma.oznam(T("upr_zakazane")); return@AssistChip }
                pracuje = true
                val popis = listOf(o.popisObrazka(f).orEmpty(), pohyb.ifBlank { "natural subtle motion, cinematic" }).filter { it.isNotBlank() }.joinToString(", ")
                o.natocAI(popis, null, f) { v, e -> pracuje = false; if (v != null) vysledok = v else Platforma.oznam("⚠ $e") }
            }, enabled = !o.generuje, label = { Text(T("vai_rozhybat_tlacidlo"), fontSize = 12.sp) }, colors = AssistChipDefaults.assistChipColors(labelColor = Farby.text))
            vysledok?.let { v ->
                AssistChip(onClick = { Prehravac.otvor(v) },
                    label = { Text(T("video_prehrat"), fontSize = 12.sp) }, colors = AssistChipDefaults.assistChipColors(labelColor = Farby.zelena))
            }
        }
    }
}


// ---------------------------------------------------------------- LoRA adaptéry (obrázky, videá, chat)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoraObrazovka(spat: () -> Unit) {
    var kat by remember { mutableStateOf("obrazky") }
    var odkaz by remember { mutableStateOf("") }
    var priebeh by remember { mutableStateOf<Float?>(null) }
    var chyba by remember { mutableStateOf("") }
    val ver = Lory.verzia
    val subory = remember(kat, ver, priebeh) { Lory.subory(kat) }
    val nadpisVyberu = T("lora_vybrat")
    fun hotovo(f: File?, e: Throwable?) {
        priebeh = null
        if (f != null) { Lory.pridany(); Lory.prepni(kat, f.name, true); chyba = "" } else chyba = e?.message ?: T("lora_chyba")
    }
    Column(Modifier.fillMaxSize()) {
        Hlavicka(T("lora_nadpis"), T("lora_podnadpis"), spat = spat)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Lory.KATEGORIE.forEach { k ->
                    val n = remember(ver) { Lory.pocetZapnutych(k) }
                    FilterChip(selected = kat == k, onClick = { kat = k; chyba = "" }, label = { Text(T("lora_kat_$k") + if (n > 0) "  ($n)" else "") },
                        leadingIcon = { Icon(when (k) { "obrazky" -> Icons.Default.Image; "videa" -> Icons.Default.Movie; else -> Icons.Default.Forum }, null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White, labelColor = Farby.text, iconColor = Farby.text))
                }
            }
            Karta {
                Text(T("lora_info_$kat"), color = Farby.tlmene, fontSize = 13.sp)
            }
            // zoznam LoRA v kategórii
            Sekcia(T("lora_moje")) {
                if (subory.isEmpty()) Text(T("lora_prazdne"), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                subory.forEach { f ->
                    val zap = remember(ver, f) { Lory.zapnuta(kat, f.name) }
                    var sila by remember(ver, f) { mutableStateOf(Lory.sila(kat, f.name)) }
                    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Farby.plocha2).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = zap, onCheckedChange = { Lory.prepni(kat, f.name, it) }, colors = CheckboxDefaults.colors(checkedColor = Farby.fialova, uncheckedColor = Farby.tlmene))
                            Column(Modifier.weight(1f)) {
                                Text(f.nameWithoutExtension, color = Farby.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Modely.velkost(f), color = Farby.tlmene, fontSize = 11.sp)
                            }
                            IconButton(onClick = { Lory.zmaz(kat, f) }) { Icon(Icons.Default.DeleteOutline, T("zmazat"), tint = Farby.tlmene) }
                        }
                        if (zap) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(T("lora_sila", "%.2f".format(sila)), color = Farby.text, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                            Slider(value = sila, onValueChange = { sila = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { Lory.nastavSilu(kat, f.name, sila) },
                                valueRange = -1f..2f, modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha))
                        }
                    }
                }
            }
            // pridanie
            Sekcia(T("lora_pridat")) {
                OutlinedTextField(value = odkaz, onValueChange = { odkaz = it }, singleLine = true, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().nadKlavesnicou(),
                    placeholder = { Text(T("lora_odkaz_hint"), color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
                val p = priebeh
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                else Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientTlacidlo(T("stiahnut"), Icons.Default.Download, enabled = odkaz.trim().startsWith("http")) {
                        val url = odkaz.trim(); val k = kat; priebeh = 0f
                        thread {
                            val r = runCatching { Modely.stiahni(url, { x -> Platforma.naHlavnom { priebeh = x } }, { false }, Lory.priecinok(k), true) }
                            Platforma.naHlavnom { hotovo(r.getOrNull(), r.exceptionOrNull()); if (r.isSuccess) odkaz = "" }
                        }
                    }
                    OutlinedButton(onClick = {
                        val k = kat
                        Platforma.vyberSubor(nadpisVyberu, Lory.pripony(k)) { f ->
                            priebeh = 0f
                            thread {
                                val r = runCatching {
                                    val ciel = File(Lory.priecinok(k), f.name)
                                    f.inputStream().use { i -> ciel.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }; ciel
                                }
                                Platforma.naHlavnom { hotovo(r.getOrNull(), r.exceptionOrNull()) }
                            }
                        }
                    }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.FolderOpen, null, tint = Farby.text); Spacer(Modifier.width(6.dp)); Text(T("zo_suboru_kratko"), color = Farby.text)
                    }
                    OutlinedButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(Lory.priecinok(kat)) } }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.Folder, null, tint = Farby.text); Spacer(Modifier.width(6.dp)); Text(T("lora_priecinok"), color = Farby.text)
                    }
                    TextButton(onClick = { Lory.pridany() }) { Text(T("lora_obnovit"), color = Farby.ruzova) }
                }
                if (chyba.isNotBlank()) Text(chyba, color = Farby.cervena, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text(T("lora_kam", Lory.priecinok(kat).path), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}


@Composable
private fun PrenosStyluKarta() {
    val o = ObrazkyJadro
    var obsah by remember { mutableStateOf<File?>(null) }
    var styl by remember { mutableStateOf<File?>(null) }
    var doplnok by remember { mutableStateOf("") }
    var chyba by remember { mutableStateOf("") }
    var vysledok by remember { mutableStateOf<File?>(null) }
    var bezi by remember { mutableStateOf(false) }
    val nadpisObsah = T("styl_vyber_obsah"); val nadpisStyl = T("styl_vyber_styl")
    val klein = remember(o.stav) { o.kleinSada() }
    // 0 = vyberá sa obsah, 1 = štýl (okno s náhľadmi galérie)
    var vyberPre by remember { mutableStateOf<Int?>(null) }
    vyberPre?.let { i ->
        GaleriaVyberDialog(
            nadpis = if (i == 0) nadpisObsah else nadpisStyl,
            zoSuboru = if (i == 1) ({ Platforma.vyberSubor(nadpisStyl, setOf("png", "jpg", "jpeg", "bmp")) { v -> chyba = ""; styl = v }; vyberPre = null }) else null,
            zavri = { vyberPre = null },
        ) { f -> chyba = ""; if (i == 0) obsah = f else styl = f; vyberPre = null }
    }
    Sekcia(T("styl_nadpis"), ikona = Icons.Default.Palette) {
        Text(T("styl_info"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        if (klein == null) Text(T("styl_treba_klein"), color = Farby.cervena, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf(Triple(obsah, "styl_obsah", nadpisObsah), Triple(styl, "styl_styl", nadpisStyl)).forEachIndexed { i, (f, kluc, nadpis) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(T(kluc), color = Farby.text, fontSize = 13.sp)
                    val nb = remember(f) { f?.let { Platforma.nacitajObrazok(it) } }
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(Farby.plocha2)
                        .clickable { vyberPre = i }, contentAlignment = Alignment.Center) {
                        if (nb != null) Image(nb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AddPhotoAlternate, null, tint = Farby.tlmene)
                            Text(T(if (i == 0) "styl_obsah_hint" else "styl_styl_hint"), color = Farby.tlmene, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable { val ns = ChatJadro.nastavenia; ns.autoStyl = !ns.autoStyl; ns.uloz() }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(T("styl_auto"), color = Farby.text, fontSize = 14.sp)
                Text(T("styl_auto_info"), color = Farby.tlmene, fontSize = 12.sp)
            }
            Switch(checked = ChatJadro.nastavenia.autoStyl, onCheckedChange = { val ns = ChatJadro.nastavenia; ns.autoStyl = it; ns.uloz() },
                colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
        }
        OutlinedTextField(value = doplnok, onValueChange = { doplnok = it }, singleLine = true, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().nadKlavesnicou(),
            placeholder = { Text(T("styl_doplnok_hint"), color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
        if (bezi && Videnie.pracuje) {
            Text(Videnie.cinnost, color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Casovac(true, Videnie.zaciatok, 0)
        } else if (bezi) {
            Text(T("styl_pracuje", (o.priebeh * 100).roundToInt()), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            LinearProgressIndicator(progress = { o.priebeh }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
        } else GradientTlacidlo(T("styl_spustit"), Icons.Default.Palette, Modifier.padding(top = 8.dp).fillMaxWidth(),
            enabled = obsah != null && styl != null && klein != null && !o.generuje) {
            bezi = true; chyba = ""; vysledok = null
            o.prenosStylu(obsah!!, styl!!, doplnok) { f, e -> bezi = false; if (f != null) { vysledok = f; Platforma.oznam(T("styl_hotovo")) } else chyba = e }
        }
        if (chyba.isNotBlank()) Text(chyba, color = Farby.cervena, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        vysledok?.let { f ->
            val nb = remember(f) { Platforma.nacitajObrazok(f) }
            if (nb != null) Image(nb, null, Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
            Text(T("styl_ulozene"), color = Farby.zelena, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}


/** rozbaľovacia sekcia: vidno len nadpis, po kliknutí sa ukáže obsah (stav sa pamätá, kým appka beží) */
private val otvoreneSekcie = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

@Composable
private fun Sekcia(nadpis: String, modifier: Modifier = Modifier, otvorena: Boolean = false, ikona: androidx.compose.ui.graphics.vector.ImageVector? = null,
                   obsah: @Composable ColumnScope.() -> Unit) {
    val otv = otvoreneSekcie[nadpis] ?: otvorena
    Karta(modifier) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { otvoreneSekcie[nadpis] = !otv }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (ikona != null) { Icon(ikona, null, tint = Farby.ruzova, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(nadpis, color = Farby.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (otv) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Farby.ruzova)
        }
        androidx.compose.animation.AnimatedVisibility(visible = otv) { Column(Modifier.padding(top = 4.dp)) { obsah() } }
    }
}

private fun jeOtvorena(kluc: String, predvolene: Boolean = false) = otvoreneSekcie[kluc] ?: predvolene

/** hlavička rozbaľovacej sekcie pre zoznamy (LazyColumn), obsah sa zobrazí cez [jeOtvorena] */
@Composable
private fun SekciaHlavicka(nadpis: String, ikona: androidx.compose.ui.graphics.vector.ImageVector? = null, kluc: String = nadpis) {
    val otv = jeOtvorena(kluc)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Farby.plocha).border(1.dp, Farby.okraj, RoundedCornerShape(20.dp))
        .clickable { otvoreneSekcie[kluc] = !otv }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ikona != null) { Icon(ikona, null, tint = Farby.ruzova, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(nadpis, color = Farby.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(if (otv) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Farby.ruzova)
    }
}

/** výber obrázka z galérie appky s náhľadmi (pri štýle aj tlačidlo „Zo súboru…“) */
@Composable
private fun GaleriaVyberDialog(nadpis: String, zoSuboru: (() -> Unit)?, zavri: () -> Unit, zvol: (File) -> Unit) {
    val g = ObrazkyJadro.galeria.toList()
    androidx.compose.ui.window.Dialog(onDismissRequest = zavri, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(0.9f).heightIn(max = 760.dp).clip(RoundedCornerShape(20.dp)).background(Farby.plocha).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nadpis, color = Farby.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (zoSuboru != null) OutlinedButton(onClick = zoSuboru, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                    Icon(Icons.Default.FolderOpen, null, tint = Farby.text); Spacer(Modifier.width(6.dp)); Text(T("zo_suboru_kratko"), color = Farby.text)
                }
                IconButton(onClick = zavri) { Icon(Icons.Default.Close, null, tint = Farby.tlmene) }
            }
            if (g.isEmpty()) Text(T("pc_galeria_prazdna_pc"), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(g.size) { idx ->
                    val f = g[idx]
                    val nahlad = remember(f) { Platforma.nacitajObrazok(f, 4) }
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Farby.plocha2).clickable { zvol(f) }) {
                        if (nahlad != null) Image(nahlad, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
}

/** Stopky: počas tvorby počíta od 0, po dokončení ukáže celkový čas. */
@Composable
private fun Casovac(bezi: Boolean, zaciatok: Long, trvanie: Long) {
    var teraz by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(bezi, zaciatok) {
        while (bezi) { teraz = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
    }
    if (bezi && zaciatok > 0) Text("⏱ " + Cas.text(teraz - zaciatok), color = Farby.ruzova, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    else if (!bezi && trvanie > 0) Text("✓ " + T("cas_hotovo", Cas.text(trvanie)), color = Farby.tlmene, fontSize = 12.sp)
}

/** Pod obrázkom: prompt (dá sa skopírovať), seed, čas tvorby, rozmer a model. */
@Composable
private fun InfoObrazka(f: java.io.File) {
    val u = remember(f) { ObrazkyJadro.udaje(f) } ?: return
    val schranka = androidx.compose.ui.platform.LocalClipboardManager.current
    val popis = u.optString("popis")
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Farby.plocha2).padding(10.dp)) {
        if (popis.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(T("cas_info_prompt"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { schranka.setText(androidx.compose.ui.text.AnnotatedString(popis)) }) { Text(T("cas_kopirovat"), color = Farby.ruzova, fontSize = 12.sp) }
            }
            Text(popis, color = Farby.text, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
        }
        if (u.has("seed")) InfoRiadok("cas_info_seed", u.optLong("seed").toString())
        if (u.optLong("trvanie") > 0) InfoRiadok("cas_info_trvanie", Cas.text(u.optLong("trvanie")))
        if (u.optInt("w") > 0) InfoRiadok("cas_info_rozmer", "${u.optInt("w")} × ${u.optInt("h")}")
        InfoRiadok("cas_info_model", u.optString("model").substringAfterLast('/').substringAfterLast('\\'))
        InfoRiadok("cas_info_kde", u.optString("kde"))
    }
}

@Composable
private fun InfoRiadok(k: String, h: String) {
    if (h.isNotBlank()) Row { Text(T(k) + ": ", color = Farby.tlmene, fontSize = 12.sp); Text(h, color = Farby.text, fontSize = 12.sp) }
}

/** Videoklip: skladba + obrázky z galérie appky → video strihané do rytmu */
@Composable
private fun VideoklipKarta() {
    val k = Videoklip
    var skladba by remember { mutableStateOf<java.io.File?>(null) }
    val fotky = remember { mutableStateListOf<java.io.File>() }
    var tempo by remember { mutableIntStateOf(2) }
    var spev by remember { mutableStateOf(false) }
    var vyber by remember { mutableStateOf(false) }
    var hotove by remember { mutableStateOf<java.io.File?>(null) }
    var chyba by remember { mutableStateOf("") }
    Sekcia(T("klip_nadpis"), ikona = Icons.Default.MusicVideo) {
        Text(T("klip_info"), color = Farby.tlmene, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(skladba?.name ?: T("klip_ziadna_skladba"), color = if (skladba != null) Farby.text else Farby.tlmene, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { Platforma.vyberSubor(T("klip_vyber_skladbu"), Videoklip.PRIPONY_HUDBY) { skladba = it } }, enabled = !k.pracuje) {
                Text(T("klip_skladba"), color = Farby.ruzova)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(T("klip_fotky", fotky.size), color = Farby.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { vyber = true }, enabled = !k.pracuje) { Text(T("klip_pridat"), color = Farby.ruzova) }
            TextButton(onClick = { fotky.clear(); fotky.addAll(ObrazkyJadro.galeria.take(30).reversed()) }, enabled = !k.pracuje && ObrazkyJadro.galeria.isNotEmpty()) {
                Text(T("klip_posledne"), color = Farby.ruzova)
            }
            if (fotky.isNotEmpty()) IconButton(onClick = { fotky.clear() }, enabled = !k.pracuje) { Icon(Icons.Default.DeleteOutline, null, tint = Farby.tlmene) }
        }
        if (fotky.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fotky.forEachIndexed { i, f ->
                val n = remember(f) { Platforma.nacitajObrazok(f, 8) }
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Farby.plocha2).clickable(enabled = !k.pracuje) { fotky.removeAt(i) }) {
                    if (n != null) Image(n, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Text("${i + 1}", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).background(Color(0x88000000)).padding(horizontal = 4.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable(enabled = !k.pracuje) { spev = !spev }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(T("spev_prepinac"), color = Farby.text, fontSize = 14.sp)
                Text(T("spev_info"), color = Farby.tlmene, fontSize = 12.sp)
            }
            Switch(checked = spev, onCheckedChange = { spev = it }, enabled = !k.pracuje, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
        }
        if (!spev) Text(T("klip_tempo"), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (!spev) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "klip_pomaly", 2 to "klip_stredne", 3 to "klip_rychlo").forEach { (t, kl) ->
                FilterChip(selected = tempo == t, onClick = { tempo = t }, label = { Text(T(kl)) }, enabled = !k.pracuje,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (k.pracuje) {
            Text(k.cinnost, color = Farby.text, fontWeight = FontWeight.SemiBold)
            Casovac(true, k.zaciatok, 0)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { k.priebeh }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
            TextButton(onClick = { k.zrus() }) { Text(T("zrusit"), color = Farby.tlmene) }
        } else {
            Casovac(false, 0, k.posledneTrvanie)
            GradientTlacidlo(T(if (spev) "spev_vytvorit" else "klip_vytvorit"), Icons.Default.MusicVideo, Modifier.fillMaxWidth(), enabled = skladba != null && fotky.isNotEmpty()) {
                chyba = ""; hotove = null
                if (spev) k.spievaj(fotky.first(), skladba!!) { f, e -> hotove = f; chyba = e }
                else k.vytvor(skladba!!, fotky.toList(), tempo) { f, e -> hotove = f; chyba = e }
            }
        }
        if (chyba.isNotBlank()) Text("⚠ $chyba", color = Farby.ruzova, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        hotove?.let { f ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✓ " + f.name, color = Farby.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { Prehravac.otvor(f) }) {
                    Text(T("klip_prehrat"), color = Farby.ruzova)
                }
            }
        }
    }
    if (vyber) GaleriaVyberDialog(T("klip_pridat"), null, { vyber = false }) { f -> if (f !in fotky) fotky += f; vyber = false }
}

/** Sekcia Prompt: model, ktorý vidí obrázky, napíše z ľubovoľného obrázka (aj bežnej fotky) veľmi podrobný prompt */
@Composable
private fun PromptObrazovka(spat: () -> Unit) {
    val v = Videnie
    var obrazok by remember { mutableStateOf<File?>(null) }
    var rezim by remember { mutableStateOf(Videnie.Rezim.PROMPT) }
    var vysledok by remember { mutableStateOf("") }
    var chyba by remember { mutableStateOf("") }
    var vyber by remember { mutableStateOf(false) }
    val schranka = androidx.compose.ui.platform.LocalClipboardManager.current
    val nadpis = T("prompt_vyber")
    if (vyber) GaleriaVyberDialog(nadpis,
        zoSuboru = { Platforma.vyberSubor(nadpis, setOf("png", "jpg", "jpeg", "bmp", "gif")) { f -> obrazok = f; vysledok = ""; chyba = "" }; vyber = false },
        zavri = { vyber = false }) { f -> obrazok = f; vysledok = ""; chyba = ""; vyber = false }
    Column(Modifier.fillMaxSize()) {
        Hlavicka(T("prompt_nadpis"), T("prompt_podnadpis"), spat = spat)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Karta { Text(T("prompt_info"), color = Farby.tlmene, fontSize = 13.sp) }
            Karta {
                val nb = remember(obrazok) { obrazok?.let { Platforma.nacitajObrazok(it) } }
                Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(14.dp)).background(Farby.plocha2).clickable(enabled = !v.pracuje) { vyber = true },
                    contentAlignment = Alignment.Center) {
                    if (nb != null) Image(nb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AddPhotoAlternate, null, tint = Farby.tlmene, modifier = Modifier.size(40.dp))
                        Text(T("prompt_klikni"), color = Farby.tlmene, fontSize = 13.sp)
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Videnie.Rezim.PROMPT to "prompt_rezim_cely", Videnie.Rezim.STYL to "prompt_rezim_styl").forEach { (r, k) ->
                        FilterChip(selected = rezim == r, onClick = { rezim = r }, label = { Text(T(k)) }, enabled = !v.pracuje,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (v.pracuje) {
                    Text(v.cinnost, color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Casovac(true, v.zaciatok, 0)
                    if (v.priebeh >= 0f) LinearProgressIndicator(progress = { v.priebeh }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    else LinearProgressIndicator(modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                } else {
                    Casovac(false, 0, v.posledneTrvanie)
                    GradientTlacidlo(T("prompt_napis"), Icons.Default.Description, Modifier.fillMaxWidth(), enabled = obrazok != null) {
                        chyba = ""; vysledok = ""
                        v.opis(obrazok!!, rezim) { t, e -> if (t != null) vysledok = t else chyba = e }
                    }
                    if (!v.mamModel()) Text(T("prompt_prvy_raz"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                if (chyba.isNotBlank()) Text("⚠ $chyba", color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (vysledok.isNotBlank()) Karta {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(T("prompt_vysledok"), color = Farby.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { schranka.setText(androidx.compose.ui.text.AnnotatedString(vysledok)); Platforma.oznam(T("prompt_skopirovane")) }) {
                        Text(T("cas_kopirovat"), color = Farby.ruzova)
                    }
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(vysledok, color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** Dabing: video z YouTube / súboru → slovenský alebo český dabing AI hlasom + titulky */
@Composable
private fun DabingObrazovka(spat: () -> Unit) {
    val d = Dabing
    var odkaz by remember { mutableStateOf("") }
    var subor by remember { mutableStateOf<File?>(null) }
    var jazyk by remember { mutableStateOf("cs") }
    var hlas by remember { mutableStateOf("jirka") }
    var pozadie by remember { mutableStateOf(true) }
    var vysledok by remember { mutableStateOf<File?>(null) }
    var chyba by remember { mutableStateOf("") }
    val hlasy = buildList {
        add("jirka" to "Jirka (CZ, muž) – Piper"); add("lili" to "Lili (SK, žena) – Piper")
        if (jazyk == "sk") listOf("st0", "st1", "st2", "st3", "st4").forEach { add(it to Hlas.meno(it) + " (SK) – Supertonic") }
    }
    Column(Modifier.fillMaxSize()) {
        Hlavicka(T("dab_nadpis"), T("dab_podnadpis"), spat = spat)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Karta { Text(T("dab_info"), color = Farby.tlmene, fontSize = 13.sp) }
            Karta {
                Text(T("dab_zdroj"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = odkaz, onValueChange = { odkaz = it; if (it.isNotBlank()) subor = null }, singleLine = true, enabled = !d.pracuje,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().nadKlavesnicou(),
                    placeholder = { Text("https://www.youtube.com/watch?v=…", color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(subor?.name ?: T("dab_alebo_subor"), color = if (subor != null) Farby.text else Farby.tlmene, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val nadpis = T("dab_vyber_video")
                    TextButton(onClick = { Platforma.vyberSubor(nadpis, setOf("mp4", "mkv", "webm", "mov", "avi")) { subor = it; odkaz = "" } }, enabled = !d.pracuje) {
                        Text(T("dab_subor"), color = Farby.ruzova)
                    }
                }
                Text(T("dab_jazyk"), color = Farby.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf("cs" to "Čeština", "sk" to "Slovenčina").forEach { (k, m) ->
                        FilterChip(selected = jazyk == k, onClick = { jazyk = k; hlas = if (k == "cs") "jirka" else "lili" }, label = { Text(m) }, enabled = !d.pracuje,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
                Text(T("dab_hlas_vyber"), color = Farby.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                hlasy.forEach { (id, meno) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !d.pracuje) { hlas = id }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = hlas == id, onClick = { hlas = id }, enabled = !d.pracuje, colors = RadioButtonDefaults.colors(selectedColor = Farby.ruzova))
                        Text(meno, color = Farby.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (!Hlas.mamDabingHlas(id)) Text(T("dab_stiahne_sa"), color = Farby.tlmene, fontSize = 11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !d.pracuje) { pozadie = !pozadie }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T("dab_pozadie"), color = Farby.text, fontSize = 14.sp)
                        Text(T("dab_pozadie_info"), color = Farby.tlmene, fontSize = 12.sp)
                    }
                    Switch(checked = pozadie, onCheckedChange = { pozadie = it }, enabled = !d.pracuje, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                }
                Spacer(Modifier.height(8.dp))
                if (d.pracuje) {
                    Text(d.cinnost, color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Casovac(true, d.zaciatok, 0)
                    LinearProgressIndicator(progress = { d.priebeh }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    TextButton(onClick = { d.zrus() }) { Text(T("zrusit"), color = Farby.tlmene) }
                } else {
                    Casovac(false, 0, d.posledneTrvanie)
                    GradientTlacidlo(T("dab_spustit"), Icons.Default.RecordVoiceOver, Modifier.fillMaxWidth(), enabled = odkaz.isNotBlank() || subor != null) {
                        chyba = ""; vysledok = null
                        d.spusti(subor?.path ?: odkaz.trim(), jazyk, hlas, pozadie) { f, e -> if (f != null) vysledok = f else chyba = e }
                    }
                }
                if (chyba.isNotBlank()) Text("⚠ $chyba", color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                vysledok?.let { f ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ " + f.name, color = Farby.zelena, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Prehravac.otvor(f) }) { Text(T("klip_prehrat"), color = Farby.ruzova) }
                        TextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(f.parentFile) } }) { Text(T("dab_priecinok"), color = Farby.ruzova) }
                    }
                }
            }
        }
    }
}

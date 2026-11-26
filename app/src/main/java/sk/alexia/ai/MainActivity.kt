package sk.alexia.ai

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ChatJadro.init(this)
        ObrazkyJadro.init(this)
        Aktualizacie.skontroluj(this)
        setContent { AlexiaTema { Appka() } }
    }
}

private enum class Obrazovka { CHAT, OBRAZKY, VIDEA_PC, VYLEPSIT, MODELY, NASTAVENIA }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Appka() {
    var obrazovka by remember { mutableStateOf(Obrazovka.CHAT) }
    LaunchedEffect(Unit) { PcSpojenie.skontroluj() }
    BackHandler(obrazovka != Obrazovka.CHAT) { obrazovka = Obrazovka.CHAT }
    // v pamäti je vždy len jeden model: obrázky, vylepšenie alebo chat
    LaunchedEffect(obrazovka) {
        when (obrazovka) {
            Obrazovka.OBRAZKY -> { VylepsenieJadro.uvolniPamat(); ObrazkyJadro.aktivnyModel?.let { if (File(it).exists()) ObrazkyJadro.nacitaj(it) } }
            Obrazovka.CHAT -> { ObrazkyJadro.uvolniPamat(); VylepsenieJadro.uvolniPamat(); ChatJadro.obnov() }
            Obrazovka.VYLEPSIT -> ObrazkyJadro.uvolniPamat()
            else -> {}
        }
    }
    val n = ChatJadro.nastavenia
    Box(
        Modifier
            .fillMaxSize()
            .background(Farby.pozadie)
            .background(Brush.radialGradient(listOf(Farby.fialova.copy(alpha = 0.16f), Color.Transparent), radius = 900f)),
    ) {
        // pozadie chatu (obrázok, ktorý si vytvoril)
        if (obrazovka == Obrazovka.CHAT && n.pozadieChatu != null) {
            val bmp = remember(n.pozadieChatu, n.pozadieVerzia) { n.pozadieChatu?.let { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() } }
            if (bmp != null) {
                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = n.stmavenie / 100f)))
            }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Aktualizacia()
            Box(Modifier.weight(1f)) {
                when (obrazovka) {
                    Obrazovka.CHAT -> ChatObrazovka(naModely = { obrazovka = Obrazovka.MODELY })
                    Obrazovka.MODELY -> ModelyObrazovka { obrazovka = Obrazovka.CHAT }
                    Obrazovka.NASTAVENIA -> NastaveniaObrazovka { obrazovka = Obrazovka.CHAT }
                    Obrazovka.OBRAZKY -> ObrazkyObrazovka { obrazovka = Obrazovka.CHAT }
                    Obrazovka.VYLEPSIT -> VylepsenieObrazovka { obrazovka = Obrazovka.CHAT }
                    Obrazovka.VIDEA_PC -> VideaPcObrazovka(naNastavenia = { obrazovka = Obrazovka.NASTAVENIA }) { obrazovka = Obrazovka.CHAT }
                }
            }
            // spodné záložky (pri písaní sa skryjú, aby nezaberali miesto nad klávesnicou)
            if (!WindowInsets.isImeVisible) Zalozky(obrazovka) { obrazovka = it }
        }
    }
}

@Composable
private fun Zalozky(aktivna: Obrazovka, zvol: (Obrazovka) -> Unit) {
    val polozky = listOf(
        Triple(Obrazovka.CHAT, Icons.Default.Forum, "chat"),
        Triple(Obrazovka.OBRAZKY, Icons.Default.Image, "obrazky"),
        Triple(Obrazovka.VIDEA_PC, Icons.Default.Movie, "pc_zalozka"),
        Triple(Obrazovka.VYLEPSIT, Icons.Default.AutoFixHigh, "vylepsit_kratko"),
        Triple(Obrazovka.MODELY, Icons.Default.Memory, "modely"),
        Triple(Obrazovka.NASTAVENIA, Icons.Default.Tune, "nastavenia"),
    )
    NavigationBar(containerColor = Farby.plocha.copy(alpha = 0.96f), tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
        polozky.forEach { (o, ikona, kluc) ->
            NavigationBarItem(
                selected = o == aktivna, onClick = { zvol(o) },
                icon = {
                    // funkcie z PC sú zamknuté, kým sa mobil nepripojí k PC
                    if (o == Obrazovka.VIDEA_PC && !PcSpojenie.pripojene) BadgedBox(badge = { Icon(Icons.Default.Lock, null, tint = Farby.tlmene, modifier = Modifier.size(12.dp)) }) { Icon(ikona, null) }
                    else Icon(ikona, null)
                }, label = { Text(T(kluc), fontSize = 11.sp, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White, selectedTextColor = Farby.ruzova, indicatorColor = Farby.fialova,
                    unselectedIconColor = Farby.tlmene, unselectedTextColor = Farby.tlmene,
                ),
            )
        }
    }
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
            IconButton(onClick = spat) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.spat), tint = Farby.text) }
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
private fun ChatObrazovka(naModely: () -> Unit) {
    val j = ChatJadro
    val o = ObrazkyJadro
    val zoznam = rememberLazyListState()
    var vstup by remember { mutableStateOf("") }
    var rezimObrazka by remember { mutableStateOf(false) }
    var historia by remember { mutableStateOf(false) }
    if (historia) HistoriaDialog { historia = false }
    var citac by remember { mutableStateOf(false) }
    if (citac && Dokumenty.aktualny != null) CitacDialog { citac = false }
    val ctx = LocalContext.current
    // kniha / dokument: skopíruje sa do appky a načíta
    val vyberDok = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val meno = runCatching { ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: "dokument.pdf"
        Thread {
            val f = File(File(ctx.filesDir, "dokumenty").apply { mkdirs() }, meno.replace(Regex("[/\\\\]"), "_"))
            val ok = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }.isSuccess
            Platforma.naHlavnom { if (ok) Dokumenty.otvor(f) else Platforma.oznam("⚠ $meno") }
        }.start()
    }
    val pridajText: (String) -> Unit = { t -> vstup = if (vstup.isBlank()) t else vstup.trimEnd() + " " + t }
    val povolenieMik = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) Rec.prepni(pridajText) else Platforma.oznam(T("rec_mikrofon", "")) }
    val posledny = j.spravy.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(j.spravy.size, posledny) { if (j.spravy.isNotEmpty()) zoznam.scrollToItem(j.spravy.lastIndex, Int.MAX_VALUE / 2) }

    Column(Modifier.fillMaxSize()) {
        val pod = when (j.stav) {
            StavModelu.PRIPRAVENY -> if (j.generuje && j.tokenovZaSekundu > 0) "${j.nazovModelu()} · ${"%.1f".format(j.tokenovZaSekundu)} tok/s" else j.nazovModelu()
            StavModelu.NACITAVA -> stringResource(R.string.nacitavam_model)
            StavModelu.CHYBA -> stringResource(R.string.chyba_modelu)
            StavModelu.ZIADNY -> stringResource(R.string.ziadny_model_kratko)
        }
        Hlavicka(stringResource(R.string.app_name), pod) {
            IconButton(onClick = { historia = true }) { Icon(Icons.Default.History, T("hist_nadpis"), tint = Farby.text) }
            IconButton(onClick = { j.novyChat() }, enabled = j.spravy.isNotEmpty()) { Icon(Icons.Default.AddComment, stringResource(R.string.novy_chat), tint = Farby.text) }
        }

        Box(Modifier.weight(1f)) {
            if (j.spravy.isEmpty()) {
                Uvod(naModely) { vstup = it }
            } else {
                LazyColumn(state = zoznam, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(j.spravy) { i, s ->
                        if (s.rola == "user") BublinaPouzivatel(s.text)
                        else if (s.jeObrazkova) BublinaObrazok(s)
                        else BublinaAI(s.text, pise = j.generuje && i == j.spravy.lastIndex, posledna = i == j.spravy.lastIndex && !j.generuje)
                    }
                }
            }
        }

        // vložený dokument (kniha, PDF…)
        val dok = Dokumenty.aktualny
        if (dok != null || Dokumenty.nacitava) {
            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Farby.plocha2).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MenuBook, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                if (dok == null) Text(T("dok_nacitavam"), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                else {
                    Column(Modifier.weight(1f)) {
                        Text(dok.nazov, color = Farby.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(T("dok_info"), color = Farby.tlmene, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { Citac.otvor(); citac = true }) { Icon(Icons.Default.VolumeUp, T("dok_citat"), tint = Farby.ruzova) }
                    IconButton(onClick = { Dokumenty.zavri() }) { Icon(Icons.Default.Close, T("dok_zavriet"), tint = Farby.tlmene, modifier = Modifier.size(18.dp)) }
                }
            }
        }
        // vstup
        Row(
            Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Farby.plocha)
                .border(1.dp, Farby.okraj, RoundedCornerShape(28.dp)).padding(start = 2.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { rezimObrazka = !rezimObrazka }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Brush, T("chat_obr_rezim"), tint = if (rezimObrazka) Farby.ruzova else Farby.tlmene)
            }
            IconButton(onClick = { vyberDok.launch(arrayOf("application/pdf", "application/epub+zip", "text/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.AttachFile, T("dok_pridat"), tint = if (Dokumenty.aktualny != null) Farby.ruzova else Farby.tlmene)
            }
            TextField(
                value = vstup, onValueChange = { vstup = it },
                placeholder = { Text(if (Rec.nahrava) T("rec_pocuvam") else if (Rec.spracuva) T("rec_prepisujem") else if (rezimObrazka) T("chat_obr_popis") else stringResource(if (j.stav == StavModelu.PRIPRAVENY) R.string.napis_spravu else R.string.ziadny_model_kratko), color = Farby.tlmene, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.weight(1f), maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    cursorColor = Farby.ruzova, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text,
                ),
            )
            Box(contentAlignment = Alignment.Center) {
                val rp = Rec.priebeh
                if (rp != null) CircularProgressIndicator(progress = { rp }, modifier = Modifier.size(34.dp), color = Farby.ruzova, strokeWidth = 3.dp)
                else if (Rec.spracuva) CircularProgressIndicator(modifier = Modifier.size(34.dp), color = Farby.ruzova, strokeWidth = 3.dp)
                IconButton(onClick = {
                    if (!Rec.mam()) { if (Rec.priebeh == null) { Platforma.oznam(T("rec_stahujem", Rec.MB)); Rec.stiahni() } }
                    else if (Rec.nahrava) Rec.prepni(pridajText)
                    else povolenieMik.launch(android.Manifest.permission.RECORD_AUDIO)
                }, modifier = Modifier.size(40.dp)) {
                    Icon(if (Rec.nahrava) Icons.Default.StopCircle else Icons.Default.Mic, T("rec_tlacidlo"),
                        tint = if (Rec.nahrava) Farby.cervena.copy(alpha = 0.6f + 0.4f * Rec.hlasitost) else Farby.tlmene)
                }
            }
            val jeObr = rezimObrazka || popisObrazka(vstup) != null
            val moze = vstup.isNotBlank() && (if (jeObr) !o.generuje else j.stav == StavModelu.PRIPRAVENY && !j.generuje)
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (j.generuje) Brush.linearGradient(listOf(Farby.cervena, Farby.cervena)) else if (moze) Farby.prechod else Brush.linearGradient(listOf(Farby.plocha2, Farby.plocha2)))
                    .clickable(enabled = j.generuje || moze) {
                        if (j.generuje) j.zastav() else { j.posli(vstup, rezimObrazka); vstup = "" }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (j.generuje) Icon(Icons.Default.Stop, stringResource(R.string.zastavit), tint = Color.White)
                else Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.poslat), tint = if (moze) Color.White else Farby.tlmene, modifier = Modifier.size(20.dp))
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
        GradientText(stringResource(R.string.ahoj_nadpis), 26)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ahoj_text), color = Farby.tlmene, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        when (j.stav) {
            StavModelu.PRIPRAVENY -> {
                listOf(R.string.navrh_1, R.string.navrh_2, R.string.navrh_3).forEach { r ->
                    val t = stringResource(r)
                    Box(
                        Modifier.padding(vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Farby.prechodJemny)
                            .border(1.dp, Farby.okraj, RoundedCornerShape(16.dp)).clickable { navrh(t) }.padding(14.dp),
                    ) { Text(t, color = Farby.text) }
                }
            }
            StavModelu.NACITAVA -> {
                CircularProgressIndicator(color = Farby.ruzova, strokeWidth = 3.dp)
                Spacer(Modifier.height(10.dp)); Text(stringResource(R.string.nacitavam_model), color = Farby.tlmene)
            }
            else -> Karta {
                Text(stringResource(if (j.stav == StavModelu.CHYBA) R.string.chyba_modelu else R.string.ziadny_model), color = Farby.text, fontWeight = FontWeight.SemiBold)
                if (j.stav == StavModelu.CHYBA && j.chyba.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(j.chyba, color = Farby.cervena, fontSize = 13.sp) }
                Spacer(Modifier.height(12.dp))
                GradientTlacidlo(stringResource(R.string.vybrat_model), Icons.Default.Memory, onClick = naModely)
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
    val context = LocalContext.current
    val skopirovane = stringResource(R.string.skopirovane)
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
                    Text(stringResource(R.string.premysla), color = Farby.tlmene, fontSize = 12.sp)
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
                    IconButton(onClick = { schranka.setText(AnnotatedString(odpoved)); Toast.makeText(context, skopirovane, Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.kopirovat), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { if (Hlas.hovori == text) Hlas.zastav() else Hlas.hovor(odpoved) }, modifier = Modifier.size(32.dp)) {
                        Icon(if (Hlas.hovori == text) Icons.Default.StopCircle else Icons.Default.VolumeUp, T("hlas_precitat"), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                    }
                    if (posledna) {
                        IconButton(onClick = { ChatJadro.znova() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.znova), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                        }
                    }
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
        if (riadok < 0) { z += KusTextu("", t.substring(a + 3).trim()); break }
        val jazyk = t.substring(a + 3, riadok).trim()
        val b = t.indexOf("```", riadok + 1)
        if (b < 0) { z += KusTextu(t.substring(riadok + 1), jazyk); break }
        z += KusTextu(t.substring(riadok + 1, b).trimEnd('\n'), jazyk)
        i = b + 3
    }
    return z
}

/** tmavé okno s kódom ako vo VS Code – s názvom jazyka a tlačidlom Kopírovať */
@Composable
private fun BlokKodu(kod: String, jazyk: String) {
    val schranka = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1E1E)).border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF2D2D2D)).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(jazyk.ifBlank { "kód" }, color = Color(0xFF9CDCFE), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
            TextButton(onClick = { schranka.setText(AnnotatedString(kod)); Platforma.oznam(T("skopirovane")) }) {
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
private fun BublinaObrazok(s: Sprava) {
    val o = ObrazkyJadro
    val c = LocalContext.current
    val cesta = s.obrazok
    val obr = remember(cesta) { cesta?.let { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Farby.prechod), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Brush, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            when {
                obr != null -> {
                    Image(obr, null, Modifier.fillMaxWidth().aspectRatio(obr.width.toFloat() / obr.height).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
                    Row(Modifier.padding(top = 2.dp)) {
                        if (android.os.Build.VERSION.SDK_INT >= 29) IconButton(onClick = { Platforma.oznam(c.getString(if (o.ulozDoGalerie(File(cesta!!))) R.string.obr_ulozene else R.string.obr_neulozene)) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.SaveAlt, T("obr_ulozit"), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { o.nastavPozadieChatu(File(cesta!!)) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Wallpaper, T("poz_chat_tlacidlo"), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                s.text.isNotEmpty() -> Text(s.text, color = Farby.cervena, fontSize = 14.sp)
                cesta != null -> Text(T("chat_obr_chyba"), color = Farby.tlmene, fontSize = 14.sp)
                else -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Farby.plocha).padding(14.dp)) {
                    val nacitava = o.stav == StavModelu.NACITAVA
                    Text(T(if (nacitava) "chat_obr_nacitavam" else "chat_obr_kreslim", (o.priebeh * 100).roundToInt()), color = Farby.text, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    if (nacitava || o.priebeh <= 0f) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    else LinearProgressIndicator(progress = { o.priebeh }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    if (!ChatJadro.nastavenia.vzdialene) TextButton(onClick = { o.zrus() }) { Text(stringResource(R.string.zrusit), color = Farby.tlmene) }
                }
            }
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
    val c = LocalContext.current
    val j = ChatJadro
    var subory by remember { mutableStateOf(Modely.zoznam(c)) }
    var priebeh by remember { mutableStateOf<Float?>(null) }
    var cinnost by remember { mutableStateOf("") }
    var zrusit by remember { mutableStateOf(false) }
    var odkaz by remember { mutableStateOf("") }
    var naZmazanie by remember { mutableStateOf<File?>(null) }
    val chybaPrefix = stringResource(R.string.chyba)
    val textStahujem = stringResource(R.string.stahujem)
    val textKopirujem = stringResource(R.string.kopirujem)

    fun hotovo(f: File?, e: Throwable?) {
        priebeh = null; subory = Modely.zoznam(c)
        if (f != null) j.nacitajModel(f.path)
        if (e != null && e !is InterruptedException) Toast.makeText(c, "$chybaPrefix: ${e.message}", Toast.LENGTH_LONG).show()
    }

    fun stiahni(url: String) {
        if (priebeh != null) return
        priebeh = 0f; cinnost = textStahujem; zrusit = false
        thread {
            val r = runCatching { Modely.stiahni(c, url, { p -> priebeh = p }, { zrusit }) }
            (c as ComponentActivity).runOnUiThread { hotovo(r.getOrNull(), r.exceptionOrNull()) }
        }
    }

    val vyber = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || priebeh != null) return@rememberLauncherForActivityResult
        priebeh = 0f; cinnost = textKopirujem; zrusit = false
        thread {
            val r = runCatching { Modely.importuj(c, uri, priebeh = { p -> priebeh = p }) }
            (c as ComponentActivity).runOnUiThread { hotovo(r.getOrNull(), r.exceptionOrNull()) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Hlavicka(stringResource(R.string.modely), spat = spat)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            priebeh?.let { p ->
                item {
                    Karta {
                        Text("$cinnost ${(p * 100).roundToInt()} %", color = Farby.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                        TextButton(onClick = { zrusit = true }) { Text(stringResource(R.string.zrusit), color = Farby.tlmene) }
                    }
                }
            }

            item { SekciaHlavicka(T("mod_sek_moje", subory.size), Icons.Default.Memory, kluc = "mod_moje") }
            if (jeOtvorena("mod_moje")) {
            if (subory.isEmpty()) item { Text(stringResource(R.string.modely_ziadne), color = Farby.tlmene) }
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
                        val info = if (aktivny && j.stav == StavModelu.NACITAVA) stringResource(R.string.nacitavam_model)
                            else if (aktivny) "${stringResource(R.string.aktivny)} · ${Modely.velkost(f)}" else Modely.velkost(f)
                        Text(info, color = Farby.tlmene, fontSize = 12.sp)
                    }
                    IconButton(onClick = { naZmazanie = f }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.zmazat), tint = Farby.tlmene) }
                }
            }

            item {
                OutlinedButton(onClick = { vyber.launch(arrayOf("*/*")) }, enabled = priebeh == null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                    Icon(Icons.Default.FolderOpen, null, tint = Farby.text); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.zo_suboru), color = Farby.text)
                }
            }

            }
            item { SekciaHlavicka(stringResource(R.string.modely_odporucane), Icons.Default.Download, kluc = "mod_odpor") }
            if (jeOtvorena("mod_odpor")) items(Modely.odporucane) { m ->
                val uz = subory.any { it.name.equals(Uri.parse(m.url).lastPathSegment, ignoreCase = true) }
                Karta {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.nazov, color = Farby.text, fontWeight = FontWeight.SemiBold)
                            Text("${m.popis} · ~${m.mb} MB", color = Farby.tlmene, fontSize = 12.sp)
                        }
                        if (uz) Icon(Icons.Default.DownloadDone, null, tint = Farby.zelena)
                        else GradientTlacidlo(stringResource(R.string.stiahnut), Icons.Default.Download, enabled = priebeh == null) { stiahni(m.url) }
                    }
                }
            }

            item {
                Sekcia(stringResource(R.string.modely_vlastny), ikona = Icons.Default.Link) {
                    OutlinedTextField(
                        value = odkaz, onValueChange = { odkaz = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://huggingface.co/…/model.gguf", color = Farby.tlmene, fontSize = 13.sp) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                    )
                    Spacer(Modifier.height(10.dp))
                    GradientTlacidlo(stringResource(R.string.stiahnut), Icons.Default.Download, enabled = priebeh == null && (odkaz.trim().startsWith("http") || odkaz.contains("huggingface.co") || odkaz.contains("hf.co"))) { stiahni(odkaz) }
                }
            }
            item { Text(stringResource(R.string.modely_info), color = Farby.tlmene, fontSize = 12.sp) }
        }
    }

    naZmazanie?.let { f ->
        AlertDialog(
            onDismissRequest = { naZmazanie = null },
            containerColor = Farby.plocha,
            title = { Text(stringResource(R.string.zmazat_model_otazka, f.nameWithoutExtension), color = Farby.text) },
            confirmButton = { TextButton(onClick = { j.zmazModel(f); subory = subory - f; naZmazanie = null }) { Text(stringResource(R.string.zmazat), color = Farby.cervena) } },
            dismissButton = { TextButton(onClick = { naZmazanie = null }) { Text(stringResource(R.string.zrusit), color = Farby.tlmene) } },
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
        Hlavicka(stringResource(R.string.nastavenia), spat = spat)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Sekcia(T("siet_karta_m")) { PrepojenieKarta() }
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hlas.HLASY.forEach { (id, meno) ->
                        FilterChip(selected = n.hlas == id, onClick = { Hlas.zastav(); n.hlas = id; n.uloz() }, label = { Text(meno.ifEmpty { T("hlas_system") }) },
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
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                if (n.hlas == "system") Text(T("hlas_android"), color = Farby.tlmene, fontSize = 12.sp)
                else {
                    Text(if (n.hlas == "lili") T("hlas_lili", Hlas.LILI_MB) else T("hlas_st_info", Hlas.ST_MB).substringBefore(" Lili"), color = Farby.tlmene, fontSize = 12.sp)
                    val p = Hlas.priebeh
                    when {
                        p != null -> LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                        !Hlas.mam(n.hlas) -> GradientTlacidlo(T("hlas_stiahnut"), Icons.Default.Download, Modifier.padding(top = 6.dp)) { Hlas.stiahni() }
                        else -> Text(T("hlas_mam"), color = Farby.zelena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (Hlas.chyba.isNotBlank()) Text(Hlas.chyba, color = Farby.cervena, fontSize = 13.sp)
                }
                if (Hlas.mam(n.hlas)) {
                    val vzorka = T("hlas_vzorka", Hlas.meno(n.hlas))
                    GradientTlacidlo(if (Hlas.hovori == vzorka) T("hlas_stop") else T("hlas_skusit"), if (Hlas.hovori == vzorka) Icons.Default.StopCircle else Icons.Default.VolumeUp, Modifier.padding(top = 6.dp)) {
                        if (Hlas.hovori == vzorka) Hlas.zastav() else Hlas.hovor(vzorka)
                    }
                }
                Text(T("hlas_rychlost", "%.2f".format(n.hlasRychlost)), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                Slider(value = n.hlasRychlost, onValueChange = { n.hlasRychlost = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { n.uloz() }, valueRange = 0.5f..2f,
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                Text(T("hlas_vyska", "%.2f".format(n.hlasVyska)), color = Farby.text, fontSize = 14.sp)
                Slider(value = n.hlasVyska, onValueChange = { n.hlasVyska = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { n.uloz() }, valueRange = 0.7f..1.4f,
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                if (n.hlas != "system") {
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
                Text(T("nahovor_info_m", Hlas.meno(n.hlas)), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                var nText by remember { mutableStateOf("") }
                var nVysledok by remember { mutableStateOf("") }
                var nChyba by remember { mutableStateOf("") }
                OutlinedTextField(value = nText, onValueChange = { nText = it; nVysledok = ""; nChyba = "" }, minLines = 3, maxLines = 8,
                    placeholder = { Text(T("nahovor_hint"), color = Farby.tlmene) }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
                val np = Hlas.nahrava
                if (np != null) {
                    Text(T("nahovor_robim", (np * 100).roundToInt()), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    LinearProgressIndicator(progress = { np }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                } else GradientTlacidlo(T("nahovor_wav"), Icons.Default.SaveAlt, Modifier.padding(top = 8.dp).fillMaxWidth(), enabled = nText.isNotBlank()) {
                    Hlas.nahovorDoSuboru(nText) { f, e -> nVysledok = f ?: ""; nChyba = if (f == null) e else "" }
                }
                if (nVysledok.isNotBlank()) Text(T("nahovor_hotovo_m", nVysledok), color = Farby.zelena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                if (nChyba.isNotBlank()) Text(nChyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Sekcia(T("akt_karta")) {
                val c = LocalContext.current
                Spacer(Modifier.height(8.dp))
                if (Aktualizacie.kontroluje) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                else GradientTlacidlo(T("akt_skontrolovat"), Icons.Default.SystemUpdate, Modifier.fillMaxWidth()) { Aktualizacie.skontroluj(c, rucne = true) }
                Row(Modifier.fillMaxWidth().clickable { n.autoAktualizacie = !n.autoAktualizacie; n.uloz() }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(T("akt_auto"), color = Farby.text, modifier = Modifier.weight(1f))
                    Switch(checked = n.autoAktualizacie, onCheckedChange = { n.autoAktualizacie = it; n.uloz() }, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                }
            }
            Sekcia(T("poz_chat_karta")) {
                val bmp = remember(n.pozadieChatu, n.pozadieVerzia) { n.pozadieChatu?.let { android.graphics.BitmapFactory.decodeFile(it, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() } }
                if (bmp == null) Text(T("poz_chat_ziadne"), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                else {
                    Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp))) {
                        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = n.stmavenie / 100f)))
                        Text(stringResource(R.string.ahoj_nadpis), color = Farby.text, modifier = Modifier.align(Alignment.Center))
                    }
                    Text(T("poz_chat_stmavenie", n.stmavenie), color = Farby.text, fontSize = 14.sp)
                    Slider(value = n.stmavenie.toFloat(), onValueChange = { n.stmavenie = it.roundToInt() }, onValueChangeFinished = { n.uloz() }, valueRange = 0f..90f,
                        colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                    OutlinedButton(onClick = { ObrazkyJadro.zrusPozadieChatu() }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Farby.tlmene); Spacer(Modifier.width(6.dp)); Text(T("poz_chat_zrusit"), color = Farby.tlmene)
                    }
                }
            }
            Sekcia(stringResource(R.string.nast_system)) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = n.systemovyPrompt, onValueChange = { n.systemovyPrompt = it; n.uloz() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    placeholder = { Text(stringResource(R.string.nast_system_hint), color = Farby.tlmene, fontSize = 13.sp) },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                )
            }
            Karta {
                Text(stringResource(R.string.nast_teplota, n.teplota), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Slider(value = n.teplota, onValueChange = { n.teplota = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { uloz() }, valueRange = 0f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                Text(stringResource(R.string.nast_teplota_info), color = Farby.tlmene, fontSize = 12.sp)
            }
            Karta {
                Text(stringResource(R.string.nast_max, n.maxTokenov), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Slider(value = n.maxTokenov.toFloat(), onValueChange = { n.maxTokenov = ((it / 128).roundToInt() * 128).coerceAtLeast(128) }, onValueChangeFinished = { uloz() }, valueRange = 128f..4096f,
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
            }
            Sekcia(stringResource(R.string.nast_kontext)) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048, 4096, 8192, 16384).forEach { k ->
                        FilterChip(selected = n.kontext == k, onClick = { n.kontext = k; uloz() }, label = { Text("${k / 1024}k") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
                Text(stringResource(R.string.nast_kontext_info), color = Farby.tlmene, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.nast_vlakna, n.vlakna), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Slider(value = n.vlakna.toFloat(), onValueChange = { n.vlakna = it.roundToInt() }, onValueChangeFinished = { uloz() }, valueRange = 1f..jadra.toFloat(), steps = (jadra - 2).coerceAtLeast(0),
                    colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                if (j.potrebujeZnovuNacitat()) {
                    Spacer(Modifier.height(4.dp))
                    GradientTlacidlo(stringResource(R.string.nast_znova), Icons.Default.Refresh) { n.aktivnyModel?.let { j.nacitajModel(it) } }
                }
            }
            Karta(Modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = Farby.tlmene, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.nast_info), color = Farby.tlmene, fontSize = 13.sp)
                }
                if (j.popisModelu.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(j.popisModelu, color = Farby.tlmene, fontSize = 12.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- obrázky

@Composable
private fun ObrazkyObrazovka(spat: () -> Unit) {
    val c = LocalContext.current
    val o = ObrazkyJadro
    var modely by remember { mutableStateOf(o.modely()) }
    var prompt by remember { mutableStateOf("") }
    var negativny by remember { mutableStateOf("blurry, low quality, deformed") }
    var pozadie by remember { mutableStateOf("bez") }
    var vlastnePozadie by remember { mutableStateOf("") }
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
    val chybaPrefix = stringResource(R.string.chyba)
    val ulozene = stringResource(R.string.obr_ulozene)
    val neulozene = stringResource(R.string.obr_neulozene)

    fun hotovo(f: File?, e: Throwable?) {
        priebehStahovania = null; modely = o.modely()
        if (f != null) o.nacitaj(f.path)
        if (e != null && e !is InterruptedException) Toast.makeText(c, "$chybaPrefix: ${e.message}", Toast.LENGTH_LONG).show()
    }
    fun stiahni(url: String) {
        if (priebehStahovania != null) return
        priebehStahovania = 0f; zrusit = false
        thread {
            val r = runCatching { Modely.stiahni(c, url, { p -> priebehStahovania = p }, { zrusit }, o.priecinokModelov(), true) }
            (c as ComponentActivity).runOnUiThread { hotovo(r.getOrNull(), r.exceptionOrNull()) }
        }
    }
    val vyber = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || priebehStahovania != null) return@rememberLauncherForActivityResult
        priebehStahovania = 0f; zrusit = false
        thread {
            val r = runCatching { Modely.importuj(c, uri, { p -> priebehStahovania = p }, o.priecinokModelov(), true) }
            (c as ComponentActivity).runOnUiThread { hotovo(r.getOrNull(), r.exceptionOrNull()) }
        }
    }
    // prvý model sa načíta sám
    LaunchedEffect(modely) {
        if (o.stav == StavModelu.ZIADNY && modely.isNotEmpty()) o.nacitaj((modely.find { o.jeAktivny(it) } ?: modely.first()).path)
    }

    val pod = when (o.stav) {
        StavModelu.PRIPRAVENY -> o.aktivnyModel?.let { File(it).nameWithoutExtension }
        StavModelu.NACITAVA -> stringResource(R.string.nacitavam_model)
        StavModelu.CHYBA -> stringResource(R.string.chyba_modelu)
        StavModelu.ZIADNY -> stringResource(R.string.ziadny_model_kratko)
    }
    Column(Modifier.fillMaxSize()) {
        Hlavicka(stringResource(R.string.obrazky), pod, spat = spat)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            priebehStahovania?.let { p ->
                Karta {
                    Text("${stringResource(R.string.stahujem)} ${(p * 100).roundToInt()} %", color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    TextButton(onClick = { zrusit = true }) { Text(stringResource(R.string.zrusit), color = Farby.tlmene) }
                }
            }

            if (modely.isEmpty()) {
                Karta {
                    Text(stringResource(R.string.obr_bez_modelu), color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.obr_bez_modelu_info), color = Farby.tlmene, fontSize = 13.sp)
                }
            } else {
                // výsledok
                o.posledny?.let { f ->
                    Sekcia(T("obr_sek_vytvorit"), ikona = Icons.Default.AutoAwesome) {
                        val bmp = remember(f) { android.graphics.BitmapFactory.decodeFile(f.path) }
                        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(14.dp)).clickable { detail = f }, contentScale = ContentScale.Fit)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                GradientTlacidlo(stringResource(R.string.obr_ulozit), Icons.Default.SaveAlt) {
                                    Toast.makeText(c, if (o.ulozDoGalerie(f)) ulozene else neulozene, Toast.LENGTH_SHORT).show()
                                }
                            }
                            OutlinedButton(onClick = { o.nastavPozadieChatu(f) }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                                Icon(Icons.Default.Wallpaper, T("poz_chat_tlacidlo"), tint = Farby.text)
                            }
                            OutlinedButton(onClick = { o.zmazObrazok(f) }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                                Icon(Icons.Default.DeleteOutline, null, tint = Farby.tlmene); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.zmazat), color = Farby.tlmene)
                            }
                        }
                    }
                }

                Karta {
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                        placeholder = { Text(stringResource(R.string.obr_prompt_hint), color = Farby.tlmene, fontSize = 13.sp) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                    )
                    Text(stringResource(R.string.obr_anglicky), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    // AI napíše presný anglický prompt z krátkeho nápadu (aj po slovensky)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ChatJadro.pisePrompt) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Farby.ruzova, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                            Text(T("prompt_pise"), color = Farby.text, fontSize = 13.sp)
                        } else OutlinedButton(onClick = {
                            ChatJadro.napisPrompt(prompt, false) { p, e -> if (p != null) prompt = p else Platforma.oznam("⚠ $e") }
                        }, enabled = prompt.isNotBlank(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.fialova)) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(T("prompt_ai"), color = Farby.text)
                        }
                    }
                    Text(T("prompt_ai_info"), color = Farby.tlmene, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    TvarObrazka(rozmer)
                    Text(T("poz_nadpis"), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        POZADIA.forEach { (id, _) ->
                            FilterChip(selected = pozadie == id, onClick = { pozadie = id }, label = { Text(T("poz_$id")) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                        }
                    }
                    if (pozadie == "vlastne") OutlinedTextField(
                        value = vlastnePozadie, onValueChange = { vlastnePozadie = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(T("poz_vlastne_hint"), color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                    )
                    TextButton(onClick = { viac = !viac }) {
                        Text(stringResource(R.string.obr_viac), color = Farby.ruzova)
                        Icon(if (viac) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Farby.ruzova)
                    }
                    AnimatedVisibility(viac) {
                        Column {
                            OutlinedTextField(
                                value = negativny, onValueChange = { negativny = it }, modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.obr_negativny)) }, shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.obr_rozmer), color = Farby.text, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(256, 384, 512).forEach { r ->
                                    FilterChip(selected = rozmer == r, onClick = { rozmer = r }, label = { Text("${r}×$r") },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                                }
                            }
                            Text(stringResource(R.string.obr_kroky, kroky), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                            Slider(value = kroky.toFloat(), onValueChange = { kroky = it.roundToInt() }, valueRange = 4f..40f,
                                colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                            Text(stringResource(R.string.obr_cfg, cfg), color = Farby.text, fontSize = 14.sp)
                            Slider(value = cfg, onValueChange = { cfg = (it * 2).roundToInt() / 2f }, valueRange = 1f..12f,
                                colors = SliderDefaults.colors(thumbColor = Farby.ruzova, activeTrackColor = Farby.fialova, inactiveTrackColor = Farby.plocha2))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (o.generuje && o.naPc) {
                        Text(T("obr_kresli_pc"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                        Casovac(true, o.zaciatok, 0)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    } else if (o.generuje) {
                        Text(stringResource(R.string.obr_tvorim, (o.priebeh * 100).roundToInt()), color = Farby.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { o.priebeh }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                        Casovac(true, o.zaciatok, 0)
                        TextButton(onClick = { o.zrus() }) { Text(stringResource(R.string.zrusit), color = Farby.tlmene) }
                    } else {
                        Casovac(false, 0, o.posledneTrvanie)
                        // dve tlačidlá: v mobile, alebo na PC (zamknuté, kým nie je mobil pripojený k PC)
                        LaunchedEffect(Unit) { PcSpojenie.skontroluj() }
                        val pcOk = PcSpojenie.pripojene && PcSpojenie.info?.obrazky == true
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GradientTlacidlo(T("obr_v_mobile"), Icons.Default.AutoAwesome, Modifier.weight(1f),
                                enabled = o.stav == StavModelu.PRIPRAVENY && prompt.isNotBlank()) { o.generuj(popisSPozadim(), negativny, rozmer, kroky, cfg) }
                            GradientTlacidlo(T("obr_na_pc"), if (pcOk) Icons.Default.Computer else Icons.Default.Lock, Modifier.weight(1f),
                                enabled = pcOk && prompt.isNotBlank()) { o.generujNaPc(popisSPozadim(), negativny, "stvorec") }
                        }
                        Text(when {
                            !PcSpojenie.pripojene -> T("obr_pc_zamknute")
                            PcSpojenie.info?.obrazky != true -> T("obr_pc_bez_modelu")
                            else -> T("obr_pc_info", PcSpojenie.info?.meno ?: "PC")
                        }, color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(stringResource(R.string.obr_cas), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (o.chyba.isNotBlank()) Text(o.chyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }

                PrenosStyluPcKarta()

                // galéria
                if (o.galeria.isNotEmpty()) Sekcia(T("obr_sek_galeria", o.galeria.size), ikona = Icons.Default.PhotoLibrary) {
                    o.galeria.chunked(3).forEach { riadok ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            riadok.forEach { f ->
                                val nahlad = remember(f) { android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) }
                                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Farby.plocha2).clickable { detail = f }) {
                                    if (nahlad != null) Image(nahlad.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            }
                            repeat(3 - riadok.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            // modely na obrázky
            Sekcia(T("obr_sek_modely"), ikona = Icons.Default.Memory) {
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
                    IconButton(onClick = { o.zmazModel(f); modely = modely - f }, enabled = !o.generuje) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.zmazat), tint = Farby.tlmene) }
                }
            }
            o.odporucane.forEach { m ->
                val uz = modely.any { it.name.equals(Uri.parse(m.url).lastPathSegment, ignoreCase = true) }
                if (!uz) Karta {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.nazov, color = Farby.text, fontWeight = FontWeight.SemiBold)
                            Text("${m.popis} · ~${m.mb} MB", color = Farby.tlmene, fontSize = 12.sp)
                        }
                        GradientTlacidlo(stringResource(R.string.stiahnut), Icons.Default.Download, enabled = priebehStahovania == null) { stiahni(m.url) }
                    }
                }
            }
            Karta {
                Text(stringResource(R.string.obr_vlastny), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = odkaz, onValueChange = { odkaz = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://huggingface.co/…/model.gguf", color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GradientTlacidlo(stringResource(R.string.stiahnut), Icons.Default.Download, enabled = priebehStahovania == null && (odkaz.trim().startsWith("http") || odkaz.contains("huggingface.co") || odkaz.contains("hf.co"))) { stiahni(odkaz) }
                    OutlinedButton(onClick = { vyber.launch(arrayOf("*/*")) }, enabled = priebehStahovania == null, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.FolderOpen, null, tint = Farby.text); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.zo_suboru_kratko), color = Farby.text)
                    }
                }
            }
            Text(stringResource(R.string.obr_info), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    detail?.let { f ->
        val bmp = remember(f) { android.graphics.BitmapFactory.decodeFile(f.path) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { detail = null }) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Farby.plocha).verticalScroll(rememberScrollState()).padding(12.dp)) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.heightIn(max = 480.dp).aspectRatio(bmp.width.toFloat() / bmp.height, matchHeightConstraintsFirst = true).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        GradientTlacidlo(stringResource(R.string.obr_ulozit), Icons.Default.SaveAlt) {
                            Toast.makeText(c, if (o.ulozDoGalerie(f)) ulozene else neulozene, Toast.LENGTH_SHORT).show()
                        }
                    }
                    OutlinedButton(onClick = { o.nastavPozadieChatu(f) }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.Wallpaper, T("poz_chat_tlacidlo"), tint = Farby.text)
                    }
                    OutlinedButton(onClick = { o.zmazObrazok(f); detail = null }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Farby.tlmene)
                    }
                }
                InfoObrazka(f)
            }
        }
    }
}

@Composable
private fun Aktualizacia() {
    val c = LocalContext.current
    Aktualizacie.stiahnuta?.let { sv ->
        AlertDialog(
            onDismissRequest = { Aktualizacie.stiahnuta = null }, containerColor = Farby.plocha,
            icon = { Icon(Icons.Default.SystemUpdate, null, tint = Farby.zelena) },
            title = { Text(T("akt_pripravena", sv), color = Farby.text) },
            text = { Text(T("akt_android_text"), color = Farby.tlmene) },
            confirmButton = { TextButton(onClick = {
                runCatching { c.startActivity(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                Aktualizacie.stiahnuta = null
            }) { Text(T("akt_android_otvorit"), color = Farby.ruzova) } },
            dismissButton = { TextButton(onClick = { Aktualizacie.stiahnuta = null }) { Text(stringResource(R.string.akt_neskor), color = Farby.tlmene) } },
        )
        return
    }
    val v = Aktualizacie.novaVerzia ?: return
    val p = Aktualizacie.priebeh
    AlertDialog(
        onDismissRequest = { if (p == null) Aktualizacie.novaVerzia = null },
        containerColor = Farby.plocha,
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = Farby.ruzova) },
        title = { Text(stringResource(R.string.akt_nadpis, v), color = Farby.text) },
        text = {
            Column {
                Text(T("akt_android_priamo"), color = Farby.tlmene)
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                if (Aktualizacie.chyba.isNotBlank()) Text(Aktualizacie.chyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { Aktualizacie.stiahni(c) }, enabled = p == null) { Text(T("akt_aktualizovat_teraz"), color = Farby.ruzova) } },
        dismissButton = { if (p == null) TextButton(onClick = { Aktualizacie.preskoc(c) }) { Text(stringResource(R.string.akt_neskor), color = Farby.tlmene) } },
    )
}

/** pozadia pre tvorbu obrázkov: id → anglický popis */
private val POZADIA = listOf(
    "bez" to "", "nahodne" to "",
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

// ---------------------------------------------------------------- prepojenie: model z Legion Go S / PC

@Composable
private fun PrepojenieKarta() {
    val n = ChatJadro.nastavenia
    val j = ChatJadro
    var najdene by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var hlada by remember { mutableStateOf(false) }
    val farbyPola = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(T("siet_pouzit"), color = Farby.text, fontWeight = FontWeight.SemiBold)
            Text(T("siet_pouzit_info"), color = Farby.tlmene, fontSize = 12.sp)
        }
        Switch(checked = n.vzdialene, onCheckedChange = { j.nastavVzdialene(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
    }
    if (n.vzdialene) {
        Text(T("siet_mobil_info"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = n.vzdialenaAdresa, onValueChange = { n.vzdialenaAdresa = it.trim(); n.uloz() }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text(T("siet_adresa_pole")) },
            placeholder = { Text("192.168.0.10:${Klient.PORT}", color = Farby.tlmene) }, shape = RoundedCornerShape(14.dp), colors = farbyPola)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = n.vzdialenyPin, onValueChange = { n.vzdialenyPin = it.filter { c -> c.isDigit() }; n.uloz() }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text(T("siet_pin")) }, shape = RoundedCornerShape(14.dp), colors = farbyPola,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
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
    Text(T("siet_internet"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

// ---------------------------------------------------------------- vylepšenie fotky

@Composable
private fun VylepsenieObrazovka(spat: () -> Unit) {
    val v = VylepsenieJadro
    val c = LocalContext.current
    var poVylepseni by remember { mutableStateOf(true) }
    val vyber = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> if (uri != null) v.nastavVstup(uri) }
    fun vybrat() = vyber.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val vstup = v.vstup; val vysledok = v.vysledok
    val obrVstup = remember(vstup) { vstup?.let { nahlad(it) } }
    val obrVysledok = remember(vysledok) { vysledok?.let { nahlad(it) } }

    Column(Modifier.fillMaxSize()) {
        Hlavicka(T("vylepsit"), spat = spat)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Sekcia(T("vyl_sek_fotka"), ikona = Icons.Default.AddPhotoAlternate) {
                if (obrVstup == null) {
                    if (vstup != null) { Text(T("vyl_neotvori", ""), color = Farby.cervena, fontSize = 13.sp); Spacer(Modifier.height(6.dp)) }
                    Text(T("vyl_prazdne"), color = Farby.tlmene)
                    Spacer(Modifier.height(10.dp))
                    GradientTlacidlo(T("vyl_vybrat"), Icons.Default.AddPhotoAlternate) { vybrat() }
                } else {
                    val ukaz = if (obrVysledok != null && poVylepseni) obrVysledok else obrVstup
                    Image(ukaz, null, Modifier.fillMaxWidth().aspectRatio(ukaz.width.toFloat() / ukaz.height).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (obrVysledok != null) {
                            listOf(false to T("vyl_pred"), true to T("vyl_po")).forEach { (po, nazov) ->
                                FilterChip(selected = poVylepseni == po, onClick = { poVylepseni = po }, label = { Text(nazov) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vybrat() }, enabled = !v.pracuje) { Text(T("vyl_ina"), color = Farby.ruzova) }
                    }
                    if (vysledok != null) {
                        Text("→ ${v.rozmerVysledku.first}×${v.rozmerVysledku.second}", color = Farby.tlmene, fontSize = 12.sp)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (android.os.Build.VERSION.SDK_INT >= 29) GradientTlacidlo(T("obr_ulozit"), Icons.Default.SaveAlt) {
                                Platforma.oznam(c.getString(if (ObrazkyJadro.ulozDoGalerie(vysledok)) R.string.obr_ulozene else R.string.obr_neulozene))
                            }
                        }
                    }
                }
            }
            Sekcia(T("vyl_zvacsenie"), ikona = Icons.Default.AutoFixHigh) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to T("vyl_bez"), 2 to "2×", 4 to "4×", VylepsenieJadro.K4 to "4K").forEach { (n, nazov) ->
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
                    LaunchedEffect(Unit) { PcSpojenie.skontroluj() }
                    val pcOk = PcSpojenie.pripojene && PcSpojenie.info?.vylepsit == true
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientTlacidlo(T("vyl_v_mobile"), Icons.Default.AutoFixHigh, Modifier.weight(1f),
                            enabled = v.vstup != null && (v.nasobok == 1 || v.mamModel()) && v.priebehStahovania == null) { poVylepseni = true; v.vylepsi() }
                        GradientTlacidlo(T("vyl_na_pc"), if (pcOk) Icons.Default.Computer else Icons.Default.Lock, Modifier.weight(1f),
                            enabled = pcOk && v.vstup != null) { poVylepseni = true; v.vylepsiNaPc() }
                    }
                    Text(when {
                        !PcSpojenie.pripojene -> T("obr_pc_zamknute")
                        PcSpojenie.info?.vylepsit != true -> T("vyl_pc_bez_modelu")
                        else -> T("vyl_pc_info")
                    }, color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    if (v.nasobok >= 4) Text(T("vyl_cas_mobil"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
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

/** náhľad fotky zmenšený na max ~2048 px (veľké 4K súbory by zbytočne zaberali pamäť) */
private fun nahlad(f: File): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(f.path, o)
    var vz = 1; while (maxOf(o.outWidth, o.outHeight) / vz > 2048) vz *= 2
    android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = vz })?.asImageBitmap()
}.getOrNull()

// ---------------------------------------------------------------- história chatov (šifrovaná)

@Composable
private fun CitacDialog(zavri: () -> Unit) {
    val d = Dokumenty.aktualny ?: return
    val n = ChatJadro.nastavenia
    androidx.compose.ui.window.Dialog(onDismissRequest = zavri) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(24.dp)).background(Farby.plocha).padding(16.dp)) {
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
                IconButton(onClick = zavri) { Icon(Icons.Default.Close, stringResource(R.string.zrusit), tint = Farby.text) }
            }
            Text(T("hist_info"), color = Farby.tlmene, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            GradientTlacidlo(stringResource(R.string.novy_chat), Icons.Default.AddComment, Modifier.fillMaxWidth()) { j.novyChat(); zavri() }
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
            dismissButton = { TextButton(onClick = { naZmazanie = null }) { Text(stringResource(R.string.zrusit), color = Farby.text) } },
        )
    }
}


// ---------------------------------------------------------------- Videá a galéria z PC (zamknuté bez pripojenia k PC)

private val DLZKY_PC = listOf(3, 5, 10, 20, 30, 60, 120, 180)

private fun dlzkaText(s: Int) = if (s >= 60) "${s / 60} min" else "$s s"

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VideaPcObrazovka(naNastavenia: () -> Unit, spat: () -> Unit) {
    val pc = PcSpojenie
    val c = LocalContext.current
    LaunchedEffect(Unit) { pc.skontroluj() }
    // kým je obrazovka otvorená, stav radu sa obnovuje
    LaunchedEffect(pc.pripojene) {
        while (pc.pripojene) { pc.obnovStav(); kotlinx.coroutines.delay(4000) }
    }
    val farbyPola = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text)
    Column(Modifier.fillMaxSize()) {
        Hlavicka(T("pc_nadpis"), pc.info?.let { T("pc_pripojene", it.meno) }, spat = spat)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!pc.pripojene) {
                Karta {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = Farby.ruzova, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(10.dp))
                        Text(T("pc_zamknute"), color = Farby.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    }
                    Text(T("pc_zamknute_info"), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                    if (pc.chyba.isNotBlank()) Text(pc.chyba, color = Farby.cervena, fontSize = 12.sp)
                    if (pc.kontroluje) LinearProgressIndicator(Modifier.padding(top = 8.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                    else Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientTlacidlo(T("pc_skusit"), Icons.Default.Refresh, Modifier.weight(1f)) { pc.skontroluj() }
                        OutlinedButton(onClick = naNastavenia, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) { Text(T("nastavenia"), color = Farby.text) }
                    }
                }
                Sekcia(T("pc_co_odomkne")) {
                    Text(T("pc_co_odomkne_zoznam"), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                return@Column
            }
            val info = pc.info!!
            var dlzka by remember { mutableIntStateOf(5) }
            // ---- nové video z textu
            Sekcia(T("pc_nove_video")) {
                if (!info.video) Text(T("pc_bez_video_modelu"), color = Farby.cervena, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                var popis by remember { mutableStateOf("") }
                var cezObrazok by remember { mutableStateOf(true) }
                var posiela by remember { mutableStateOf(false) }
                OutlinedTextField(value = popis, onValueChange = { popis = it }, minLines = 3, maxLines = 8, modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    placeholder = { Text(T("pc_popis_hint"), color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(14.dp), colors = farbyPola)
                Text(T("pc_dlzka"), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DLZKY_PC.forEach { d ->
                        FilterChip(selected = dlzka == d, onClick = { dlzka = d }, label = { Text(dlzkaText(d)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
                if (dlzka > 5) Text(T("pc_dlzka_info"), color = Farby.tlmene, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().clickable { cezObrazok = !cezObrazok }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T("pc_najprv_obrazok"), color = Farby.text, fontSize = 14.sp)
                        Text(T("pc_najprv_obrazok_info"), color = Farby.tlmene, fontSize = 12.sp)
                    }
                    Switch(checked = cezObrazok, onCheckedChange = { cezObrazok = it }, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                }
                GradientTlacidlo(T("pc_poslat"), Icons.AutoMirrored.Filled.Send, Modifier.padding(top = 8.dp).fillMaxWidth(), enabled = popis.isNotBlank() && !posiela && info.video) {
                    posiela = true
                    pc.pridaj(popis.trim(), dlzka, cezObrazok, null) { e -> posiela = false; if (e == null) { popis = ""; Platforma.oznam(T("pc_odoslane")) } else Platforma.oznam("⚠ $e") }
                }
            }
            // ---- rozhýbať obrázok z PC
            Sekcia(T("pc_rozhybat")) {
                if (!info.rozhybat) Text(T("pc_bez_i2v"), color = Farby.cervena, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                var vyber by remember { mutableStateOf(false) }
                var zvoleny by remember { mutableStateOf<String?>(null) }
                var pohyb by remember { mutableStateOf("") }
                var posiela by remember { mutableStateOf(false) }
                zvoleny?.let { m ->
                    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pc.nahlad(m)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                    }
                    bmp?.let { Image(it, null, Modifier.padding(top = 8.dp).heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit) }
                    OutlinedTextField(value = pohyb, onValueChange = { pohyb = it }, minLines = 2, maxLines = 6, modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                        placeholder = { Text(T("pc_pohyb_hint"), color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(14.dp), colors = farbyPola)
                    Text(T("pc_dlzka_rovnaka", dlzkaText(dlzka)), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vyber = true }, enabled = info.rozhybat, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) {
                        Icon(Icons.Default.PhotoLibrary, null, tint = Farby.text); Spacer(Modifier.width(6.dp)); Text(T(if (zvoleny == null) "pc_vybrat_obrazok" else "pc_iny_obrazok"), color = Farby.text)
                    }
                    if (zvoleny != null) GradientTlacidlo(T("pc_rozhybat_tlacidlo"), Icons.AutoMirrored.Filled.Send, Modifier.weight(1f), enabled = !posiela) {
                        posiela = true
                        pc.pridaj(pohyb.trim(), dlzka, false, zvoleny) { e -> posiela = false; if (e == null) { zvoleny = null; pohyb = ""; Platforma.oznam(T("pc_odoslane")) } else Platforma.oznam("⚠ $e") }
                    }
                }
                if (vyber) GaleriaPcDialog(lenAppka = true, zavri = { vyber = false }) { zvoleny = it; vyber = false }
            }
            // ---- galéria PC (stiahnuť obrázky do mobilu)
            Karta {
                var galeria by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T("pc_galeria"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                        Text(T("pc_galeria_info"), color = Farby.tlmene, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { galeria = true }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Farby.okraj)) { Text(T("pc_otvorit"), color = Farby.text) }
                }
                if (galeria) GaleriaPcDialog(lenAppka = false, zavri = { galeria = false }) { pc.ulozObrazok(it) }
            }
            // ---- videoklip na skladbu (skladba z mobilu, obrázky z galérie PC, robí PC)
            Sekcia(T("klip_nadpis")) {
                var skladba by remember { mutableStateOf<Uri?>(null) }
                var menoSkladby by remember { mutableStateOf("") }
                val fotky = remember { mutableStateListOf<String>() }
                var tempo by remember { mutableIntStateOf(2) }
                var spev by remember { mutableStateOf(false) }
                var vyber by remember { mutableStateOf(false) }
                var posiela by remember { mutableStateOf(false) }
                val vyberSkladbu = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri != null) {
                        skladba = uri
                        menoSkladby = runCatching { c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { k -> if (k.moveToFirst()) k.getString(0) else null } }.getOrNull() ?: "skladba"
                    }
                }
                Text(T("klip_info"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = Farby.ruzova, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(menoSkladby.ifBlank { T("klip_ziadna_skladba") }, color = if (skladba != null) Farby.text else Farby.tlmene, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vyberSkladbu.launch(arrayOf("audio/*")) }) { Text(T("klip_skladba"), color = Farby.ruzova) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(T("klip_fotky_pc", fotky.size), color = Farby.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vyber = true }) { Text(T("klip_pridat"), color = Farby.ruzova) }
                    if (fotky.isNotEmpty()) IconButton(onClick = { fotky.clear() }) { Icon(Icons.Default.DeleteOutline, null, tint = Farby.tlmene) }
                }
                if (fotky.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fotky.forEachIndexed { i, m ->
                        val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pc.nahlad(m)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                        }
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Farby.plocha2).clickable { fotky.removeAt(i) }) {
                            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            Text("${i + 1}", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).background(Color(0x88000000)).padding(horizontal = 4.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { spev = !spev }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T("spev_prepinac"), color = Farby.text, fontSize = 14.sp)
                        Text(T("spev_info"), color = Farby.tlmene, fontSize = 12.sp)
                    }
                    Switch(checked = spev, onCheckedChange = { spev = it }, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                }
                if (!spev) Text(T("klip_tempo"), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                if (!spev) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "klip_pomaly", 2 to "klip_stredne", 3 to "klip_rychlo").forEach { (t, kl) ->
                        FilterChip(selected = tempo == t, onClick = { tempo = t }, label = { Text(T(kl)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
                    }
                }
                GradientTlacidlo(T(if (spev) "spev_vytvorit" else "klip_odoslat"), Icons.AutoMirrored.Filled.Send, Modifier.padding(top = 8.dp).fillMaxWidth(), enabled = skladba != null && fotky.isNotEmpty() && !posiela) {
                    posiela = true
                    pc.pridajKlip(skladba!!, fotky.toList(), tempo, spev) { e -> posiela = false; if (e == null) { fotky.clear(); skladba = null; menoSkladby = ""; Platforma.oznam(T("pc_odoslane")) } else Platforma.oznam("⚠ $e") }
                }
                if (vyber) GaleriaPcDialog(lenAppka = true, zavri = { vyber = false }) { if (it !in fotky) fotky += it; vyber = false }
            }
            // ---- rad videí na PC
            Sekcia(T("pc_rad")) {
                val st = pc.stav
                val ulohy = st?.optJSONArray("ulohy")
                if (st?.optBoolean("pcZaneprazdnene") == true) Text(T("pc_zaneprazdnene"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (ulohy == null || ulohy.length() == 0) Text(T("pc_rad_prazdny"), color = Farby.tlmene, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                else for (i in 0 until ulohy.length()) {
                    val u = ulohy.getJSONObject(i)
                    val id = u.getString("id")
                    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Farby.plocha2).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(u.optString("popis").ifBlank { T("pc_bez_popisu") }, color = Farby.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { pc.zrus(id) }, modifier = Modifier.size(32.dp)) { Icon(if (u.optString("stav") == "bezi") Icons.Default.StopCircle else Icons.Default.DeleteOutline, null, tint = Farby.tlmene) }
                        }
                        val druh = if (u.optBoolean("spev")) T("spev_prepinac") else if (u.optBoolean("klip")) T("klip_nadpis") else if (u.optString("predloha").isNotBlank()) T("pc_druh_rozhybat") else if (u.optBoolean("cezObrazok")) T("pc_druh_obrazok") else T("pc_druh_text")
                        Text(if (u.optBoolean("klip")) druh else "${dlzkaText(u.optInt("sekundy"))} · $druh", color = Farby.tlmene, fontSize = 12.sp)
                        when (u.optString("stav")) {
                            "caka" -> Text(T("pc_caka"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            "bezi" -> {
                                val p = st.optDouble("priebeh", 0.0).toFloat()
                                // čas beží ďalej aj medzi obnoveniami stavu (každé 4 s)
                                val ms = u.optLong("bezi_ms"); val prijate = remember(ms) { System.currentTimeMillis() }
                                var teraz by remember { mutableLongStateOf(System.currentTimeMillis()) }
                                LaunchedEffect(Unit) { while (true) { teraz = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
                                Text("⏱ " + Cas.text(ms + (teraz - prijate)), color = Farby.ruzova, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                                Text(st.optString("text").ifBlank { T("pc_tvori", (p * 100).roundToInt()) }, color = Farby.text, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                                LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha)
                            }
                            "chyba" -> Text("⚠ " + u.optString("chyba"), color = Farby.cervena, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            "hotova" -> {
                                if (u.optLong("trvanie") > 0) Text("✓ " + T("cas_hotovo", Cas.text(u.optLong("trvanie"))), color = Farby.zelena, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                val sp = pc.stahuje[id]
                                val uri = pc.stiahnute[id]
                                when {
                                    sp != null -> LinearProgressIndicator(progress = { sp }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = Farby.zelena, trackColor = Farby.plocha)
                                    uri != null -> Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GradientTlacidlo(T("video_prehrat"), Icons.Default.PlayArrow) {
                                            runCatching {
                                                c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4")
                                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                            }.onFailure { Platforma.oznam("⚠ " + (it.message ?: "")) }
                                        }
                                        TextButton(onClick = { pc.stiahni(id) }) { Text(T("pc_stiahnut_znova"), color = Farby.tlmene, fontSize = 12.sp) }
                                    }
                                    else -> GradientTlacidlo(T("pc_stiahnut", "%.1f".format(u.optLong("velkost") / 1_000_000.0)), Icons.Default.Download, Modifier.padding(top = 6.dp)) { pc.stiahni(id) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** výber obrázka z galérie PC (náhľady sa sťahujú postupne) */
@Composable
private fun GaleriaPcDialog(lenAppka: Boolean, zavri: () -> Unit, zvol: (String) -> Unit) {
    var zoznam by remember { mutableStateOf<List<Triple<String, String, Boolean>>?>(null) }
    var chyba by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { PcSpojenie.galeria { z, e -> zoznam = z?.filter { !lenAppka || it.third }; chyba = e } }
    androidx.compose.ui.window.Dialog(onDismissRequest = zavri) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(20.dp)).background(Farby.plocha).padding(12.dp)) {
            Text(T(if (lenAppka) "pc_vyber_nadpis" else "pc_galeria"), color = Farby.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            val z = zoznam
            when {
                z == null && chyba.isBlank() -> LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp), color = Farby.ruzova, trackColor = Farby.plocha2)
                z == null -> Text("⚠ $chyba", color = Farby.cervena, fontSize = 13.sp)
                z.isEmpty() -> Text(T("pc_galeria_prazdna"), color = Farby.tlmene, fontSize = 13.sp)
                else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(z.size) { i ->
                        val m = z[i].first
                        val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PcSpojenie.nahlad(m)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                        }
                        Box(Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)).background(Farby.plocha2).clickable { zvol(m) }, contentAlignment = Alignment.Center) {
                            val b = bmp
                            if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else CircularProgressIndicator(Modifier.size(18.dp), color = Farby.ruzova, strokeWidth = 2.dp)
                            if (!lenAppka) Icon(Icons.Default.Download, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp))
                        }
                    }
                }
            }
            TextButton(onClick = zavri, modifier = Modifier.align(Alignment.End)) { Text(T("zavriet"), color = Farby.ruzova) }
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

/** tvar obrázka – platí pre V mobile aj Na PC */
@Composable
private fun TvarObrazka(rozmer: Int) {
    val n = ChatJadro.nastavenia
    Text(T("obr_tvar"), color = Farby.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("stvorec", "vyska", "sirka", "nahodne", "vlastne").forEach { t ->
            val popis = if (t == "nahodne" || t == "vlastne") T("obr_tvar_$t") else ObrazkyJadro.rozmerPomeru(rozmer, t).let { (w, v) -> T("obr_tvar_$t") + " ${w}×$v" }
            FilterChip(selected = n.obrPomer == t, onClick = { n.obrPomer = t; n.uloz() }, label = { Text(popis, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Farby.fialova, selectedLabelColor = Color.White, labelColor = Farby.text))
        }
    }
    if (n.obrPomer == "vlastne") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var sTxt by remember { mutableStateOf(n.obrSirka.toString()) }
        var vTxt by remember { mutableStateOf(n.obrVyska.toString()) }
        val farby = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text)
        OutlinedTextField(value = sTxt, onValueChange = { t -> sTxt = t.filter { it.isDigit() }.take(4); sTxt.toIntOrNull()?.let { n.obrSirka = it; n.uloz() } },
            singleLine = true, label = { Text(T("obr_sirka")) }, modifier = Modifier.width(100.dp), shape = RoundedCornerShape(12.dp), colors = farby,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        Text("×", color = Farby.text)
        OutlinedTextField(value = vTxt, onValueChange = { t -> vTxt = t.filter { it.isDigit() }.take(4); vTxt.toIntOrNull()?.let { n.obrVyska = it; n.uloz() } },
            singleLine = true, label = { Text(T("obr_vyska")) }, modifier = Modifier.width(100.dp), shape = RoundedCornerShape(12.dp), colors = farby,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        val (w, v) = ObrazkyJadro.rozmerPomeru(rozmer, "vlastne")
        Text(T("obr_vlastne_info", w, v), color = Farby.tlmene, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

/** prenos štýlu cez PC (FLUX.3 Klein): obrázok z galérie PC v štýle obrázka z mobilu */
@Composable
private fun PrenosStyluPcKarta() {
    val o = ObrazkyJadro
    var obsah by remember { mutableStateOf<String?>(null) }
    var styl by remember { mutableStateOf<Uri?>(null) }
    var doplnok by remember { mutableStateOf("") }
    var vyber by remember { mutableStateOf(false) }
    var vysledok by remember { mutableStateOf<File?>(null) }
    var chyba by remember { mutableStateOf("") }
    var bezi by remember { mutableStateOf(false) }
    val vyberStyl = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? -> if (u != null) { styl = u; chyba = "" } }
    val pcOk = PcSpojenie.pripojene && PcSpojenie.info?.styl == true
    Sekcia(T("styl_nadpis_pc"), ikona = if (PcSpojenie.pripojene) Icons.Default.Palette else Icons.Default.Lock) {
        Text(when {
            !PcSpojenie.pripojene -> T("obr_pc_zamknute")
            PcSpojenie.info?.styl != true -> T("styl_treba_klein_pc")
            else -> T("styl_info_pc")
        }, color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        if (pcOk) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // obsah: náhľad z PC
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(T("styl_obsah_pc"), color = Farby.text, fontSize = 12.sp)
                    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, obsah) {
                        value = obsah?.let { m -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PcSpojenie.nahlad(m)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } } }
                    }
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp)).background(Farby.plocha2).clickable { vyber = true }, contentAlignment = Alignment.Center) {
                        val b = bmp
                        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Text(T("styl_tukni"), color = Farby.tlmene, fontSize = 11.sp)
                    }
                }
                // štýl: obrázok z mobilu
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(T("styl_styl"), color = Farby.text, fontSize = 12.sp)
                    val c = LocalContext.current
                    val bmp = remember(styl) { styl?.let { u -> runCatching { c.contentResolver.openInputStream(u)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) }?.asImageBitmap() }.getOrNull() } }
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp)).background(Farby.plocha2).clickable { vyberStyl.launch("image/*") }, contentAlignment = Alignment.Center) {
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Text(T("styl_tukni"), color = Farby.tlmene, fontSize = 11.sp)
                    }
                }
            }
            OutlinedTextField(value = doplnok, onValueChange = { doplnok = it }, singleLine = true, modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                placeholder = { Text(T("styl_doplnok_hint"), color = Farby.tlmene, fontSize = 13.sp) }, shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Farby.fialova, unfocusedBorderColor = Farby.okraj, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text))
            if (bezi) {
                Text(T("styl_pracuje_pc"), color = Farby.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                LinearProgressIndicator(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
            } else GradientTlacidlo(T("styl_spustit"), Icons.Default.Palette, Modifier.padding(top = 8.dp).fillMaxWidth(), enabled = obsah != null && styl != null && !o.generuje) {
                bezi = true; chyba = ""; vysledok = null
                o.prenosStyluNaPc(obsah!!, styl!!, doplnok) { f, e -> bezi = false; if (f != null) vysledok = f else chyba = e }
            }
            if (chyba.isNotBlank()) Text(chyba, color = Farby.cervena, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            vysledok?.let { f ->
                val b = remember(f) { android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
                if (b != null) Image(b, null, Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                Text(T("styl_v_galerii"), color = Farby.zelena, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (vyber) GaleriaPcDialog(lenAppka = true, zavri = { vyber = false }) { obsah = it; vyber = false }
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

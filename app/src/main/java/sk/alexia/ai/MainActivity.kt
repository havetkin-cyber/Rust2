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

private enum class Obrazovka { CHAT, OBRAZKY, VYLEPSIT, MODELY, NASTAVENIA }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Appka() {
    var obrazovka by remember { mutableStateOf(Obrazovka.CHAT) }
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
        Triple(Obrazovka.VYLEPSIT, Icons.Default.AutoFixHigh, "vylepsit_kratko"),
        Triple(Obrazovka.MODELY, Icons.Default.Memory, "modely"),
        Triple(Obrazovka.NASTAVENIA, Icons.Default.Tune, "nastavenia"),
    )
    NavigationBar(containerColor = Farby.plocha.copy(alpha = 0.96f), tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
        polozky.forEach { (o, ikona, kluc) ->
            NavigationBarItem(
                selected = o == aktivna, onClick = { zvol(o) },
                icon = { Icon(ikona, null) }, label = { Text(T(kluc), fontSize = 11.sp, maxLines = 1) },
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

        // vstup
        Row(
            Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Farby.plocha)
                .border(1.dp, Farby.okraj, RoundedCornerShape(28.dp)).padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { rezimObrazka = !rezimObrazka }) {
                Icon(Icons.Default.Brush, T("chat_obr_rezim"), tint = if (rezimObrazka) Farby.ruzova else Farby.tlmene)
            }
            TextField(
                value = vstup, onValueChange = { vstup = it },
                placeholder = { Text(if (rezimObrazka) T("chat_obr_popis") else stringResource(if (j.stav == StavModelu.PRIPRAVENY) R.string.napis_spravu else R.string.ziadny_model_kratko), color = Farby.tlmene, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.weight(1f), maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    cursorColor = Farby.ruzova, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text,
                ),
            )
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

            item { Text(stringResource(R.string.modely_moje), color = Farby.tlmene, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
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

            item { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.modely_odporucane), color = Farby.tlmene, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            items(Modely.odporucane) { m ->
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
                Karta {
                    Text(stringResource(R.string.modely_vlastny), color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
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
                Text(T("hlas_android"), color = Farby.tlmene, fontSize = 12.sp)
            }
            Karta {
                val c = LocalContext.current
                Text(T("akt_karta"), color = Farby.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (Aktualizacie.kontroluje) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                else GradientTlacidlo(T("akt_skontrolovat"), Icons.Default.SystemUpdate, Modifier.fillMaxWidth()) { Aktualizacie.skontroluj(c, rucne = true) }
                Row(Modifier.fillMaxWidth().clickable { n.autoAktualizacie = !n.autoAktualizacie; n.uloz() }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(T("akt_auto"), color = Farby.text, modifier = Modifier.weight(1f))
                    Switch(checked = n.autoAktualizacie, onCheckedChange = { n.autoAktualizacie = it; n.uloz() }, colors = SwitchDefaults.colors(checkedTrackColor = Farby.fialova, checkedThumbColor = Color.White))
                }
            }
            Karta {
                Text(T("poz_chat_karta"), color = Farby.text, fontWeight = FontWeight.SemiBold)
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
            Karta {
                Text(stringResource(R.string.nast_system), color = Farby.text, fontWeight = FontWeight.SemiBold)
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
            Karta {
                Text(stringResource(R.string.nast_kontext), color = Farby.text, fontWeight = FontWeight.SemiBold)
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
                    Karta {
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
                    if (o.generuje) {
                        Text(stringResource(R.string.obr_tvorim, (o.priebeh * 100).roundToInt()), color = Farby.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { o.priebeh }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                        TextButton(onClick = { o.zrus() }) { Text(stringResource(R.string.zrusit), color = Farby.tlmene) }
                    } else {
                        GradientTlacidlo(stringResource(R.string.obr_vytvorit), Icons.Default.AutoAwesome, Modifier.fillMaxWidth(),
                            enabled = o.stav == StavModelu.PRIPRAVENY && prompt.isNotBlank()) { o.generuj(popisSPozadim(), negativny, rozmer, kroky, cfg) }
                        Text(stringResource(R.string.obr_cas), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (o.chyba.isNotBlank()) Text(o.chyba, color = Farby.cervena, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }

                // galéria
                if (o.galeria.isNotEmpty()) {
                    Text(stringResource(R.string.obr_galeria), color = Farby.tlmene, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            Text(stringResource(R.string.obr_modely), color = Farby.tlmene, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
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
            Text(stringResource(R.string.obr_info), color = Farby.tlmene, fontSize = 12.sp)
        }
    }

    detail?.let { f ->
        val bmp = remember(f) { android.graphics.BitmapFactory.decodeFile(f.path) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { detail = null }) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Farby.plocha).padding(12.dp)) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
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
    AlertDialog(
        onDismissRequest = { Aktualizacie.novaVerzia = null },
        containerColor = Farby.plocha,
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = Farby.ruzova) },
        title = { Text(stringResource(R.string.akt_nadpis, v), color = Farby.text) },
        text = { Text(stringResource(R.string.akt_text), color = Farby.tlmene) },
        confirmButton = { TextButton(onClick = { Aktualizacie.stiahni(c) }) { Text(stringResource(R.string.akt_stiahnut), color = Farby.ruzova) } },
        dismissButton = { TextButton(onClick = { Aktualizacie.preskoc(c) }) { Text(stringResource(R.string.akt_neskor), color = Farby.tlmene) } },
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
            Karta {
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
            Karta {
                Text(T("vyl_zvacsenie"), color = Farby.text, fontWeight = FontWeight.SemiBold)
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
                if (v.pracuje) {
                    Text(v.cinnost, color = Farby.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Farby.ruzova, trackColor = Farby.plocha2)
                } else {
                    GradientTlacidlo(T("vyl_spustit"), Icons.Default.AutoFixHigh, Modifier.fillMaxWidth(),
                        enabled = v.vstup != null && (v.nasobok == 1 || v.mamModel()) && v.priebehStahovania == null) { poVylepseni = true; v.vylepsi() }
                    if (v.nasobok >= 4) Text(T("vyl_cas_mobil"), color = Farby.tlmene, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
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

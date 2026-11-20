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
        setContent { AlexiaTema { Appka() } }
    }
}

private enum class Obrazovka { CHAT, MODELY, NASTAVENIA }

@Composable
private fun Appka() {
    var obrazovka by remember { mutableStateOf(Obrazovka.CHAT) }
    BackHandler(obrazovka != Obrazovka.CHAT) { obrazovka = Obrazovka.CHAT }
    Box(
        Modifier
            .fillMaxSize()
            .background(Farby.pozadie)
            .background(Brush.radialGradient(listOf(Farby.fialova.copy(alpha = 0.16f), Color.Transparent), radius = 900f))
            .systemBarsPadding()
            .imePadding(),
    ) {
        when (obrazovka) {
            Obrazovka.CHAT -> ChatObrazovka(
                naModely = { obrazovka = Obrazovka.MODELY },
                naNastavenia = { obrazovka = Obrazovka.NASTAVENIA },
            )
            Obrazovka.MODELY -> ModelyObrazovka { obrazovka = Obrazovka.CHAT }
            Obrazovka.NASTAVENIA -> NastaveniaObrazovka { obrazovka = Obrazovka.CHAT }
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
private fun ChatObrazovka(naModely: () -> Unit, naNastavenia: () -> Unit) {
    val j = ChatJadro
    val zoznam = rememberLazyListState()
    var vstup by remember { mutableStateOf("") }
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
            IconButton(onClick = { j.novyChat() }, enabled = j.spravy.isNotEmpty()) { Icon(Icons.Default.AddComment, stringResource(R.string.novy_chat), tint = Farby.text) }
            IconButton(onClick = naModely) { Icon(Icons.Default.Memory, stringResource(R.string.modely), tint = Farby.text) }
            IconButton(onClick = naNastavenia) { Icon(Icons.Default.Tune, stringResource(R.string.nastavenia), tint = Farby.text) }
        }

        Box(Modifier.weight(1f)) {
            if (j.spravy.isEmpty()) {
                Uvod(naModely) { vstup = it }
            } else {
                LazyColumn(state = zoznam, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(j.spravy) { i, s ->
                        if (s.rola == "user") BublinaPouzivatel(s.text)
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
            TextField(
                value = vstup, onValueChange = { vstup = it },
                placeholder = { Text(stringResource(if (j.stav == StavModelu.PRIPRAVENY) R.string.napis_spravu else R.string.ziadny_model_kratko), color = Farby.tlmene) },
                modifier = Modifier.weight(1f), maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    cursorColor = Farby.ruzova, focusedTextColor = Farby.text, unfocusedTextColor = Farby.text,
                ),
            )
            val moze = j.stav == StavModelu.PRIPRAVENY && vstup.isNotBlank()
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (j.generuje) Brush.linearGradient(listOf(Farby.cervena, Farby.cervena)) else if (moze) Farby.prechod else Brush.linearGradient(listOf(Farby.plocha2, Farby.plocha2)))
                    .clickable(enabled = j.generuje || moze) {
                        if (j.generuje) j.zastav() else { j.posli(vstup); vstup = "" }
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
                SelectionContainer {
                    Text(odpoved + if (pise) " ▍" else "", color = Farby.text, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
            if (!pise && odpoved.isNotEmpty()) {
                Row(Modifier.padding(top = 2.dp)) {
                    IconButton(onClick = { schranka.setText(AnnotatedString(odpoved)); Toast.makeText(context, skopirovane, Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.kopirovat), tint = Farby.tlmene, modifier = Modifier.size(16.dp))
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
            val r = runCatching { Modely.importuj(c, uri) { p -> priebeh = p } }
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
                    GradientTlacidlo(stringResource(R.string.stiahnut), Icons.Default.Download, enabled = priebeh == null && odkaz.startsWith("http")) { stiahni(odkaz) }
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

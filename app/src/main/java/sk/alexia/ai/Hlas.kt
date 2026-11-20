package sk.alexia.ai

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Hlas v mobile: systémový prevod textu na reč (offline, ak má telefón stiahnutú slovenčinu). */
object Hlas {
    private var tts: TextToSpeech? = null
    private var pripraveny = false
    var hovori by mutableStateOf<String?>(null)
        private set
    private var cakajuci: String? = null

    private fun jazyk(): Locale = if (ChatJadro.nastavenia.jazykOdpovedi == "en") Locale.ENGLISH else Locale("sk", "SK")

    private fun priprav() {
        if (tts != null) return
        tts = TextToSpeech(Platforma.app) { st ->
            pripraveny = st == TextToSpeech.SUCCESS
            if (pripraveny) {
                val t = tts!!
                t.language = jazyk()
                // ženský hlas, ak ho systém označí (Google má v názve často „female“ alebo „#female“)
                runCatching { t.voices?.filter { it.locale.language == jazyk().language && !it.isNetworkConnectionRequired }
                    ?.firstOrNull { it.name.contains("female", true) || it.features.any { f -> f.contains("female", true) } }?.let { t.voice = it } }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "koniec") Platforma.naHlavnom { hovori = null } }
                    @Deprecated("") override fun onError(id: String?) { Platforma.naHlavnom { hovori = null } }
                })
                cakajuci?.let { c -> cakajuci = null; hovor(c) }
            } else Platforma.oznam(T("hlas_chyba", "TTS"))
        }
    }

    fun naCitanie(t: String): String {
        var s = t.replace(Regex("<think>[\\s\\S]*?(</think>|$)"), " ")
        s = s.replace(Regex("```[\\s\\S]*?(```|$)"), ". ")
        s = s.replace(Regex("https?://\\S+"), " ")
        return s.replace(Regex("[*#_`>|~]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun hovor(text: String) {
        if (tts == null || !pripraveny) { cakajuci = text; priprav(); return }
        val t = tts!!; t.stop(); t.language = jazyk()
        hovori = text
        // TTS má limit dĺžky – čítame po vetách
        val vety = naCitanie(text).split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        vety.forEachIndexed { i, v ->
            t.speak(v.take(3900), if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == vety.lastIndex) "koniec" else "v$i")
        }
        if (vety.isEmpty()) hovori = null
    }

    fun zastav() { tts?.stop(); hovori = null; cakajuci = null }

    fun poOdpovedi(text: String) { if (ChatJadro.nastavenia.citatNahlas && text.isNotBlank() && !text.startsWith("⚠")) hovor(text) }
}

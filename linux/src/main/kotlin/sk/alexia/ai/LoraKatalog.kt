package sk.alexia.ai

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Katalóg LoRA z Hugging Face – načíta sa živo: všetky LoRA, ktoré autori označili ako adaptér
 * pre model, ktorý appka používa (Z-Image, FLUX.1 schnell, SD 1.5, Wan 2.2 5B, Wan 2.1 1.3B, chatové modely).
 * Explicitný obsah sa vyradí (značka not-for-all-audiences a slová v názve / značkách).
 */
object LoraKatalog {
    /** základný model v appke → repozitáre na Hugging Face, ku ktorým sa LoRA hľadajú */
    data class Zaklad(val kat: String, val nazov: String, val repa: List<String>)

    val ZAKLADY = listOf(
        Zaklad("obrazky", "Z-Image Turbo", listOf("Tongyi-MAI/Z-Image-Turbo")),
        Zaklad("obrazky", "FLUX.1 schnell", listOf("black-forest-labs/FLUX.1-schnell")),
        Zaklad("obrazky", "Stable Diffusion 1.5", listOf("stable-diffusion-v1-5/stable-diffusion-v1-5", "runwayml/stable-diffusion-v1-5")),
        Zaklad("videa", "Wan 2.2 5B", listOf("Wan-AI/Wan2.2-TI2V-5B", "Wan-AI/Wan2.2-TI2V-5B-Diffusers")),
        Zaklad("videa", "Wan 2.1 1.3B", listOf("Wan-AI/Wan2.1-T2V-1.3B", "Wan-AI/Wan2.1-T2V-1.3B-Diffusers")),
        Zaklad("chat", "Qwen3 4B 2507", listOf("Qwen/Qwen3-4B-Instruct-2507")),
        Zaklad("chat", "Qwen3 8B", listOf("Qwen/Qwen3-8B")),
        Zaklad("chat", "Qwen2.5 1.5B", listOf("Qwen/Qwen2.5-1.5B-Instruct")),
        Zaklad("chat", "Qwen2.5 3B", listOf("Qwen/Qwen2.5-3B-Instruct")),
        Zaklad("chat", "Qwen2.5 7B", listOf("Qwen/Qwen2.5-7B-Instruct")),
        Zaklad("chat", "Qwen2.5 14B", listOf("Qwen/Qwen2.5-14B-Instruct")),
        Zaklad("chat", "Llama 3.2 3B", listOf("meta-llama/Llama-3.2-3B-Instruct")),
        Zaklad("chat", "Llama 3.1 8B", listOf("meta-llama/Llama-3.1-8B-Instruct", "meta-llama/Meta-Llama-3.1-8B-Instruct")),
        Zaklad("chat", "Gemma 3 4B", listOf("google/gemma-3-4b-it")),
        Zaklad("chat", "Gemma 3 12B", listOf("google/gemma-3-12b-it")),
        Zaklad("chat", "Mistral 7B v0.3", listOf("mistralai/Mistral-7B-Instruct-v0.3")),
        Zaklad("chat", "Phi-4 mini", listOf("microsoft/Phi-4-mini-instruct")),
    )

    data class Polozka(val repo: String, val subory: List<String>, val stiahnutia: Int, val paci: Int, val zmenene: String) {
        val meno get() = repo.substringAfter('/')
        val autor get() = repo.substringBefore('/')
    }

    /** explicitný obsah sa do katalógu nedostane */
    private val ZAKAZANE = Regex(
        "(^|[^a-z])(nsfw|nude|nudes|nudity|naked|porn\\w*|hentai|lewd|sexy|sex|sexual|erotic\\w*|boobs?|breasts?|nipples?|ass|butt|booty|" +
            "lingerie|bikini|underwear|panties|topless|xxx|onlyfans|fetish|bdsm|r18|uncensored|undress\\w*|futa\\w*|jiggle|bouncy|bounce|" +
            "cleavage|seduct\\w*|horny|genital\\w*|penis|vagina|pussy|cum|blowjob|handjob|deepthroat|ahegao|strip\\w*|thicc|milf|waifu|celeb\\w*)([^a-z]|$)")

    private fun cisty(id: String, znacky: List<String>): Boolean {
        if (znacky.any { it == "not-for-all-audiences" }) return false
        val t = (id + " " + znacky.joinToString(" ")).lowercase().replace('_', ' ').replace('-', ' ')
        return !ZAKAZANE.containsMatchIn(t)
    }

    private val pamat = mutableMapOf<String, List<Polozka>>()

    /** všetky LoRA pre základný model (blokuje – volať mimo UI); zoradené podľa počtu stiahnutí */
    fun nacitaj(z: Zaklad, nanovo: Boolean = false): List<Polozka> {
        if (!nanovo) pamat[z.nazov]?.let { return it }
        val vysl = linkedMapOf<String, Polozka>()
        for (repo in z.repa) {
            var url: String? = "https://huggingface.co/api/models?filter=" + URLEncoder.encode("base_model:adapter:$repo", "UTF-8") +
                "&sort=downloads&direction=-1&limit=500&full=true"
            var strany = 0
            while (url != null && strany++ < 10) {
                val k = URL(url).openConnection() as HttpURLConnection
                k.setRequestProperty("User-Agent", "AlexiaAI (Linux)"); k.connectTimeout = 20_000; k.readTimeout = 60_000
                if (k.responseCode != 200) throw IllegalStateException("Hugging Face vrátil ${k.responseCode}")
                val a = JSONArray(k.inputStream.bufferedReader().readText())
                // ďalšia strana: Link: <…>; rel="next"
                url = k.getHeaderField("Link")?.let { Regex("<([^>]+)>;\\s*rel=\"next\"").find(it)?.groupValues?.get(1) }
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val id = o.optString("id").ifBlank { o.optString("modelId") }
                    if (id.isBlank() || id in vysl) continue
                    val znacky = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList()
                    if (o.optBoolean("gated") || o.optString("gated") in setOf("auto", "manual")) continue
                    if (!cisty(id, znacky)) continue
                    val sub = o.optJSONArray("siblings")?.let { s -> (0 until s.length()).map { s.getJSONObject(it).optString("rfilename") } } ?: emptyList()
                    val vhodne = vhodneSubory(z.kat, sub)
                    if (vhodne.isEmpty()) continue
                    vysl[id] = Polozka(id, vhodne, o.optInt("downloads"), o.optInt("likes"), o.optString("lastModified").take(10))
                }
            }
        }
        return vysl.values.sortedByDescending { it.stiahnutia }.also { pamat[z.nazov] = it }
    }

    /** súbory, ktoré appka vie použiť: chat = .gguf, obrázky / videá = .safetensors (nie celé modely, VAE, textové enkodéry) */
    private fun vhodneSubory(kat: String, s: List<String>): List<String> {
        val pripona = if (kat == "chat") ".gguf" else ".safetensors"
        return s.filter { f ->
            val l = f.lowercase()
            l.endsWith(pripona) && !l.contains("text_encoder") && !l.contains("vae/") && !l.contains("unet/") && !l.contains("transformer/") &&
                !l.contains("mmproj") && !Regex("-\\d{5}-of-\\d{5}").containsMatchIn(l) &&
                // Wan 14B (high / low noise) nefunguje s 5B
                !(kat == "videa" && (l.contains("high_noise") || l.contains("low_noise") || l.contains("14b")))
        }
    }

    /** najvhodnejší súbor z repozitára: v koreni, s „lora“ v mene, inak posledná verzia (epocha) */
    fun najlepsiSubor(p: Polozka): String {
        val koren = p.subory.filter { '/' !in it }.ifEmpty { p.subory }
        return koren.firstOrNull { it.lowercase().contains("lora") && !Regex("\\d{3,}").containsMatchIn(it) }
            ?: koren.maxByOrNull { it.lowercase() }!!
    }

    fun odkaz(p: Polozka, subor: String) =
        "https://huggingface.co/${p.repo}/resolve/main/" + subor.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** meno súboru v priečinku LoRA (repo + súbor, aby sa rovnaké mená „pytorch_lora_weights“ neprepísali) */
    fun menoSuboru(p: Polozka, subor: String): String {
        val zaklad = subor.substringAfterLast('/')
        val pref = p.meno.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (zaklad.lowercase().startsWith(pref.lowercase())) zaklad else "${pref}__$zaklad"
    }

    fun mam(kat: String, p: Polozka) = File(Lory.priecinok(kat), menoSuboru(p, najlepsiSubor(p))).exists()

    /** stiahne LoRA do priečinka kategórie (blokuje) */
    fun stiahni(kat: String, p: Polozka, priebeh: (Float) -> Unit): File {
        val subor = najlepsiSubor(p)
        val docasny = File(Lory.priecinok(kat), ".stahovanie").apply { mkdirs() }
        val f = Modely.stiahni(odkaz(p, subor), priebeh, { false }, docasny, true)
        val ciel = File(Lory.priecinok(kat), menoSuboru(p, subor))
        ciel.delete()
        if (!f.renameTo(ciel)) { f.copyTo(ciel, overwrite = true); f.delete() }
        return ciel
    }
}

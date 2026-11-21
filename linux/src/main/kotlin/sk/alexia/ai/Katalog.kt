package sk.alexia.ai

import org.json.JSONObject

/** Obrázkový model z jedného alebo viacerých súborov (napr. Z-Image: obrazový model + Qwen3 4B + VAE). */
data class ObrazkovaSada(
    val id: String, val nazov: String, val popis: String, val mb: Int,
    val kroky: Int, val cfg: Float, val rozmer: Int, val metoda: String, val fa: Boolean, val negativny: Boolean,
    /** kľúč pre stable-diffusion.cpp (model_path, diffusion_model_path, llm_path, vae_path, …) -> odkaz */
    val subory: Map<String, String>,
    /** video AI (Wan): rozmer, počet snímok, fps, flow shift; null = obrázkový model */
    val video: VideoNastavenie? = null,
)

/** i2v = vie rozhýbať obrázok (prvá snímka = obrázok) */
data class VideoNastavenie(val sirka: Int, val vyska: Int, val snimok: Int, val fps: Int, val posun: Float, val i2v: Boolean = false)

/** Katalóg overených modelov (resources/katalog.json – odkazy kontroluje GitHub). */
object Katalog {
    private val json by lazy { JSONObject(Katalog::class.java.getResource("/katalog.json")!!.readText()) }

    val text: List<OdporucanyModel> by lazy {
        val a = json.getJSONArray("text")
        (0 until a.length()).map { i -> a.getJSONObject(i).let {
            OdporucanyModel(it.getString("nazov"), it.getString("popis"), it.getString("url"), it.getInt("mb"), it.optString("ram"))
        } }
    }

    val obrazky: List<ObrazkovaSada> by lazy {
        val a = json.getJSONArray("obrazky")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            val s = o.getJSONObject("subory")
            ObrazkovaSada(
                o.getString("id"), o.getString("nazov"), o.getString("popis"), o.getInt("mb"),
                o.getInt("kroky"), o.getDouble("cfg").toFloat(), o.getInt("rozmer"), o.getString("metoda"),
                o.getBoolean("fa"), o.getBoolean("negativny"),
                s.keys().asSequence().associateWith { k -> s.getString(k) },
                o.optJSONObject("video")?.let { v -> VideoNastavenie(v.getInt("sirka"), v.getInt("vyska"), v.getInt("snimok"), v.getInt("fps"), v.getDouble("posun").toFloat(), v.optBoolean("i2v")) },
            )
        } }
    }
}

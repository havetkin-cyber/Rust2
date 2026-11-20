package sk.alexia.ai.obrazky

import sk.alexia.ai.Platforma

/** Most do stable-diffusion.cpp (libalexiasd.so, Vulkan). Volania musia ísť z jedného vlákna. */
object Sd {
    init { Platforma.nacitajKniznicu("alexiasd") }

    external fun nacitaj(cesta: String, vlakna: Int): Long
    external fun chyba(): String
    external fun verzia(h: Long): String
    external fun priebeh(): Float
    external fun zrus(h: Long)
    external fun generuj(h: Long, prompt: String, negativny: String, sirka: Int, vyska: Int, kroky: Int, cfg: Float, seed: Long, rozmer: IntArray): IntArray?
    external fun uvolni(h: Long)

    // vylepšenie fotky (Real-ESRGAN) – obsah sa nemení, len sa zväčší a doostrí
    external fun upscalerNacitaj(cesta: String, vlakna: Int): Long
    external fun upscalerNasobok(h: Long): Int
    external fun zvacsi(h: Long, argb: IntArray, sirka: Int, vyska: Int, rozmer: IntArray): IntArray?
    external fun upscalerUvolni(h: Long)

    // modely z viacerých súborov (Z-Image, FLUX…) a generovanie so zvoleným samplerom
    external fun nacitajViac(kluce: Array<String>, hodnoty: Array<String>, vlakna: Int, fa: Boolean): Long
    external fun generujEx(h: Long, prompt: String, negativny: String, sirka: Int, vyska: Int, kroky: Int, cfg: Float, seed: Long, metoda: String, rozmer: IntArray): IntArray?
}

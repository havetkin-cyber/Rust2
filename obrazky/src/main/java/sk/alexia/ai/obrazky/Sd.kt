package sk.alexia.ai.obrazky

/** Most do stable-diffusion.cpp (libalexiasd.so). Volania musia ísť z jedného vlákna. */
object Sd {
    init { System.loadLibrary("alexiasd") }

    external fun nacitaj(cesta: String, vlakna: Int): Long
    external fun chyba(): String
    external fun verzia(h: Long): String
    /** 0..1 – priebeh aktuálneho obrázka (dá sa čítať z iného vlákna) */
    external fun priebeh(): Float
    external fun zrus(h: Long)
    /** ARGB pixely alebo null; skutočná šírka a výška sa zapíšu do [rozmer] */
    external fun generuj(h: Long, prompt: String, negativny: String, sirka: Int, vyska: Int, kroky: Int, cfg: Float, seed: Long, rozmer: IntArray): IntArray?
    external fun uvolni(h: Long)

    // vylepšenie fotky (Real-ESRGAN) – obsah sa nemení, len sa zväčší
    external fun upscalerNacitaj(cesta: String, vlakna: Int): Long
    external fun upscalerNasobok(h: Long): Int
    external fun zvacsi(h: Long, argb: IntArray, sirka: Int, vyska: Int, rozmer: IntArray): IntArray?
    external fun upscalerUvolni(h: Long)
    external fun nacitajViac(kluce: Array<String>, hodnoty: Array<String>, vlakna: Int, fa: Boolean): Long
    external fun generujEx(h: Long, prompt: String, negativny: String, sirka: Int, vyska: Int, kroky: Int, cfg: Float, seed: Long, metoda: String, rozmer: IntArray): IntArray?
    /** úprava obrázka z appky podľa predlohy (argb) a nového popisu; sila 0..1 */
    external fun generujZPredlohy(h: Long, prompt: String, negativny: String, sirka: Int, vyska: Int, kroky: Int, cfg: Float, seed: Long, metoda: String, argb: IntArray, sila: Float, rozmer: IntArray): IntArray?
    /** nekonečná textúra pre ďalšie obrázky (generujEx / generujZPredlohy) */
    external fun nastavTexturu(zapnut: Boolean)
    /** video AI (Wan): RGB snímky za sebou; rozmer = {šírka, výška, snímok, fps} */
    external fun generujVideo(h: Long, prompt: String, negativny: String, sirka: Int, vyska: Int, snimok: Int, kroky: Int, cfg: Float, posun: Float, seed: Long, metoda: String, predloha: IntArray?, rozmer: IntArray): ByteArray?
}

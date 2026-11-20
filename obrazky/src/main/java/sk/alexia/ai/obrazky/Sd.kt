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
}

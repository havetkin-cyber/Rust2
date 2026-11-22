package sk.alexia.ai

/** Most do llama.cpp (libalexiaai.so, Vulkan). Všetky volania musia ísť z jedného vlákna – stará sa o to [ChatJadro]. */
object Llama {
    init { Platforma.nacitajKniznicu("alexiaai") }

    /** spätné volanie pri každom kúsku textu (UTF-8); false = zastaviť */
    fun interface Odpoved { fun token(b: ByteArray): Boolean }

    external fun nacitaj(cesta: String, nCtx: Int, vlakna: Int): Long
    external fun chyba(): String
    external fun popis(h: Long): String
    external fun vzorkovanie(h: Long, teplota: Float, topP: Float, topK: Int, minP: Float, opakovanie: Float)
    /** 0 hotovo, 1 zastavené, 2 limit tokenov, -1 chyba, -2 rozhovor je dlhší ako kontext */
    external fun generuj(h: Long, roly: Array<String>, obsahy: Array<String>, maxTokenov: Int, cb: Odpoved): Int
    /** LoRA adaptéry (GGUF) pre chatový model; vráti "" alebo text chyby */
    external fun nastavLory(h: Long, cesty: Array<String>, sily: FloatArray): String
    external fun zastav(h: Long)
    external fun zabudni(h: Long)
    external fun uvolni(h: Long)
}

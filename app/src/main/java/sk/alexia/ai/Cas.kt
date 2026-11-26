package sk.alexia.ai

/** časy pre časovač („⏱ 1:23“) a údaje pod obrázkom */
object Cas {
    fun text(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }
}

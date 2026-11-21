package sk.alexia.ai

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Čítanie hlavičky GGUF – zistí architektúru modelu (napr. qwen3) a veľkosť embeddingu, bez načítania váh. */
object Gguf {
    class Info(val architektura: String, val embedding: Long?, val nazov: String)

    private class Citac(i: InputStream) {
        private val d = DataInputStream(i.buffered(1 shl 16))
        private fun le(n: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(n).also { d.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
        fun u32() = le(4).int.toLong() and 0xFFFFFFFFL
        fun u64() = le(8).long
        fun str(): String { val n = u64(); require(n in 0..1_000_000); return String(ByteArray(n.toInt()).also { d.readFully(it) }, Charsets.UTF_8) }
        fun preskoc(n: Long) { var z = n; while (z > 0) { val s = d.skip(z); if (s <= 0) { d.readByte(); z-- } else z -= s } }
        /** hodnota podľa typu; veľké polia sa preskočia */
        fun hodnota(t: Long): Any? = when (t.toInt()) {
            0, 1, 7 -> le(1).get().toLong()
            2, 3 -> le(2).short.toLong()
            4, 5 -> le(4).int.toLong()
            6 -> le(4).float
            8 -> str()
            9 -> { val et = u32(); val n = u64(); repeat(n.toInt().coerceAtLeast(0)) { hodnota(et) }; null }
            10, 11 -> le(8).long
            12 -> le(8).double
            else -> throw IllegalStateException("neznámy typ $t")
        }
    }

    fun info(f: File): Info? = runCatching {
        f.inputStream().use { i ->
            val c = Citac(i)
            @Suppress("UNUSED_VARIABLE") val m = c.u32()     // "GGUF"
            if (m != 0x46554747L) return null
            c.u32(); c.u64()                                   // verzia, počet tenzorov
            val kv = c.u64()
            val mapa = HashMap<String, Any?>()
            for (k in 0 until kv) {
                val kluc = c.str(); val t = c.u32()
                mapa[kluc] = c.hodnota(t)
                val arch = mapa["general.architecture"] as? String
                if (arch != null && mapa.containsKey("$arch.embedding_length")) break
            }
            val arch = mapa["general.architecture"] as? String ?: return null
            Info(arch, (mapa["$arch.embedding_length"] as? Long), mapa["general.name"] as? String ?: f.nameWithoutExtension)
        }
    }.getOrNull()
}

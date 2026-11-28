package sk.alexia.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Oddelenie reči od hudby a zvukov (Demucs htdemucs, Meta, MIT) – rovnaký model a výpočet ako AI stopy v ALEXIA DJ,
 * prepísaný do Kotlinu. Beží v samostatnom procese (veľa pamäte, pád nezhodí appku).
 * Argumenty: model.onnx vstup.s16 (stereo 44,1 kHz) pozadie.s16 hlasy.s16
 * Stdout: „P i n“ = hotový úsek i z n.
 */
object DemucsProces {
    private const val L = 343980; private const val HL = 1024; private const val N = 4096
    private const val F = 2048; private const val T = 336; private const val S = 4

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            spusti(File(args[0]), File(args[1]), File(args[2]), File(args[3]))
            kotlin.system.exitProcess(0)
        } catch (t: Throwable) {
            System.err.println("demucs: chyba $t"); t.printStackTrace(); kotlin.system.exitProcess(1)
        }
    }

    // ---------- FFT 4096 (radix 2) ----------
    private val okno = DoubleArray(N) { 0.5 - 0.5 * cos(2 * Math.PI * it / N) }
    private val wr = DoubleArray(N / 2) { cos(-2 * Math.PI * it / N) }
    private val wi = DoubleArray(N / 2) { sin(-2 * Math.PI * it / N) }
    private val rev = IntArray(N).also { r -> var lg = 0; while ((1 shl lg) < N) lg++; for (i in 0 until N) { var x = 0; for (b in 0 until lg) if ((i shr b) and 1 == 1) x = x or (1 shl (lg - 1 - b)); r[i] = x } }

    private fun fft(re: DoubleArray, im: DoubleArray, inv: Boolean) {
        for (i in 0 until N) { val j = rev[i]; if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= N) {
            val k = N / len; val h = len / 2
            var i = 0
            while (i < N) {
                for (j in 0 until h) {
                    val c = wr[j * k]; val s = if (inv) -wi[j * k] else wi[j * k]
                    val a = i + j; val b = a + h
                    val vr = re[b] * c - im[b] * s; val vi = re[b] * s + im[b] * c
                    re[b] = re[a] - vr; im[b] = im[a] - vi; re[a] += vr; im[a] += vi
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun odraz(i0: Long, n: Long): Long {
        if (n <= 1) return 0
        var i = i0
        while (i < 0 || i >= n) { if (i < 0) i = -i; if (i >= n) i = 2 * (n - 1) - i }
        return i
    }

    /** demucs _spec pre jeden kanál dĺžky L → zr/zi [F][T] */
    private fun spec(x: FloatArray, zr: DoubleArray, zi: DoubleArray) {
        val pad = (HL / 2 * 3).toLong(); val dlzka1 = 2 * pad + T.toLong() * HL
        val re = DoubleArray(N); val im = DoubleArray(N); val norm = 1.0 / sqrt(N.toDouble())
        for (t in 0 until T) {
            val ram = (t + 2).toLong() * HL
            for (k in 0 until N) {
                val j = odraz(ram + k - N / 2, dlzka1)
                re[k] = x[odraz(j - pad, L.toLong()).toInt()] * okno[k]; im[k] = 0.0
            }
            fft(re, im, false)
            for (k in 0 until F) { zr[k * T + t] = re[k] * norm; zi[k * T + t] = im[k] * norm }
        }
    }

    /** demucs _ispec: [F][T] → signál dĺžky L */
    private fun ispec(zr: FloatArray, zi: FloatArray, zOff: Int, y: DoubleArray) {
        val tp = T + 4; val pad = (HL / 2 * 3).toLong(); val le = HL.toLong() * ((L + HL - 1) / HL) + 2 * pad
        val dl = N + HL * (tp - 1)
        val acc = DoubleArray(dl); val ws = DoubleArray(dl)
        val re = DoubleArray(N); val im = DoubleArray(N); val norm = sqrt(N.toDouble()) / N
        for (t in 0 until tp) {
            val ts = t - 2
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            if (ts in 0 until T) {
                for (k in 0 until F) { re[k] = zr[zOff + k * T + ts].toDouble(); im[k] = zi[zOff + k * T + ts].toDouble() }
                for (k in 1 until N / 2) { re[N - k] = re[k]; im[N - k] = -im[k] }
            }
            fft(re, im, true)
            val o = t * HL
            for (k in 0 until N) { acc[o + k] += re[k] * norm * okno[k]; ws[o + k] += okno[k] * okno[k] }
        }
        for (i in 0 until L) {
            val j = i + pad + N / 2
            y[i] = if (j < dl && j < le + N / 2) { val w = ws[j.toInt()]; if (w > 1e-11) acc[j.toInt()] / w else 0.0 } else 0.0
        }
    }

    private fun spusti(model: File, vstup: File, pozadie: File, hlasy: File) {
        // vstup: stereo int16 44,1 kHz
        val raw = ByteBuffer.wrap(vstup.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = raw.remaining() / 2
        val x0 = ShortArray(n); val x1 = ShortArray(n)
        for (i in 0 until n) { x0[i] = raw.get(2 * i); x1[i] = raw.get(2 * i + 1) }
        var s = 0.0; var s2 = 0.0
        for (i in 0 until n) { val m = 0.5 * (x0[i] + x1[i]) / 32768.0; s += m; s2 += m * m }
        val mu = s / maxOf(1, n); val sd = sqrt(maxOf(0.0, s2 / maxOf(1, n) - mu * mu) * n / maxOf(1, n - 1)) + 1e-8
        fun vzorka(k: Int, i: Int) = (((if (k == 0) x0[i] else x1[i]) / 32768.0 - mu) / sd).toFloat()

        val krok = (0.75 * L).toInt()
        val offsety = generateSequence(0) { it + krok }.takeWhile { it < n }.toList()
        val acc = Array(S) { Array(2) { FloatArray(L) } }; val vahy = FloatArray(L)
        val bg = ShortArray(n * 2); val voc = ShortArray(n * 2)

        val env = OrtEnvironment.getEnvironment()
        val o = OrtSession.SessionOptions()
        o.setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() - 1))
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        o.setCPUArenaAllocator(false); o.setMemoryPatternOptimization(false)
        val sess = env.createSession(model.path, o)
        val mix = FloatArray(2 * L); val mag = FloatArray(4 * F * T)
        val ch = FloatArray(L); val zr = DoubleArray(F * T); val zi = DoubleArray(F * T)
        val y = DoubleArray(L)
        val spekR = FloatArray(F * T); val spekI = FloatArray(F * T)
        println("P 0 ${offsety.size}"); System.out.flush()
        for ((i, off) in offsety.withIndex()) {
            val dl = minOf(L, n - off); val zac = off - (L - dl) / 2
            for (k in 0 until 2) {
                for (j in 0 until L) { val q = zac + j; ch[j] = if (q in 0 until n) vzorka(k, q) else 0f }
                System.arraycopy(ch, 0, mix, k * L, L)
                spec(ch, zr, zi)
                for (f in 0 until F) for (t in 0 until T) {
                    mag[((k * 2 + 0) * F + f) * T + t] = zr[f * T + t].toFloat()
                    mag[((k * 2 + 1) * F + f) * T + t] = zi[f * T + t].toFloat()
                }
            }
            val (spek, cas) = OnnxTensor.createTensor(env, FloatBuffer.wrap(mix), longArrayOf(1, 2, L.toLong())).use { tm ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(mag), longArrayOf(1, 4, F.toLong(), T.toLong())).use { tg ->
                    sess.run(mapOf("mix" to tm, "mag" to tg)).use { out ->
                        val a = (out.get(0) as OnnxTensor).floatBuffer; val b = (out.get(1) as OnnxTensor).floatBuffer
                        FloatArray(a.remaining()).also { a.get(it) } to FloatArray(b.remaining()).also { b.get(it) }
                    }
                }
            }
            val posun = (L - dl) / 2
            for (st in 0 until S) for (k in 0 until 2) {
                val baseRe = (st * 4 + k * 2 + 0) * F * T; val baseIm = (st * 4 + k * 2 + 1) * F * T
                System.arraycopy(spek, baseRe, spekR, 0, F * T); System.arraycopy(spek, baseIm, spekI, 0, F * T)
                ispec(spekR, spekI, 0, y)
                val ct = (st * 2 + k) * L
                val a = acc[st][k]
                for (j in 0 until dl) {
                    val w = (if (j < L / 2) j + 1 else L - j).toFloat() / (L / 2)
                    a[j] += w * (y[j + posun] + cas[ct + j + posun]).toFloat()
                    if (st == 0 && k == 0) vahy[j] += w
                }
            }
            val fin = (if (i + 1 < offsety.size) offsety[i + 1] else n) - off
            for (j in 0 until fin) for (k in 0 until 2) {
                val w = maxOf(1e-6f, vahy[j])
                var sucet = 0.0
                for (st in 0 until 3) sucet += acc[st][k][j]
                val pozad = sucet / w * sd + mu
                val hl = acc[3][k][j] / w * sd + mu
                bg[(off + j) * 2 + k] = (pozad.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
                voc[(off + j) * 2 + k] = (hl.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            }
            for (st in 0 until S) for (k in 0 until 2) { val a = acc[st][k]; System.arraycopy(a, fin, a, 0, L - fin); java.util.Arrays.fill(a, L - fin, L, 0f) }
            System.arraycopy(vahy, fin, vahy, 0, L - fin); java.util.Arrays.fill(vahy, L - fin, L, 0f)
            println("P ${i + 1} ${offsety.size}"); System.out.flush()
        }
        sess.close()
        fun zapis(f: File, d: ShortArray) {
            val b = ByteBuffer.allocate(d.size * 2).order(ByteOrder.LITTLE_ENDIAN); b.asShortBuffer().put(d); f.writeBytes(b.array())
        }
        zapis(pozadie, bg); zapis(hlasy, voc)
    }
}

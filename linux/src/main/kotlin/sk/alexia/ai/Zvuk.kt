package sk.alexia.ai

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Úprava hlasu bez nového modelu (rovnaký súbor je v PC aj mobilnej appke):
 *  - výška tónu cez TD-PSOLA (farba hlasu – formanty – ostane),
 *  - hrúbka = posun farby: zvuk sa prehrá s inou vzorkovacou frekvenciou (posunie tón aj farbu)
 *    a tón sa predtým PSOLA-ou vráti, takže sa zmení len farba; dĺžku vyrovná rýchlosť pri tvorbe reči,
 *  - plnosť = jemné zvýraznenie basov (low-shelf).
 */
object Zvuk {
    /** posun farby: hrúbka > 1 = hrubší (formanty nižšie) */
    fun formant(hrubka: Float) = 1f / hrubka.coerceIn(0.8f, 1.25f)

    /**
     * [vyska] požadovaná výška tónu, [hrubka] hrúbka; vráti zvuk pripravený na prehratie pri frekvencii sr * formant(hrubka).
     */
    fun uprav(x: FloatArray, sr: Int, vyska: Float, hrubka: Float, plnost: Float): FloatArray {
        val f = formant(hrubka)
        val q = vyska / f                       // tón pred prehratím (prehratie ho vynásobí f)
        var y = if (abs(q - 1f) > 0.01f) psola(x, sr, q) else x
        if (plnost > 0.01f) y = basy(y, sr, plnost)
        return y
    }

    // ---------------- TD-PSOLA ----------------
    private fun psola(x: FloatArray, sr: Int, q: Float): FloatArray {
        val n = x.size
        if (n < sr / 20) return x
        val minP = sr / 400; val maxP = sr / 70
        // 1) základná perióda po rámcoch (autokorelácia), 0 = neznelé
        val krok = sr / 100
        val per = IntArray(n / krok + 1)
        val okno = maxP * 2
        for (r in per.indices) {
            val z = r * krok
            if (z + okno + maxP >= n) { per[r] = 0; continue }
            var e = 0.0; for (i in z until z + okno) e += x[i] * x[i]
            if (e / okno < 1e-5) { per[r] = 0; continue }
            var najT = 0; var najK = 0.0
            for (t in minP..maxP) {
                var k = 0.0; var e2 = 0.0
                var i = z
                while (i < z + okno) { k += x[i] * x[i + t]; e2 += x[i + t] * x[i + t]; i += 2 }
                val nk = k / sqrt(e / 2 * e2 + 1e-12)
                if (nk > najK) { najK = nk; najT = t }
            }
            per[r] = if (najK > 0.45) najT else 0
        }
        fun periodaV(i: Int): Int { val r = (i / krok).coerceIn(0, per.lastIndex); return per[r] }
        // 2) analytické značky: v znelých častiach po perióde (doladené na maximum), inak každých 10 ms
        val znacky = ArrayList<Int>()
        var t = 0
        while (t < n) {
            znacky += t
            val p = periodaV(t)
            if (p > 0) {
                val ciel = t + p
                var naj = ciel; var v = -1f
                for (i in max(t + minP, ciel - p / 4)..min(n - 1, ciel + p / 4)) if (abs(x[i]) > v) { v = abs(x[i]); naj = i }
                t = max(naj, t + minP)
            } else t += krok
        }
        // 3) syntéza: výstupné značky s periódou / q, pre každú najbližšia analytická, okno 2 periódy (Hann), sčítanie
        val y = FloatArray(n); val w = FloatArray(n)
        var ts = 0.0
        var ai = 0
        while (ts < n) {
            val tsi = ts.toInt()
            while (ai + 1 < znacky.size && abs(znacky[ai + 1] - tsi) <= abs(znacky[ai] - tsi)) ai++
            val ta = znacky[ai]
            val p = periodaV(ta)
            val pol = if (p > 0) p else krok
            for (k in -pol until pol) {
                val zi = ta + k; val ci = tsi + k
                if (zi < 0 || zi >= n || ci < 0 || ci >= n) continue
                val h = 0.5f + 0.5f * cos(PI * k / pol).toFloat()
                y[ci] += x[zi] * h; w[ci] += h
            }
            ts += if (p > 0) p / q.toDouble() else krok.toDouble()
        }
        for (i in 0 until n) if (w[i] > 0.1f) y[i] /= w[i]
        return y
    }

    // ---------------- basy (low-shelf ~180 Hz, až +7 dB) ----------------
    private fun basy(x: FloatArray, sr: Int, plnost: Float): FloatArray {
        val gain = 7.0 * plnost.coerceIn(0f, 1f)
        val a = Math.pow(10.0, gain / 40)
        val w0 = 2 * PI * 180 / sr
        val cs = cos(w0); val sn = sin(w0)
        val alpha = sn / 2 * sqrt((a + 1 / a) * (1 / 0.8 - 1) + 2)
        val sa = 2 * sqrt(a) * alpha
        val b0 = a * ((a + 1) - (a - 1) * cs + sa)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cs)
        val b2 = a * ((a + 1) - (a - 1) * cs - sa)
        val a0 = (a + 1) + (a - 1) * cs + sa
        val a1 = -2 * ((a - 1) + (a + 1) * cs)
        val a2 = (a + 1) + (a - 1) * cs - sa
        val y = FloatArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        var spicka = 0f
        for (i in x.indices) {
            val v = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x[i].toDouble(); y2 = y1; y1 = v
            y[i] = v.toFloat(); spicka = max(spicka, abs(y[i]))
        }
        // aby basy neprebudili (orezanie)
        if (spicka > 0.98f) { val k = 0.98f / spicka; for (i in y.indices) y[i] *= k }
        return y
    }

    fun naShort(f: FloatArray) = ShortArray(f.size) { (f[it].coerceIn(-1f, 1f) * 32767).roundToInt().toShort() }
}


/** Mix hlasov Supertonic: voice.bin = hlavička (int64: N, 50, 256, N, 8, 16) + štýly ttl a dp; pridá hovoriaceho N = mix A a B */
object MixHlasov {
    fun vytvor(zdroj: java.io.File, ciel: java.io.File, a: Int, b: Int, pomer: Float): Int {
        val bb = java.nio.ByteBuffer.wrap(zdroj.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val n1 = bb.long.toInt(); val d1 = bb.long.toInt(); val e1 = bb.long.toInt()
        val n2 = bb.long.toInt(); val d2 = bb.long.toInt(); val e2 = bb.long.toInt()
        val ttl = FloatArray(n1 * d1 * e1) { bb.float }
        val dp = FloatArray(n2 * d2 * e2) { bb.float }
        val w = pomer.coerceIn(0f, 1f)
        val out = java.nio.ByteBuffer.allocate(48 + (ttl.size + d1 * e1 + dp.size + d2 * e2) * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putLong((n1 + 1).toLong()).putLong(d1.toLong()).putLong(e1.toLong())
        out.putLong((n2 + 1).toLong()).putLong(d2.toLong()).putLong(e2.toLong())
        ttl.forEach { out.putFloat(it) }
        val ta = a.coerceIn(0, n1 - 1) * d1 * e1; val tb = b.coerceIn(0, n1 - 1) * d1 * e1
        for (i in 0 until d1 * e1) out.putFloat((1 - w) * ttl[ta + i] + w * ttl[tb + i])
        dp.forEach { out.putFloat(it) }
        val pa = a.coerceIn(0, n2 - 1) * d2 * e2; val pb = b.coerceIn(0, n2 - 1) * d2 * e2
        for (i in 0 until d2 * e2) out.putFloat((1 - w) * dp[pa + i] + w * dp[pb + i])
        ciel.writeBytes(out.array())
        return n1   // index nového hovoriaceho
    }
}

package sk.alexia.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Pery v dabingu (experimentálne): ústa hlavnej (najväčšej) tváre v zábere sa prekreslia podľa dabingu (Wav2Lip).
 * Len keď práve znie dabing – inak ostane pôvodný obraz. Video sa spracuje pri 25 snímkach/s (na tom je Wav2Lip trénovaný).
 * Argumenty: priecinokModelov video dabing.f32(16 kHz mono) ffmpeg vystup.mp4(bez zvuku)
 * Stdout: „P i n“.
 */
object PeryProces {
    private const val FPS = 25

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            spusti(File(args[0]), File(args[1]), File(args[2]), args[3], File(args[4]))
            kotlin.system.exitProcess(0)
        } catch (t: Throwable) {
            System.err.println("pery: chyba $t"); t.printStackTrace(); kotlin.system.exitProcess(1)
        }
    }

    private fun spusti(modely: File, video: File, dabF: File, ff: String, vystup: File) {
        // rozmery videa
        val info = ProcessBuilder(ff, "-hide_banner", "-i", video.path).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
        val m = Regex("Video:.*?(\\d{2,5})x(\\d{2,5})").find(info) ?: throw IllegalStateException("rozmer videa")
        var w = m.groupValues[1].toInt(); var h = m.groupValues[2].toInt()
        if (w > 1280) { h = (h * 1280.0 / w / 2).roundToInt() * 2; w = 1280 }
        w = w / 2 * 2; h = h / 2 * 2
        val dlzka = Regex("Duration: (\\d+):(\\d+):([0-9.]+)").find(info)?.let { it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 + it.groupValues[3].toDouble() } ?: 0.0
        val snimok = maxOf(1, (dlzka * FPS).toInt())

        // dabing → mel (rovnako ako pri speve)
        val b = ByteBuffer.wrap(dabF.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val zvuk = FloatArray(b.remaining()).also { b.get(it) }
        val mel = SpevProces.melSpektrogram(zvuk)
        val t = mel[0].size
        fun hovori(i: Int): Boolean {
            val a = (i * 16000L / FPS).toInt(); val c = minOf(zvuk.size, ((i + 1) * 16000L / FPS).toInt())
            if (a >= c) return false
            var e = 0.0; for (k in a until c) e += zvuk[k] * zvuk[k]
            return e / (c - a) > 2e-4
        }

        val env = OrtEnvironment.getEnvironment()
        val opt = OrtSession.SessionOptions().apply { setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() - 1)) }
        val yolo = env.createSession(File(modely, "yoloface_8n.onnx").path, opt)
        val lip = env.createSession(File(modely, "wav2lip_gan_96.onnx").path, opt)
        val mena = lip.inputNames.toList()
        val menoZvuku = mena.firstOrNull { n -> lip.inputInfo[n]!!.info.toString().contains("80") } ?: mena.first()
        val menoTvare = mena.first { it != menoZvuku }

        val dec = ProcessBuilder(ff, "-v", "error", "-i", video.path, "-an", "-vf", "fps=$FPS,scale=$w:$h", "-f", "rawvideo", "-pix_fmt", "rgb24", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val enc = ProcessBuilder(ff, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${w}x$h", "-r", "$FPS", "-i", "-",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p", vystup.path).redirectErrorStream(true).start()
        Thread { enc.inputStream.bufferedReader().forEachLine { println("pery-enc: $it") } }.apply { isDaemon = true }.start()

        val ram = ByteArray(w * h * 3)
        var box: FloatArray? = null; var bezTvare = 0
        val melVstup = FloatArray(80 * 16)
        DataInputStream(dec.inputStream.buffered(ram.size * 2)).use { vstup ->
            enc.outputStream.buffered(1 shl 20).use { out ->
                var i = 0
                while (true) {
                    try { vstup.readFully(ram) } catch (e: Exception) { break }
                    // tvár: každých 5 snímok nanovo, medzi tým vyhladená poloha
                    if (i % 5 == 0) {
                        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                        val px = IntArray(w * h) { k -> ((ram[3 * k].toInt() and 255) shl 16) or ((ram[3 * k + 1].toInt() and 255) shl 8) or (ram[3 * k + 2].toInt() and 255) }
                        img.setRGB(0, 0, w, h, px, 0, w)
                        val nb = runCatching { SpevProces.najdiTvar(env, yolo, img) }.getOrNull()
                        if (nb != null && (nb[2] - nb[0]) > 48) {
                            val p0 = box
                            box = if (p0 == null) nb else FloatArray(4) { k -> p0[k] * 0.6f + nb[k] * 0.4f }; bezTvare = 0
                        } else if (++bezTvare > 2) box = null
                    }
                    val bx = box
                    if (bx != null && hovori(i)) {
                        val strana = (maxOf(bx[2] - bx[0], bx[3] - bx[1]) * 1.05f).roundToInt().coerceIn(48, minOf(w, h))
                        val cx = (bx[0] + bx[2]) / 2; val cy = (bx[1] + bx[3]) / 2 + strana * 0.06f
                        val x0 = (cx - strana / 2f).roundToInt().coerceIn(0, w - strana); val y0 = (cy - strana / 2f).roundToInt().coerceIn(0, h - strana)
                        val vyrez = BufferedImage(strana, strana, BufferedImage.TYPE_INT_RGB)
                        for (y in 0 until strana) for (x in 0 until strana) {
                            val k = ((y0 + y) * w + x0 + x) * 3
                            vyrez.setRGB(x, y, ((ram[k].toInt() and 255) shl 16) or ((ram[k + 1].toInt() and 255) shl 8) or (ram[k + 2].toInt() and 255))
                        }
                        val tvar = SpevProces.pripravTvar(SpevProces.zmen(vyrez, 96, 96))
                        var od = (i * (80.0 / FPS)).toInt(); if (od + 16 > t) od = maxOf(0, t - 16)
                        for (mm in 0 until 80) for (k in 0 until 16) melVstup[mm * 16 + k] = mel[mm].getOrElse(od + k) { -4f }
                        val vysl = OnnxTensor.createTensor(env, FloatBuffer.wrap(melVstup), longArrayOf(1, 1, 80, 16)).use { mt ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(tvar), longArrayOf(1, 6, 96, 96)).use { tt ->
                                lip.run(mapOf(menoZvuku to mt, menoTvare to tt)).use { r -> (r[0] as OnnxTensor).floatBuffer.let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } } }
                            }
                        }
                        val usta = BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB)
                        for (y in 0 until 96) for (x in 0 until 96) {
                            fun c(ch: Int) = (vysl[ch * 9216 + y * 96 + x].coerceIn(0f, 1f) * 255).roundToInt()
                            usta.setRGB(x, y, (c(2) shl 16) or (c(1) shl 8) or c(0))
                        }
                        val vacsie = SpevProces.zmen(usta, strana, strana)
                        val maska = SpevProces.maskaUst(strana)
                        for (y in 0 until strana) for (x in 0 until strana) {
                            val a = maska[y * strana + x]; if (a <= 0f) continue
                            val n = vacsie.getRGB(x, y); val k = ((y0 + y) * w + x0 + x) * 3
                            for ((j, sh) in listOf(16, 8, 0).withIndex()) {
                                val o = ram[k + j].toInt() and 255
                                ram[k + j] = (o * (1 - a) + ((n shr sh) and 255) * a).roundToInt().coerceIn(0, 255).toByte()
                            }
                        }
                    }
                    out.write(ram)
                    i++
                    if (i % 25 == 0) { println("P $i $snimok"); System.out.flush() }
                }
            }
        }
        dec.waitFor(); val kod = enc.waitFor()
        if (kod != 0 || !vystup.exists()) throw IllegalStateException("kódovanie videa zlyhalo")
    }
}

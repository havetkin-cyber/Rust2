package sk.alexia.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prepis reči pre dabing v samostatnom procese (rovnako ako spev): ak natívny Whisper spadne
 * alebo minie pamäť, appka ostane bežať a chyba sa zapíše do logu.
 * Argumenty: sherpaDir modelDir predpona(turbo|small) zvuk.f32 useky.txt vystup.txt
 * useky.txt = riadky „od po“ v sekundách; vystup.txt = riadky „index<TAB>text“. Stdout: „P i n“.
 */
object PrepisProces {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val (sherpa, modelDir, pred, zvukCesta, usekyCesta) = args.toList()
            val vystup = File(args[5])
            System.setProperty("sherpa_onnx.native.path", sherpa)
            val sr = 16000
            val b = ByteBuffer.wrap(File(zvukCesta).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val x = FloatArray(b.remaining()).also { b.get(it) }
            val useky = File(usekyCesta).readLines().filter { it.isNotBlank() }.map { l -> l.split(' ').let { it[0].toDouble() to it[1].toDouble() } }
            val d = File(modelDir)
            val w = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig.builder()
                .setEncoder(File(d, "$pred-encoder.int8.onnx").path)
                .setDecoder(File(d, "$pred-decoder.int8.onnx").path)
                .setLanguage("").setTask("transcribe").build()
            val mc = com.k2fsa.sherpa.onnx.OfflineModelConfig.builder()
                .setWhisper(w).setTokens(File(d, "$pred-tokens.txt").path)
                .setNumThreads((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)).build()
            println("prepis: načítavam Whisper $pred"); System.out.flush()
            val r = com.k2fsa.sherpa.onnx.OfflineRecognizer(com.k2fsa.sherpa.onnx.OfflineRecognizerConfig.builder().setOfflineModelConfig(mc).build())
            vystup.bufferedWriter().use { out ->
                useky.forEachIndexed { i, (od, po) ->
                    val a = (od * sr).toInt().coerceIn(0, x.size); val c = (po * sr).toInt().coerceIn(a, x.size)
                    val st = r.createStream()
                    st.acceptWaveform(x.copyOfRange(a, c), sr)
                    r.decode(st)
                    val t = r.getResult(st).text.replace('\n', ' ').replace('\t', ' ').trim()
                    st.release()
                    out.write("$i\t$t\n"); out.flush()
                    println("P $i ${useky.size}"); System.out.flush()
                }
            }
            r.release()
            kotlin.system.exitProcess(0)
        } catch (t: Throwable) {
            System.err.println("prepis: chyba " + t); t.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
}

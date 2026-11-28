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
            // rozpoznanie hovoriacich (kto hovorí kedy) – voliteľné: args[6] = pyannote segmentácia, args[7] = model hlasového odtlačku
            if (args.size >= 8 && File(args[6]).exists() && File(args[7]).exists()) runCatching {
                println("prepis: rozpoznávam hovoriacich"); System.out.flush()
                val vlakna = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
                val cfg = com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig.builder()
                    .setSegmentation(com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig.builder()
                        .setPyannote(com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig.builder().setModel(args[6]).build())
                        .setNumThreads(vlakna).build())
                    .setEmbedding(com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig.builder().setModel(args[7]).setNumThreads(vlakna).build())
                    .setClustering(com.k2fsa.sherpa.onnx.FastClusteringConfig.builder().setNumClusters(-1).setThreshold(0.6f).build())
                    .setMinDurationOn(0.3f).setMinDurationOff(0.5f).build()
                val sd = com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization(cfg)
                val seg = sd.process(x)
                File(vystup.path + ".hov").writeText(seg.joinToString("\n") { "${it.start} ${it.end} ${it.speaker}" })
                sd.release()
                println("prepis: hovoriacich ${seg.map { it.speaker }.distinct().size}"); System.out.flush()
            }.onFailure { System.err.println("prepis: rozpoznanie hovoriacich zlyhalo: $it") }
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

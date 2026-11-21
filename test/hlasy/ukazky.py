"""Vygeneruje rovnakú slovenskú vetu rôznymi hlasmi na porovnanie."""
import json, os, subprocess, sys, traceback, wave
import numpy as np

M, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
TEXT = ("Ahoj, ja som Alexia. Dnes je krásny deň, tak poďme spolu vymyslieť niečo zaujímavé. "
        "Čo by si chcel robiť? Môžem ti nakresliť obrázok, napísať kód alebo len tak pokecať.")


def zapis(meno, vzorky, sr):
    a = np.clip(np.asarray(vzorky, dtype=np.float32), -1, 1)
    with wave.open(os.path.join(OUT, meno + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(int(sr))
        w.writeframes((a * 32767).astype(np.int16).tobytes())
    print("OK", meno, round(len(a) / sr, 1), "s")


# 1) Supertonic 3 – všetkých 10 hovoriacich
try:
    import sherpa_onnx
    d = os.path.join(M, "sherpa-onnx-supertonic-3-tts-int8-2026-05-11")
    p = lambda n: os.path.join(d, n)
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
                duration_predictor=p("duration_predictor.int8.onnx"),
                text_encoder=p("text_encoder.int8.onnx"),
                vector_estimator=p("vector_estimator.int8.onnx"),
                vocoder=p("vocoder.int8.onnx"),
                tts_json=p("tts.json"),
                unicode_indexer=p("unicode_indexer.bin"),
                voice_style=p("voice.bin"),
            ),
            num_threads=4, provider="cpu"))
    tts = sherpa_onnx.OfflineTts(cfg)
    print("Supertonic hovoriaci:", tts.num_speakers)
    for f in os.listdir(d):
        if f.endswith((".json", ".txt", ".md")) and f != "tts.json":
            print(f, open(p(f), errors="ignore").read()[:1500])
    for sid in range(max(tts.num_speakers, 10)):
        gc = sherpa_onnx.GenerationConfig()
        gc.sid = sid; gc.num_steps = 8; gc.speed = 1.0
        gc.extra["lang"] = "sk"
        a = tts.generate(TEXT, gc)
        zapis(f"supertonic-{sid}", a.samples, a.sample_rate)
except Exception:
    traceback.print_exc()

# 2) Lili cez Piper (ako v appke)
try:
    j = json.load(open(f"{M}/lili.onnx.json"))
    j["phoneme_id_map"] = {k: v for k, v in j["phoneme_id_map"].items() if len(k) == 1}
    json.dump(j, open(f"{M}/lili.onnx.json", "w"), ensure_ascii=False)
    subprocess.run([f"{M}/piper/piper", "--model", f"{M}/lili.onnx", "--espeak_data", f"{M}/piper/espeak-ng-data",
                    "--output_file", f"{OUT}/lili.wav"], input=TEXT.encode(), check=True,
                   env={**os.environ, "LD_LIBRARY_PATH": f"{M}/piper"})
    print("OK lili")
except Exception:
    traceback.print_exc()

# 3) neurlang – fonémy cez goruut, potom priamo ONNX
try:
    import onnxruntime as ort
    j = json.load(open(f"{M}/neur.onnx.json"))
    print({k: v for k, v in j.items() if k != "phoneme_id_map"})
    idm = j["phoneme_id_map"]
    from pygoruut.pygoruut import Pygoruut
    g = Pygoruut()
    r = g.phonemize(language="Slovak", sentence=TEXT)
    words = getattr(r, "Words", None) or getattr(r, "words", [])
    ipa = " ".join((getattr(w, "Phonetic", None) or getattr(w, "phonetic", "")) +
                   (getattr(w, "PosPunct", None) or "") for w in words) or str(r)
    print("IPA:", ipa)
    pad, bos, eos = idm.get("_", [0])[0], idm.get("^", [1])[0], idm.get("$", [2])[0]
    ids, chyba = [bos, pad], set()
    for ch in ipa:
        if ch in idm:
            ids += idm[ch] + [pad]
        else:
            chyba.add(ch)
    ids.append(eos)
    print("chýbajúce znaky:", chyba)
    inf = j.get("inference", {})
    s = ort.InferenceSession(f"{M}/neur.onnx")
    feed = {"input": np.array([ids], dtype=np.int64),
            "input_lengths": np.array([len(ids)], dtype=np.int64),
            "scales": np.array([inf.get("noise_scale", 0.667), inf.get("length_scale", 1.0),
                                inf.get("noise_w", 0.8)], dtype=np.float32)}
    names = [i.name for i in s.get_inputs()]
    if "sid" in names:
        feed["sid"] = np.array([0], dtype=np.int64)
    out = s.run(None, {k: v for k, v in feed.items() if k in names})[0].squeeze()
    zapis("neurlang", out, j.get("audio", {}).get("sample_rate", 22050))
except Exception:
    traceback.print_exc()

# Alexia AI – 3D model z obrázka (TripoSR, MIT licencia, Stability AI + Tripo AI)
# Spúšťa ho appka: python alexia3d.py <obrazok.png> <vystup_bez_pripony> <vlakna> <rozlisenie>
# Výstup: <vystup>.glb a <vystup>.obj (farby vo vrcholoch), na stdout priebeh "PRIEBEH x" a nakoniec "HOTOVO pocet_vrcholov"
import os
import sys

TSR_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "TripoSR")
sys.path.insert(0, TSR_DIR)


def priebeh(x, text=""):
    print(f"PRIEBEH {x:.2f} {text}", flush=True)


def main():
    vstup, vystup = sys.argv[1], sys.argv[2]
    vlakna = int(sys.argv[3]) if len(sys.argv) > 3 else 4
    rozlisenie = int(sys.argv[4]) if len(sys.argv) > 4 else 256

    priebeh(0.02, "nacitavam")
    import numpy as np
    import torch
    from PIL import Image

    torch.set_num_threads(max(1, vlakna))
    from tsr.system import TSR
    from tsr.utils import resize_foreground

    model = TSR.from_pretrained(os.environ.get("ALEXIA_TSR_MODEL", "stabilityai/TripoSR"),
                                config_name="config.yaml", weight_name="model.ckpt")
    model.renderer.set_chunk_size(8192)
    model.to("cpu")
    priebeh(0.3, "obrazok")

    obr = Image.open(vstup).convert("RGBA")
    # bez priehľadnosti: pozadie = farba rohov (obrázky z appky majú jednoduché pozadie, sprity sú priehľadné)
    a = np.array(obr).astype(np.float32) / 255.0
    if a[:, :, 3].min() > 0.99:
        rohy = np.stack([a[0, 0, :3], a[0, -1, :3], a[-1, 0, :3], a[-1, -1, :3]]).mean(0)
        rozdiel = np.sqrt(((a[:, :, :3] - rohy) ** 2).sum(-1))
        a[:, :, 3] = np.clip((rozdiel - 0.12) / 0.10, 0, 1)
        obr = Image.fromarray((a * 255).astype(np.uint8), "RGBA")
    obr = resize_foreground(obr, 0.85)
    a = np.array(obr).astype(np.float32) / 255.0
    a = a[:, :, :3] * a[:, :, 3:4] + (1 - a[:, :, 3:4]) * 0.5
    obr = Image.fromarray((a * 255.0).astype(np.uint8))

    priebeh(0.4, "model")
    with torch.no_grad():
        kody = model([obr], device="cpu")
    priebeh(0.75, "siet")
    try:
        meshe = model.extract_mesh(kody, True, resolution=rozlisenie)
    except TypeError:
        meshe = model.extract_mesh(kody, resolution=rozlisenie)
    m = meshe[0]
    # vnútro von → otoč trojuholníky
    try:
        if m.is_watertight and m.volume < 0:
            m.invert()
    except Exception:
        pass
    priebeh(0.95, "ukladam")
    m.export(vystup + ".glb")
    m.export(vystup + ".obj")
    print(f"HOTOVO {len(m.vertices)}", flush=True)


if __name__ == "__main__":
    main()

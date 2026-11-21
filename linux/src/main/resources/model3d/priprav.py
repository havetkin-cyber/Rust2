# Alexia AI – úprava TripoSR po stiahnutí: torchmcubes (treba kompilovať) nahradí PyMCubes (hotové balíky pre Linux aj Windows)
import os
import sys

d = sys.argv[1]
p = os.path.join(d, "tsr", "models", "isosurface.py")
s = open(p, encoding="utf-8").read()
if "ALEXIA_MCUBES" not in s:
    nahrada = '''# ALEXIA_MCUBES: marching cubes cez PyMCubes namiesto torchmcubes
def marching_cubes(vol, thr):
    import mcubes
    import numpy as np
    import torch
    v, f = mcubes.marching_cubes(vol.detach().cpu().numpy(), thr)
    # torchmcubes vracia súradnice v opačnom poradí osí (kód nižšie ich otočí späť)
    v = np.ascontiguousarray(v[:, ::-1]).astype(np.float32)
    return torch.from_numpy(v), torch.from_numpy(f.astype(np.int64))
'''
    s = s.replace("from torchmcubes import marching_cubes", nahrada)
    open(p, "w", encoding="utf-8").write(s)
# tsr/utils.py: imageio a rembg (odstránenie pozadia) appka nepotrebuje – pozadie rieši sama
import re
u = os.path.join(d, "tsr", "utils.py")
t = open(u, encoding="utf-8").read()
for modul in ("imageio", "rembg"):
    t = re.sub(r"^import %s\s*$" % modul, "try:\n    import %s\nexcept ImportError:\n    %s = None" % (modul, modul), t, flags=re.M)
open(u, "w", encoding="utf-8").write(t)
print("OK")

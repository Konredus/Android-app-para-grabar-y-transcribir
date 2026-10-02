"""Capturas de tablet para Google Play (16:9, 1920×1080), con el mismo estilo que las del teléfono.
Recorta la barra de estado y la barra de tareas del emulador (2560×1600 → 2560×1440, 16:9) y la monta en un marco."""
import os, importlib.util
from PIL import Image, ImageDraw

SCR = r"C:\Users\Konra\AppData\Local\Temp\claude\C--Users-Konra-Desktop-Digital-Home-Casa-Digital-2026-Proyectos-2026-14-App-grabar-y-transcribir\68b4f5e2-d2c0-4d25-9853-6acd57fd56d3\scratchpad"
spec = importlib.util.spec_from_file_location("sa", os.path.join(SCR, "store_assets_lib.py"))
sa = importlib.util.module_from_spec(spec); spec.loader.exec_module(sa)
OUT = os.path.join(SCR, "store", "tablet"); os.makedirs(OUT, exist_ok=True)

SHOTS = [("t_welcome", "Tus palabras, para *siempre*"),
         ("t_home", "Graba con un toque, incluso sin *internet*"),
         ("t_detail", "Transcribe y separa cada *voz*"),
         ("t_lib", "Todas tus grabaciones, a un *toque*")]

def tablet(name, height):
    shot = Image.open(os.path.join(SCR, "shots", name + ".png")).convert("RGB").crop((0, 49, 2560, 1489))
    w = round(shot.width * height / shot.height)
    shot = sa.rounded(shot.resize((w, height), Image.LANCZOS), round(height * 0.03))
    pad = round(height * 0.018)
    frame = Image.new("RGBA", (w + 2 * pad, height + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, frame.width - 1, frame.height - 1], radius=round(height * 0.03) + pad, fill=(11, 20, 17, 255))
    frame.alpha_composite(shot, (pad, pad))
    return frame

for i, (name, text) in enumerate(SHOTS, 1):
    W, H = 1920, 1080
    can = sa.green(W, H, 0, 0, W, H * 1.4, 300, 120, 1800).convert("RGBA")
    bottom = sa.caption(can, text, 48, size=64, max_w=1600)
    tb = tablet(name, H - bottom - 70)
    sa.shadowed(can, tb, ((W - tb.width) // 2, bottom + 24), blur=24)
    can.convert("RGB").save(os.path.join(OUT, f"tablet-{i:02d}.png"))
print(sorted(os.listdir(OUT)))

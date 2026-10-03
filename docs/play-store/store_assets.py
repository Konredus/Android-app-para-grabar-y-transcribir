"""Gráficos de la ficha de Google Play para Verbapp: ícono 512×512, gráfico destacado 1024×500 y capturas 1080×1920.
Dibuja el ícono con las mismas formas y colores que los vectores de la app (ic_launcher_background/foreground)."""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = r"C:\Users\Konra\Desktop\Digital Home\Casa Digital 2026\Proyectos 2026\14-App grabar y transcribir"
SCR = r"C:\Users\Konra\AppData\Local\Temp\claude\C--Users-Konra-Desktop-Digital-Home-Casa-Digital-2026-Proyectos-2026-14-App-grabar-y-transcribir\68b4f5e2-d2c0-4d25-9853-6acd57fd56d3\scratchpad"
SRC = os.path.join(SCR, "book", "src")
OUT = os.path.join(SCR, "store")
FONT = os.path.join(REPO, "app", "src", "main", "assets", "fonts", "Outfit.ttf")
os.makedirs(os.path.join(OUT, "capturas"), exist_ok=True)

INK = (20, 35, 29); MINT = (207, 233, 220); WHITE = (255, 255, 255); SPARK = (205, 238, 221)
STOPS = [(0.0, (0x4E, 0x8C, 0x77)), (0.55, (0x2F, 0x6B, 0x58)), (1.0, (0x1B, 0x46, 0x38))]

def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f

def green(w, h, sx, sy, ex, ey, hx, hy, hr):
    """Degradado lineal de la marca (3 paradas) más el brillo radial blanco del ícono, en coordenadas de píxel."""
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float64) + 0.5
    dx, dy = ex - sx, ey - sy
    t = np.clip(((xs - sx) * dx + (ys - sy) * dy) / (dx * dx + dy * dy), 0, 1)
    img = np.zeros((h, w, 3))
    for (o0, c0), (o1, c1) in zip(STOPS, STOPS[1:]):
        m = (t >= o0) & (t <= o1)
        u = ((t - o0) / (o1 - o0))[m][:, None]
        img[m] = np.array(c0) * (1 - u) + np.array(c1) * u
    r = np.clip(np.hypot(xs - hx, ys - hy) / hr, 0, 1)
    a = (0x40 / 255.0) * (1 - r)
    img = img * (1 - a[..., None]) + 255 * a[..., None]
    return Image.fromarray(img.clip(0, 255).astype(np.uint8), "RGB")

def quad(p0, c, p1, n=48):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1])
            for t in (i / n for i in range(n + 1))]

def glyph(draw, tx):
    """Barras de onda (trazo redondeado de 5,5) y destello, como en ic_launcher_foreground. tx: viewport 108 → píxeles."""
    k = tx(1, 0)[0] - tx(0, 0)[0]
    w = 5.5 * k
    for x, y1, y2 in [(35, 50, 62), (44.2, 42, 70), (53.4, 46, 66), (62.4, 51, 61)]:
        (ax, ay), (bx, by) = tx(x, y1), tx(x, y2)
        draw.line([(ax, ay), (bx, by)], fill=WHITE, width=round(w))
        for cx, cy in [(ax, ay), (bx, by)]:
            draw.ellipse([cx - w / 2, cy - w / 2, cx + w / 2, cy + w / 2], fill=WHITE)
    pts = []
    for p0, c, p1 in [((66.5, 33.5), (67.2, 41.3), (75, 42)), ((75, 42), (67.2, 42.7), (66.5, 50.5)),
                      ((66.5, 50.5), (65.8, 42.7), (58, 42)), ((58, 42), (65.8, 41.3), (66.5, 33.5))]:
        pts += [tx(*q) for q in quad(p0, c, p1)]
    draw.polygon(pts, fill=SPARK)

# ---------- Ícono 512×512: el centro visible del ícono adaptativo (18..90 del viewport de 108) ----------
S = 2048; k = S / 72.0
tx = lambda x, y: ((x - 18) * k, (y - 18) * k)
icon = green(S, S, *tx(18, 0), *tx(90, 108), *tx(30, 22), 70 * k).convert("RGBA")
glyph(ImageDraw.Draw(icon), tx)
icon = icon.resize((512, 512), Image.LANCZOS)
icon.save(os.path.join(OUT, "icono-512.png"))

def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius=radius, fill=255)
    out = img.convert("RGBA"); out.putalpha(mask)
    return out

def phone(shot_key, height):
    """Captura con marco de teléfono oscuro y esquinas redondeadas, a la altura pedida."""
    shot = Image.open(os.path.join(SRC, shot_key + ".png")).convert("RGB")
    w = round(shot.width * height / shot.height)
    shot = rounded(shot.resize((w, height), Image.LANCZOS), round(height * 0.035))
    pad = round(height * 0.012)
    frame = Image.new("RGBA", (w + 2 * pad, height + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, frame.width - 1, frame.height - 1], radius=round(height * 0.035) + pad, fill=(11, 20, 17, 255))
    frame.alpha_composite(shot, (pad, pad))
    return frame

def shadowed(canvas, layer, xy, blur=28, alpha=110):
    sh = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    sh.putalpha(layer.getchannel("A").point(lambda v: alpha if v else 0))
    big = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    big.alpha_composite(sh, (xy[0], xy[1] + blur // 2))
    canvas.alpha_composite(big.filter(ImageFilter.GaussianBlur(blur)))
    canvas.alpha_composite(layer, xy)

# ---------- Gráfico destacado 1024×500 ----------
FW, FH, F2 = 1024, 500, 2
feat = green(FW * F2, FH * F2, 0, 0, FW * F2, FH * F2, 260, 120, 1500).convert("RGBA")
d = ImageDraw.Draw(feat)
for gx in range(40, FW * F2, 56):              # trama de puntitos, como el fondo de la app
    for gy in range(40, FH * F2, 56):
        d.ellipse([gx - 2, gy - 2, gx + 2, gy + 2], fill=(255, 255, 255, 26))
# Marca como en la cabecera de la app: el símbolo a la izquierda de «Verbapp», en la misma línea.
gk = 2.3 * F2
glyph(d, lambda x, y: (64 * F2 + (x - 31) * gk, 142 * F2 + (y - 33) * gk))
d.text((176 * F2, 128 * F2), "Verbapp", font=font(96 * F2, 600), fill=WHITE)
d.text((70 * F2, 278 * F2), "Graba sin internet.", font=font(42 * F2, 400), fill=MINT)
d.text((70 * F2, 330 * F2), "Transcribe con IA.", font=font(42 * F2, 400), fill=MINT)
p1 = phone("v080final/n80_home", 470 * F2); p2 = phone("v080final/n80_detail", 470 * F2)
shadowed(feat, p2, (790 * F2, 74 * F2)); shadowed(feat, p1, (585 * F2, 40 * F2))
feat = feat.resize((FW, FH), Image.LANCZOS).convert("RGB")
feat.save(os.path.join(OUT, "grafico-destacado-1024x500.png"))

# ---------- Capturas 1080×1920 con titular (la palabra destacada en menta, como en la app) ----------
SHOTS = [
    ("v080/0.8-01-bienvenida", "Tus palabras, para *siempre*"),
    ("v080final/n80_home", "Graba con un toque, incluso sin *internet*"),
    ("v070/0.7-02-grabando", "Marca los momentos *clave* mientras grabas"),
    ("v080final/n80_detail", "Transcribe y separa cada *voz*"),
    ("v080/0.8-07-metricas", "Tus métricas, calculadas en tu *teléfono*"),
    ("v080/0.8-05-modelos", "Elige el modelo de IA o deja el *automático*"),
    ("v070/0.7-08-oscuro", "También en modo *oscuro*"),
]

def caption(img, text, top, size=66, max_w=920):
    """Titular centrado en hasta 3 líneas; la palabra entre asteriscos va sobre un recuadro menta con texto oscuro."""
    d = ImageDraw.Draw(img); f = font(size, 600)
    words = text.split(" "); lines = [[]]
    for wd in words:
        trial = " ".join(x.strip("*") for x in lines[-1] + [wd])
        if lines[-1] and d.textlength(trial, font=f) > max_w:
            lines.append([wd])
        else:
            lines[-1].append(wd)
    y = top; lh = round(size * 1.22); space = d.textlength(" ", font=f)
    for line in lines:
        widths = [d.textlength(wd.strip("*"), font=f) for wd in line]
        total = sum(widths) + space * (len(line) - 1)
        x = (img.width - total) / 2
        for wd, wdw in zip(line, widths):
            plain = wd.strip("*")
            if wd.startswith("*"):
                pad = size * 0.14
                d.rounded_rectangle([x - pad, y + size * 0.08, x + wdw + pad, y + size * 1.12], radius=size * 0.2, fill=MINT)
                d.text((x, y), plain, font=f, fill=INK)
            else:
                d.text((x, y), plain, font=f, fill=WHITE)
            x += wdw + space
        y += lh
    return y

for i, (key, text) in enumerate(SHOTS, 1):
    W, H = 1080, 1920
    can = green(W, H, 0, 0, W * 0.6, H, 260, 200, 1600).convert("RGBA")
    d = ImageDraw.Draw(can)
    for gx in range(30, W, 44):
        for gy in range(30, 420, 44):
            d.ellipse([gx - 1.6, gy - 1.6, gx + 1.6, gy + 1.6], fill=(255, 255, 255, 16))
    bottom = caption(can, text, 120)
    ph = phone(key, 1920 - max(bottom, 300) - 110)
    shadowed(can, ph, ((W - ph.width) // 2, max(bottom, 300) + 50))
    can.convert("RGB").save(os.path.join(OUT, "capturas", f"{i:02d}.png"))

print("listo:", sorted(os.listdir(OUT)), sorted(os.listdir(os.path.join(OUT, "capturas"))))

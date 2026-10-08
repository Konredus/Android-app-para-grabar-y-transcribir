"""Capturas de la ficha de Google Play en español, inglés y portugués (0.9.4), con el mismo estilo que store_assets.py.
Teléfono 1080×1920 (8) y tablet 1920×1080 (4), a partir de capturas del emulador con los datos de muestra de StoreDemo
(am instrument -e demo es|en|pt). Uso: python store_shots.py <carpeta con las capturas crudas> <carpeta de salida>.
Las crudas se llaman <idioma>-<nombre>.png (teléfono 1080×2400) y <idioma>-t_<nombre>.png (tablet 2560×1600)."""
import ast, os, sys
from PIL import Image, ImageDraw

# Funciones y colores de store_assets.py (sin generar sus gráficos): ícono, degradado, marco, sombra y titular.
_here = os.path.dirname(os.path.abspath(__file__))
_tree = ast.parse(open(os.path.join(_here, "store_assets.py"), encoding="utf-8").read())
_keep = [n for n in _tree.body if isinstance(n, (ast.Import, ast.ImportFrom, ast.FunctionDef))
         or (isinstance(n, ast.Assign) and all(isinstance(t, ast.Name) and t.id in ("REPO", "FONT", "INK", "MINT", "WHITE", "SPARK", "STOPS") for t in n.targets))]
exec(compile(ast.Module(body=_keep, type_ignores=[]), "store_assets.py", "exec"))

PHONE = ["welcome", "home", "detail", "note", "library", "noise", "metrics", "dark"]
TABLET = ["t_welcome", "t_home", "t_detail", "t_library"]
CAPTIONS = {
    "es": {"welcome": "Tus palabras, para *siempre*", "home": "Graba con un toque, incluso sin *internet*",
           "detail": "Transcribe y separa cada *voz*", "note": "Una nota para tu segundo *cerebro*",
           "library": "Todas tus grabaciones, a un *toque*", "noise": "Menos ruido, más *voz*",
           "metrics": "Tus métricas, calculadas en tu *teléfono*", "dark": "También en modo *oscuro*"},
    "en": {"welcome": "Your words, *forever*", "home": "Record with one tap, even *offline*",
           "detail": "Transcribe and separate every *voice*", "note": "A note for your second *brain*",
           "library": "All your recordings, one *tap* away", "noise": "Less noise, more *voice*",
           "metrics": "Your stats, computed on your *phone*", "dark": "Dark mode *too*"},
    "pt": {"welcome": "Suas palavras, para *sempre*", "home": "Grave com um toque, mesmo sem *internet*",
           "detail": "Transcreva e separe cada *voz*", "note": "Uma nota para o seu segundo *cérebro*",
           "library": "Todas as suas gravações, a um *toque*", "noise": "Menos ruído, mais *voz*",
           "metrics": "Suas métricas, calculadas no seu *celular*", "dark": "Também no modo *escuro*"},
    "de": {"welcome": "Deine Worte, für *immer*", "home": "Mit einem Tipp aufnehmen, auch *offline*",
           "detail": "Transkribiert und trennt jede *Stimme*", "note": "Eine Notiz für dein zweites *Gehirn*",
           "library": "Alle Aufnahmen, nur einen *Tipp* entfernt", "noise": "Weniger Lärm, mehr *Stimme*",
           "metrics": "Deine Statistik, berechnet auf deinem *Handy*", "dark": "Auch im dunklen *Modus*"},
}

def framed(shot, height, radius, padf):
    w = round(shot.width * height / shot.height)
    shot = rounded(shot.resize((w, height), Image.LANCZOS), round(height * radius))
    pad = round(height * padf)
    frame = Image.new("RGBA", (w + 2 * pad, height + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, frame.width - 1, frame.height - 1], radius=round(height * radius) + pad, fill=(11, 20, 17, 255))
    frame.alpha_composite(shot, (pad, pad))
    return frame

def phone_shot(src, lang, name, text):
    W, H = 1080, 1920
    can = green(W, H, 0, 0, W * 0.6, H, 260, 200, 1600).convert("RGBA")
    d = ImageDraw.Draw(can)
    for gx in range(30, W, 44):
        for gy in range(30, 420, 44):
            d.ellipse([gx - 1.6, gy - 1.6, gx + 1.6, gy + 1.6], fill=(255, 255, 255, 16))
    bottom = caption(can, text, 120)
    shot = Image.open(os.path.join(src, f"{lang}-{name}.png")).convert("RGB")
    ph = framed(shot, 1920 - max(bottom, 300) - 110, 0.035, 0.012)
    shadowed(can, ph, ((W - ph.width) // 2, max(bottom, 300) + 50))
    return can.convert("RGB")

def tablet_shot(src, lang, name, text):
    W, H = 1920, 1080
    can = green(W, H, 0, 0, W, H * 1.4, 300, 120, 1800).convert("RGBA")
    bottom = caption(can, text, 48, size=64, max_w=1600)
    raw = Image.open(os.path.join(src, f"{lang}-{name}.png")).convert("RGB")
    shot = raw.crop((0, 49, 2560, 1489))  # sin barra de estado ni barra de tareas: 2560×1440, 16:9
    tb = framed(shot, H - bottom - 70, 0.03, 0.018)
    shadowed(can, tb, ((W - tb.width) // 2, bottom + 24), blur=24)
    return can.convert("RGB")

if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]
    for lang, caps in CAPTIONS.items():
        os.makedirs(os.path.join(out, lang, "telefono"), exist_ok=True)
        os.makedirs(os.path.join(out, lang, "tablet"), exist_ok=True)
        for i, name in enumerate(PHONE, 1):
            if os.path.exists(os.path.join(src, f"{lang}-{name}.png")):
                phone_shot(src, lang, name, caps[name]).save(os.path.join(out, lang, "telefono", f"{i:02d}.png"))
        for i, name in enumerate(TABLET, 1):
            if os.path.exists(os.path.join(src, f"{lang}-{name}.png")):
                key = {"t_welcome": "welcome", "t_home": "home", "t_detail": "detail", "t_library": "library"}[name]
                tablet_shot(src, lang, name, caps[key]).save(os.path.join(out, lang, "tablet", f"tablet-{i:02d}.png"))
    print("listo:", {l: sorted(os.listdir(os.path.join(out, l, "telefono"))) for l in CAPTIONS})

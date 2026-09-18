"""Builds every Gesture Mouse brand asset from the source logo."""
from PIL import Image, ImageFilter
import numpy as np, sys, os
SRC = sys.argv[1]; OUT = sys.argv[2]
BG = np.array([234,239,240], float)          # the source logo's flat background
im = Image.open(SRC).convert("RGB")

def extract(box, scale, lo, hi):
    c = im.crop(box)
    c = c.resize((round(c.width*scale), round(c.height*scale)), Image.LANCZOS)
    c = c.filter(ImageFilter.UnsharpMask(radius=2, percent=55, threshold=2))
    a = np.asarray(c).astype(float)
    d = np.sqrt(((a-BG)**2).sum(-1))
    alpha = np.clip((d-lo)/(hi-lo), 0, 1)
    rgb = np.clip(BG + (a-BG)/np.maximum(alpha,1e-3)[...,None], 0, 255)
    img = Image.fromarray(np.dstack([rgb, alpha*255]).astype(np.uint8), "RGBA")
    return img.crop(img.getbbox())

def square(img, size, fill=0.86, bg=(0,0,0,0)):
    s = fill*size/max(img.size)
    r = img.resize((round(img.width*s), round(img.height*s)), Image.LANCZOS)
    canvas = Image.new("RGBA", (size,size), bg)
    canvas.alpha_composite(r, ((size-r.width)//2, (size-r.height)//2))
    return canvas

FULL, MARK = (300,40,720,505), (300,40,720,390)
variants = {
    "light": dict(lo=16, hi=70),   # keeps the soft glow — for light grounds
    "dark":  dict(lo=48, hi=100),  # glow trimmed — it turns to haze on dark
}
for name, k in variants.items():
    full = extract(FULL, 2.6, **k); mark = extract(MARK, 2.6, **k)
    square(full, 1024).save(f"{OUT}/logo-{name}.png")
    square(mark, 1024).save(f"{OUT}/mark-{name}.png")
    globals()[f"full_{name}"] = full; globals()[f"mark_{name}"] = mark

prev = Image.new("RGBA", (2048,1024))
prev.alpha_composite(square(full_light,1024,bg=(246,247,251,255)), (0,0))
prev.alpha_composite(square(full_dark,1024,bg=(11,12,31,255)), (1024,0))
prev.convert("RGB").resize((1024,512), Image.LANCZOS).save(f"{OUT}/_preview.png")

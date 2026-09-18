"""Adaptive launcher icon from the brand mark.

The foreground is 108dp; launchers mask it to a circle, squircle etc. and only
the central 66dp circle is guaranteed visible. So instead of guessing a scale,
measure how far the solid artwork reaches from its centre and fit that radius
inside the safe circle."""
from PIL import Image
import numpy as np, sys, os
mark = Image.open(sys.argv[1]).convert("RGBA"); OUT = sys.argv[2]
mark = mark.crop(mark.getbbox())
# The mark was cut off above the wordmark, which leaves the glow ending in a
# hard horizontal line. Fade only the faint glow over the bottom 22%; the solid
# artwork (alpha >= 0.5) is left exactly as it is.
arr = np.asarray(mark).astype(float)
h = arr.shape[0]; start = int(h*0.78)
ramp = np.ones(h); ramp[start:] = np.linspace(1, 0, h-start)**1.5
glow = arr[..., 3] < 128
arr[..., 3] = np.where(glow, arr[..., 3]*ramp[:, None], arr[..., 3])
mark = Image.fromarray(arr.astype(np.uint8), "RGBA")
a = np.asarray(mark)[..., 3]
ys, xs = np.where(a > 128)                      # the solid artwork, not the glow
cx, cy = (xs.min()+xs.max())/2, (ys.min()+ys.max())/2
reach = np.sqrt((xs-cx)**2 + (ys-cy)**2).max()  # px from centre to furthest solid pixel
SAFE_R = 33 * 0.97                               # dp, inside the 66dp safe circle
BG = (244, 246, 250, 255)                        # brand light ground, from the logo

densities = {"mdpi":1, "hdpi":1.5, "xhdpi":2, "xxhdpi":3, "xxxhdpi":4}
for name, k in densities.items():
    size = round(108*k)
    s = (SAFE_R*k) / reach
    r = mark.resize((max(1,round(mark.width*s)), max(1,round(mark.height*s))), Image.LANCZOS)
    fg = Image.new("RGBA", (size,size), (0,0,0,0))
    # centre the solid artwork (not the bbox, which the glow skews)
    ox = round(size/2 - cx*s); oy = round(size/2 - cy*s)
    fg.alpha_composite(r, (ox, oy))
    os.makedirs(f"{OUT}/mipmap-{name}", exist_ok=True)
    fg.save(f"{OUT}/mipmap-{name}/ic_launcher_foreground.png", optimize=True)

# a flat 512 icon for docs / sharing, and a preview of the common masks
def flat(size, mask=None):
    k = size/108; s = (SAFE_R*k)/reach
    r = mark.resize((round(mark.width*s), round(mark.height*s)), Image.LANCZOS)
    img = Image.new("RGBA", (size,size), BG)
    img.alpha_composite(r, (round(size/2-cx*s), round(size/2-cy*s)))
    if mask == "circle":
        m = Image.new("L", (size,size), 0)
        from PIL import ImageDraw; ImageDraw.Draw(m).ellipse((0,0,size-1,size-1), fill=255)
        img.putalpha(m)
    elif mask == "squircle":
        from PIL import ImageDraw
        m = Image.new("L", (size,size), 0); ImageDraw.Draw(m).rounded_rectangle((0,0,size-1,size-1), radius=size*0.3, fill=255)
        img.putalpha(m)
    return img
print("reach px", round(reach), "mark", mark.size)

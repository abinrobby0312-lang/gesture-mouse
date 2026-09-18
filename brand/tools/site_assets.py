"""Web assets for the landing page (site/assets), from the brand kit.

Run from the repo root:  python brand/tools/site_assets.py <site-url>
Needs Pillow and qrcode[pil]."""
import sys
from PIL import Image, ImageDraw, ImageFont
import qrcode

SITE = sys.argv[1]
OUT = "site/assets"

def fit(img, size):
    img = img.crop(img.getbbox())
    s = size / max(img.size)
    return img.resize((round(img.width*s), round(img.height*s)), Image.LANCZOS)

# logos for the page, light and dark, WebP with alpha
for v in ("light", "dark"):
    fit(Image.open(f"brand/logo-{v}.png"), 560).save(f"{OUT}/logo-{v}.webp", quality=88, method=6)
    fit(Image.open(f"brand/mark-{v}.png"), 160).save(f"{OUT}/mark-{v}.webp", quality=90, method=6)

# favicons / home-screen icons from the app icon
icon = Image.open("brand/icon-512.png").convert("RGBA")
for s in (32, 180, 192, 512):
    icon.resize((s, s), Image.LANCZOS).save(f"{OUT}/icon-{s}.png", optimize=True)

# social preview (Open Graph), 1200x630 — what WhatsApp/Instagram/X show for the link
W, H = 1200, 630
og = Image.new("RGBA", (W, H), (11, 12, 31, 255))
logo = fit(Image.open("brand/logo-dark.png"), 520)
og.alpha_composite(logo, (70, (H - logo.height)//2))
d = ImageDraw.Draw(og)
def font(size, bold=False):
    for name in (["segoeuib.ttf", "arialbd.ttf"] if bold else ["segoeui.ttf", "arial.ttf"]):
        try: return ImageFont.truetype(name, size)
        except OSError: pass
    return ImageFont.load_default()
x = 660
d.text((x, 190), "Your phone is a", font=font(46), fill=(243, 243, 255))
d.text((x, 245), "mouse & keyboard", font=font(58, True), fill=(76, 201, 240))
d.text((x, 330), "Trackpad, air gestures and typing", font=font(28), fill=(146, 150, 194))
d.text((x, 368), "over Bluetooth. Nothing to install", font=font(28), fill=(146, 150, 194))
d.text((x, 406), "on your computer.", font=font(28), fill=(146, 150, 194))
d.text((x, 470), "Free for Android 9+", font=font(30, True), fill=(152, 237, 19))
og.convert("RGB").save(f"{OUT}/og.png", optimize=True)

# QR code for desktop visitors: opens this page on the phone
qr = qrcode.QRCode(border=2, box_size=10, error_correction=qrcode.constants.ERROR_CORRECT_M)
qr.add_data(SITE); qr.make(fit=True)
qr.make_image(fill_color=(11, 13, 58), back_color="white").save(f"{OUT}/qr.png", optimize=True)
print("ok")

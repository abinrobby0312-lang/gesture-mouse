# Gesture Mouse — brand

Everything here derives from the logo in `source/logo-source.webp`: a pixel-art
phone, a pointing hand and a cursor arrow in neon colours, with a halftone
glow, over the wordmark **GESTURE** (magenta) **MOUSE** (lime).

## Files

| File | Use |
|---|---|
| `logo-light.png` | Full logo with wordmark, 1024×1024, transparent. Keeps the soft glow — **for light backgrounds**. |
| `logo-dark.png` | The same with the faint glow trimmed — it reads as haze on dark. **For dark backgrounds.** |
| `mark-light.png` / `mark-dark.png` | The graphic without the wordmark, same two variants. For small sizes, where the wordmark is unreadable. |
| `icon-512.png` | The app icon, flat square, for sharing and listings. |
| `source/logo-source.webp` | The original. Don't edit the derived files — regenerate them. |
| `tools/brand_build.py`, `tools/icon_build.py` | Regenerate everything from the source (Pillow + numpy). |

In the app: `res/drawable-nodpi/logo_full.webp` (light) and
`res/drawable-night-nodpi/logo_full.webp` (dark) are picked by theme
automatically; the launcher icon is `mipmap-*/ic_launcher_foreground.png` on
`@color/icon_background`.

## Using the logo

- Wordmark when there's room (≥ 120dp wide); the mark alone below that.
- Match the variant to the ground: light on light, dark on dark. Never place
  the light variant on a dark surface.
- Keep clear space of at least a quarter of the logo's width on every side.
- Don't recolour, stretch, outline or add effects. The glow is part of it.
- The app name is **Gesture Mouse** — two words, both capitalised.

## Colour

Taken from the logo by quantising its artwork. Dark-theme values are the
logo's colours as drawn; light-theme values are deepened so they stay readable
on white.

| Role | Dark | Light | From the logo | Used for |
|---|---|---|---|---|
| `track` — primary | `#F50BE0` | `#C800B6` | "GESTURE" magenta | Selection, sliders, buttons, trackpad glow |
| `ok` — success | `#98ED13` | `#3F8C00` | "MOUSE" lime | Connected, "tracker looks right" |
| `cyan` — highlight | `#07DFDD` | `#00929A` | Phone screen | Secondary accent, hand-overlay halo |
| `fire` — pending | `#FF9A3C` | `#C25E00` | The hand | Connecting, waiting, tracker suggestions |
| `fault` — error | `#FF5468` | `#C62834` | — | Errors. A true red, kept apart from magenta |
| `paper` — text | `#F3F3FF` | `#0B0D3A` | Outline navy | Primary text |
| `ink` — ground | `#0B0C1F` | `#F4F6FA` | Navy / logo background | App background |
| `panel` | `#14163A` | `#FFFFFF` | | Tab bar, sheets |
| `line` | `#2B2E5E` | `#DADDEA` | | Borders, dividers |
| `dim` | `#9296C2` | `#565A7E` | | Secondary text |

**Meaning is fixed across both themes:** lime means connected / good, orange
means waiting, red means wrong. Magenta is the brand accent and never signals
a state on its own.

Defined in `app/src/main/res/values/colors.xml` (light) and
`values-night/colors.xml` (dark). Add new colours to both, by role, never as
hard-coded hex in layouts or code.

## Type and shape

- System font (Roboto on most phones); bold for titles, regular for body.
  Section headers: small caps-style — 12sp, bold, all caps, `track` colour.
- Outlined buttons for most actions; the filled magenta button only for the
  single main action on a screen (e.g. **Email us**).
- Cards: 12dp corners, 1dp `line` border, `ink` fill.
- Grounds stay flat. Colour belongs to the accents and the logo; textures
  compete with the trackpad and the camera overlay.

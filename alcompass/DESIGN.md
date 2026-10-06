---
name: Alcompass
description: A beer-bottle compass needle printed as an Indian matchbox label, pointing at the nearest open alcohol shop.
colors:
  navy-ground: "#0B1240"
  chrome-yellow: "#F7C51E"
  ochre-ray: "#E9A80F"
  vermilion: "#E0301E"
  bottle-green: "#1E6B34"
  glass-highlight: "#4FA35F"
  key-black: "#111111"
  paper-white: "#FFFFFF"
  navy-mist: "#C9D0F5"
typography:
  display:
    fontFamily: "Big Shoulders Display, Impact, sans-serif"
    fontSize: "68px"
    fontWeight: 900
    lineHeight: "70px"
    letterSpacing: "normal"
    fontFeature: "tnum"
  headline:
    fontFamily: "Big Shoulders Display, Impact, sans-serif"
    fontSize: "48px"
    fontWeight: 900
    lineHeight: "52px"
    letterSpacing: "normal"
  title:
    fontFamily: "Big Shoulders Display, Impact, sans-serif"
    fontSize: "32px"
    fontWeight: 800
    lineHeight: "34px"
    letterSpacing: "0.4px"
  button:
    fontFamily: "Big Shoulders Display, Impact, sans-serif"
    fontSize: "24px"
    fontWeight: 800
    letterSpacing: "2px"
  body:
    fontFamily: "Archivo, Arial, sans-serif"
    fontSize: "15px"
    fontWeight: 500
    lineHeight: "21px"
  body-strong:
    fontFamily: "Archivo, Arial, sans-serif"
    fontSize: "15px"
    fontWeight: 700
    lineHeight: "21px"
  chip:
    fontFamily: "Archivo, Arial, sans-serif"
    fontSize: "13px"
    fontWeight: 700
    letterSpacing: "1.2px"
  label:
    fontFamily: "Archivo, Arial, sans-serif"
    fontSize: "12px"
    fontWeight: 700
    letterSpacing: "1.4px"
    fontFeature: "tnum"
rounded:
  none: "0px"
spacing:
  stock: "7px"
  gutter: "14px"
  edge: "16px"
  emblem: "18px"
  hint-gap: "22px"
components:
  label:
    backgroundColor: "{colors.chrome-yellow}"
    rounded: "{rounded.none}"
    padding: "{spacing.stock}"
    width: "min(86vw, 400px)"
  label-banner:
    backgroundColor: "{colors.key-black}"
    textColor: "{colors.chrome-yellow}"
    typography: "{typography.title}"
    padding: "8px 14px 10px"
  distance-readout:
    textColor: "{colors.key-black}"
    typography: "{typography.display}"
  arrival-headline:
    textColor: "{colors.key-black}"
    typography: "{typography.headline}"
  button-start:
    backgroundColor: "{colors.key-black}"
    textColor: "{colors.chrome-yellow}"
    typography: "{typography.button}"
    rounded: "{rounded.none}"
    padding: "14px 0"
  button-start-pressed:
    backgroundColor: "{colors.vermilion}"
    textColor: "{colors.chrome-yellow}"
  chip-open:
    backgroundColor: "{colors.vermilion}"
    textColor: "{colors.paper-white}"
    typography: "{typography.chip}"
    rounded: "{rounded.none}"
    padding: "3px 10px"
  hint:
    textColor: "{colors.navy-mist}"
    typography: "{typography.body}"
  status:
    textColor: "{colors.chrome-yellow}"
    typography: "{typography.body-strong}"
---

# Design System: Alcompass

## Overview

**Creative North Star: "The Matchbox Label"**

Alcompass is printed, not rendered. The whole product is one chrome-yellow matchbox label laid on a deep navy ground: an outer stock edge, an inset black keyline, a serial strip, a sunburst emblem with a green beer bottle as the compass needle, a black banner of condensed caps, and the small print underneath. Every colour is a flat chromolitho ink at full strength. The label is the only object on screen; everything else is navy.

Density is deliberately low. The bottle owns the label, the distance is the largest type, and the shop's own identity is held back until arrival, when the label slides away like a matchbox tray and comes back carrying the shop's name. Motion behaves like paper and counters: trays slide, digits roll one at a time, the needle turns by the shortest way.

The world refuses the category default of a dark screen, a centred grey ring and a single accent. It also refuses bar and party clichés (neon, foam, cocktail glasses); the beer-bottle needle is the one sanctioned drinking image, chosen by the user.

**Key Characteristics:**
- One square-cornered yellow label, about 86% of the screen width (max 400px), centred on navy.
- Flat ink only: no gradients, glow, grain or shadows.
- Black keylines draw every edge, in the label, the emblem rings and around the bottle.
- Condensed heavy caps (Big Shoulders Display) for anything read at arm's length; Archivo for small print.
- Tabular figures for the distance and the serial, so numbers hold still while they change.

## Colors

Six printing inks at full strength on a navy ground, with one navy-tinted mist for quiet type off the label.

### Primary
- **Chrome Yellow** (`chrome-yellow`): the label stock, the banner title, the Start button text and the status line on navy. The colour of the object itself.
- **Bottle Green** (`bottle-green`): the beer-bottle glass, the needle. Appears nowhere else.

### Secondary
- **Vermilion** (`vermilion`): the bottle's crown cap (the end that points at the shop) and label band, the Open till chip, and the Start button's pressed state.

### Tertiary
- **Ochre Ray** (`ochre-ray`): the alternating wedges of the 24-ray sunburst behind the emblem. Only ever on yellow.
- **Glass Highlight** (`glass-highlight`): the single vertical highlight stripe on the bottle. One stripe, never a sheen.

### Neutral
- **Deep Navy Ground** (`navy-ground`): the page, the browser overscroll, the app background, splash and adaptive-icon background.
- **Key Black** (`key-black`): keylines, the banner, the Start button, and all type printed on the label.
- **Paper White** (`paper-white`): text inside the vermilion chip and the focus outline on navy.
- **Navy Mist** (`navy-mist`): secondary type on the navy ground (the "Tap the label" hint), tinted from the ground rather than grey.

### Named Rules
**The Full-Strength Ink Rule.** Inks print flat and opaque. Opacity appears only as a state signal: the sunburst drops to 0.45 while the heading is untrustworthy, and the pressed label to 0.94. Never as a decorative tint.

**The Two-Grounds Rule.** On the yellow label, type is Key Black (Chrome Yellow only inside the black banner or button). On the navy, type is Chrome Yellow for status and Navy Mist for hints.

## Typography

**Display Font:** Big Shoulders Display, 800 ExtraBold and 900 Black (with Impact, sans-serif)
**Body Font:** Archivo, 500 Medium and 700 Bold (with Arial, sans-serif)

**Character:** A tall, condensed poster face that reads like letterpress wood type, against a plain grotesque for the small print. The display face carries everything glanced at while walking; Archivo carries anything read once.

### Hierarchy
- **Display** (900, 68px, 70px line, tabular figures): the distance under the banner. The largest type on screen.
- **Headline** (900, 48px, 52px line, uppercase): YOU'VE ARRIVED on arrival.
- **Title** (800, 32px, 34px line, 0.4px tracking, uppercase, centred): the banner text, up to two lines (FOLLOW THE BOTTLE, the shop name on arrival, state titles).
- **Tagline** (800, 20px, 23px line, 0.6px tracking, uppercase, centred): the Start label's slogan, "Your pre-game just got more adventurous".
- **Button** (800, 24px, 2px tracking, uppercase): the Start button.
- **Body** (500, 15px, 21px line, max 300px wide, centred): small print on the label and the hint on navy.
- **Body Strong** (700, 15px, 21px line, max 320px wide): status lines on navy and the pending line.
- **Chip** (700, 13px, 1.2px tracking, uppercase): the Open till chip.
- **Label** (700, 12px, 1.4px tracking, uppercase, tabular figures): the serial strip, "No. n of m" and ALCOMPASS.

### Named Rules
**The Arm's-Length Rule.** Anything read while walking (banner, distance, arrival, compass point) is set in Big Shoulders Display. Archivo never carries the primary reading.

**The Still-Numbers Rule.** Distances and serials use tabular figures, so a changing digit never shifts its neighbours.

## Layout

A single centred object on a full-bleed navy screen with a 16px edge gutter. The label is `min(86vw, 400px)` wide. Inside it, a fixed vertical stack: the serial strip (9px vertical padding, 1px black rule under it; replaced by a 6px blank before access), the emblem (18px above and below), the full-width black banner, then the small print (12px above, 16px keyline bottom padding). The 7px yellow stock sits outside a 2px black keyline, and the content inside the keyline has a 14px side gutter that the banner breaks out of to touch the keyline.

The emblem is sized to the label, `min(labelWidth - 70, 34vh)`, so the whole label fits a short phone while the bottle stays its largest element. Under the label on navy, the hint sits 22px below and any status line 10px below that. There is no other chrome: no header, no navigation, no map.

## Elevation & Depth

Flat. There are no shadows anywhere. The label separates from the ground by the contrast between yellow ink and navy, and depth inside the label comes from the stock edge and the inset keyline, the way a printed label shows its border. Motion supplies the only sense of physical layering: the label slides out and in like a matchbox tray.

### Named Rules
**The Printed-Not-Lit Rule.** No box shadows, drop shadows, glows or gradients. If something needs to stand forward, it gets a black keyline or a black panel.

## Shapes

Square corners everywhere in the interface (0px): the label, the keyline, the banner, the Start button and the chip. Circles appear only in the emblem: an outer ring (1.2-unit stroke at radius 48.5 of a 100-unit box) and an inner hairline ring (0.5 at 45.5), framing a 24-ray sunburst with a black hub when no needle is shown. The bottle is the one softened silhouette, with rounded shoulders and base, outlined in a 1.3-unit black stroke with round joins. The rounded bottle is illustration; the interface shapes around it stay square.

## Components

### Label (signature)
The whole interface lives in one matchbox label.
- **Corner Style:** square (0px).
- **Background:** Chrome Yellow stock, 7px, around a 2px Key Black keyline.
- **Structure:** serial strip, emblem, banner, small print, in that order and only that order.
- **Interaction:** the whole label is a button when there is more than one shop. A tap slides it out left (160ms, ease-in quad), swaps the shop, and slides the new one in from the right (340ms, ease-out expo). Arrival and leaving use the same slide. Pressed: label opacity 0.94. Reduced motion swaps instantly.
- **Focus:** 3px white outline, 6px offset, on the navy.

### Emblem and Bottle Needle (signature)
A sunburst in Ochre Ray over yellow inside two black rings, with the green beer bottle over it. The crown cap points at the shop. The needle eases to each new angle in 120ms along the shortest arc. While calibrating, the bottle prints as a yellow outline and the burst dims to 0.45. With no way to orient the bottle, the burst dims and the compass point (NE, SW) is set inside it in Display weight at 36% of the emblem's size.

### Banner
A full-width Key Black band with Chrome Yellow Title caps, centred, up to two lines. It names the state: FOLLOW THE BOTTLE while walking, the shop's name on arrival, or the state title (Finding you, Nothing nearby, Couldn't load shops).

### Distance Readout
Key Black Display figures under the banner. Each character rolls up into place on its own as it changes (rises from 0.45 em below, 260ms, ease-out expo, fading in), like a counter wheel. Announced as a single text to screen readers.

### Buttons
- **Shape:** square (0px), stretched to the label's inner width.
- **Primary (Start):** Key Black fill, Chrome Yellow Button caps, 14px vertical padding.
- **Pressed:** fill turns Vermilion.
- **Focus:** 3px Key Black outline, 3px offset, so it shows on the yellow stock.

### Chips
- **Open till:** Vermilion fill, Paper White Chip caps, 3px by 10px padding, square. Appears only on arrival, under YOU'VE ARRIVED. It is the only chip.

### Off-label Text
The hint ("Tap the label to try another shop") in Navy Mist Body, and status lines (calibrating, no compass) in Chrome Yellow Body Strong, centred under the label. States are always spelled out in words, never signalled by colour alone.

## Do's and Don'ts

### Do:
- **Do** keep a single label as the only object on the navy ground, at `min(86vw, 400px)`.
- **Do** print every colour as a flat, full-strength ink from the palette; use opacity only for the dimmed-heading and pressed states.
- **Do** draw edges with Key Black keylines (2px label keyline, 1px serial rule, 1.3-unit stroke round the bottle).
- **Do** set anything read while walking in Big Shoulders Display caps, and every number with tabular figures.
- **Do** change content by sliding the label like a matchbox tray, and change digits by rolling them one at a time; honour reduced motion.
- **Do** keep the bottle green with a vermilion cap and band; the cap is the pointing end.

### Don't:
- **Don't** use gradients, glow, grain, or any shadow.
- **Don't** round interface corners; only the bottle illustration and the emblem rings are curved.
- **Don't** introduce bar or party imagery (neon, foam, cocktail glasses) beyond the bottle needle.
- **Don't** fall back to the category default of a dark screen, a grey ring and one accent colour.
- **Don't** use grey for secondary type on navy; use Navy Mist, tinted from the ground.
- **Don't** put Chrome Yellow type directly on the yellow stock or Key Black type on navy.

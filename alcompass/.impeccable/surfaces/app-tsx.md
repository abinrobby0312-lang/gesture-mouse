---
version: 1
slug: "app-tsx"
primary_target: "App.tsx"
related_targets: ["src/Needle.tsx"]
---

# Alcompass compass screen

Scope: the single screen in App.tsx (permission gate, compass, all states) plus src/Needle.tsx. Mode: Operate.
Audience and job: adults in Indian cities, phone held out while walking, find the nearest alcohol shop. Read direction and distance in under a second while walking, in sun or at night; the name and closing time on arrival.
Constraints: React Native primitives, react-native-svg and reanimated only, so the Android build shares it. Openly about drinking; no bar or party clichés, except the beer-bottle needle, which the user chose (2026-10-06).

## Direction contract

THESIS: The hunt for the nearest open shop is printed as an Indian matchbox label, with a beer-bottle needle as its emblem; the shop's own label is only revealed on arrival. It refuses the category default of a dark screen, a centred grey ring and one accent.

OWN-WORLD: Flat chromolitho ink. Deep navy #0B1240 ground, chrome-yellow #F7C51E label, a green beer bottle #1E6B34 (highlight #4FA35F) as the needle, its crown cap and label band vermilion #E0301E, black #111111 keylines and banner, white for type on blue. A square-cornered label with an outer edge plus an inset black keyline, an ochre sunburst behind the emblem, condensed heavy caps. No gradients, glow or grain.

STORY: The visitor taps Start, sees one label whose bottle points at the nearest open shop, reads FOLLOW THE BOTTLE in the black banner and the huge distance under it, and taps the label to slide another shop in, like pushing a matchbox tray. Within 30 m the label slides again and reveals the shop's name, YOU'VE ARRIVED and Open till <time>.

FIRST VIEWPORT: Navy ground. One label about 86% of the screen width, centred. Top strip: serial "No. 2 of 5" and "ALCOMPASS". Middle: the sunburst with the bottle needle, the largest element. Black banner: FOLLOW THE BOTTLE in yellow condensed caps (the shop name only on arrival). Below it: tabular distance in black at display size (on arrival: YOU'VE ARRIVED and a vermilion Open till chip). Under the label on navy: "Tap the label to try another shop" and any status line. Before access, the label has no serial strip and shows ALCOMPASS and a square black Start button. Only shops open right now are ever shown.

FORM: Matchbox Label, candidate 4 of 7 on the ordered list; seed key a65a7974. Signature move: tapping slides the label out left and the next shop's label in from the right; distance digits roll individually as they change. Raised by Catalog Sleeve (one emblem owns the label), Dark Console (tabular figures), Split-flap (changes roll per digit), Garden Guide Map (flat ink at full commitment).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

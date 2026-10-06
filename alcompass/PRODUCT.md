# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

Mobile web first (Expo with a web target, deployed on Vercel). The same screen code also builds a native Android app later, so the UI stays React Native primitives plus `react-native-svg` and `react-native-reanimated`.

## Users

Adults in Indian cities, starting with Bangalore, who want the nearest alcohol shop right now. They hold the phone out in front of them while walking a street, often in the evening or at night, sometimes in harsh daylight.

## Product Purpose

Alcompass is a compass needle, drawn as a beer bottle, that points at the nearest alcohol shop. There is no map. Tap the label to cycle through the five nearest shops. The shop stays a mystery while you walk: you see only the direction and the distance. Its name, and its listed hours if it has any, are revealed when you arrive (within 30 m). Success means the needle feels trustworthy on a real walk and the distance reads at a glance.

It is meant to become a real public product, possibly earning money later (Play Store, step and calorie features).

## Positioning

A single-purpose instrument rather than a map or a listing app. You follow a needle, not a route. That works for "wine shop" streets in India, where map tagging is patchy and the shop is usually within a short walk.

## Operating Context

- One screen: Start (permission gate), then the bottle needle, "Follow the bottle", the distance, "No. n of m" and tap for another shop. On arrival: the shop name, "You've arrived", its listed hours when known, and the trip verdict.
- States: asking for access, denied, finding you, looking for shops, nothing nearby (3 km), couldn't load shops, calibrating (figure 8), no compass (follows GPS course, or shows a compass point as text).
- Used while walking, in sun or at night; the screen must stay on.

## Capabilities and Constraints

- Zero budget (user's call, 2026-10-06): no paid APIs or keys. Shops come from OpenStreetMap via the free Overpass API through `/api/shops` (3 km radius; `shop=alcohol|wine` plus wine/liquor-named shops). Coverage depends on OpenStreetMap.
- No time restriction (user's call, 2026-10-06): every nearby shop is shown at any hour. Listed OpenStreetMap opening hours are shown on arrival as information only; most shops have none, and nothing is assumed.
- Magnetic north on the web; HTTPS only; no step counter in browsers.
- Calories: estimated from GPS distance and pace on the way to the shop (web has no step counter), assuming 70 kg, and turned into sips of beer on arrival. Pace above 16 km/h counts as wheels: 0 kcal and a don't-drink-and-drive line. Copy is deliberately sarcastic (user's call, 2026-10-06).
- Usage and error logging to Supabase (insert-only, no coordinates or personal data). A privacy policy should mention it.
- Not built yet: a per-user weight setting, a real step counter, edge cases, age gate, dry-state check, privacy policy, disclaimer.
- Open decisions: search radius, offline use, dry days, the name check.

## Brand Commitments

- Name: Alcompass.
- Openly about drinking: liquor-shop culture is the material, carried with pride rather than hidden.
- No bar or party clichés: no neon signs, beer foam or cocktail glasses. Exception the user chose on 2026-10-06: the compass needle is a beer bottle.

## Evidence on Hand

- Real shop names, positions and any opening hours come from OpenStreetMap at runtime. Nothing else exists: no users, reviews, press or partner shops. Do not invent any.
- The demo artifact uses sample shops only.

## Product Principles

1. The needle comes first. Nothing competes with direction and distance; the shop's identity waits until arrival.
2. Readable at arm's length, while walking, in any light.
3. Honest about uncertainty: calibrating, no compass and no shops are shown plainly, never hidden.
4. Proud of the subject, never a cliché of it.
5. One codebase for web and Android.

## Accessibility & Inclusion

Glanceable in direct sun and at night: high contrast, large type for the shop name and distance. States are announced in text, not by colour alone. Legal-age gating is still to be built (original step 9).

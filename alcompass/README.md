# Alcompass

A compass needle that points at the nearest alcohol shop. No map. Built with Expo (React Native) and shipped as a **web app for now**; the same code still builds for Android.

Shops come from OpenStreetMap through the free Overpass API: no API key, no account, no cost. The phone asks Overpass directly, which answers in seconds. If that fails it falls back to `/api/shops`, a Vercel function running the same search; from Vercel's shared servers Overpass can take over a minute.

## How it works

- **Shops.** On the first location fix, the app asks `/api/shops` for alcohol shops within 3 km. It refetches only after you move more than 500 m, and caches each roughly 500 m area for 24 hours on the device.
- **No time limits.** Every shop nearby is shown, whatever the hour. When a shop lists opening hours in OpenStreetMap, the arrival label shows what they say ("Open till 10:00 PM" or "Listed as closed right now"), read on the phone against its own clock (`src/hours.ts`). Shops without hours show nothing about it.
- **Target.** A beer bottle points at the nearest shop. Tap the label to cycle through the 5 nearest. The chosen shop stays chosen while you walk.
- **The trip verdict.** While you head to the shop, the app adds up GPS distance and time (`src/trip.ts`). It skips fixes rougher than 35 m, ignores jitter under 5 m and drops GPS jumps. On arrival it judges the trip by pace: a stroll (under 60 m), a walk, a run (average 2.4 m/s or more), or wheels (at least 20 s and 40% of the distance above 16 km/h). Calories assume 70 kg (walking 0.5, running 1.0 kcal per kg per km), and they're converted into sips or shares of a 140 kcal beer, with a sarcastic line. Wheels get 0 kcal and a don't-drink-and-drive line. GPS can't tell a scooter from a car, so they share one verdict.
- **Mystery until you arrive.** While walking, the label shows only the bottle and the distance. Within 30 m it reveals the shop's name, plus its listed hours if it has any (hidden again beyond 60 m).
- **Needle.** Heading smoothed on sin and cos so it never spins the long way past north, greyed with a "calibrating" note when the reading is poor.
- **No compass.** If no heading arrives in 4 s, the arrow follows your GPS course while walking (above 0.8 m/s). Standing still, it shows the direction as text (N, NE, ...).
- **Nothing found.** "Nothing nearby" when no shop is within 3 km; "Couldn't load shops" on an error, retried after 30 s.

## The search (`src/osm.ts`)

Each request makes one Overpass query within 3 km of your position, for:

1. Shops tagged `shop=alcohol` or `shop=wine`.
2. Any shop whose name contains wine, liquor or spirits, since many Indian shops are named "... Wines" without the right tag.

Disused shops and anything in `EXCLUDE` are dropped. The rest is nearest first, up to 20. Each instance gets 15 s (25 s from the function) before the next one in `OVERPASS_URLS` is tried. The function identifies itself with `USER_AGENT`, as the Overpass usage policy asks; browsers send their own. Asking directly means the phone's rough position goes to Overpass, which keeps no account and logs per its own policy. The function logs nothing about the caller and sends `Cache-Control: no-store`.

Coverage depends on OpenStreetMap. Missing shops can be added at openstreetmap.org, and they show up within minutes.

## Run it

```bash
cd alcompass
npm install
npm run web          # dev server; /api/shops is not served here
npm test             # maths, heading, shop list, search, hours, trip
npm run typecheck
```

For live shops locally, either run the whole project with `npx vercel dev`, or point the dev server at a deployed API with `EXPO_PUBLIC_SHOPS_URL=https://<deployment>/api/shops npm run web`. `EXPO_PUBLIC_*` values are baked in at build time.

## Deploy

```bash
cd alcompass
npx vercel link            # project: alcompass
npx vercel deploy --prod
curl "https://<deployment>/api/shops?lat=12.9716&lng=77.5946"
```

`vercel.json` builds the static site with `expo export`; files in `api/` deploy as functions. Nothing needs configuring: the search needs no key and fits the free Hobby plan.

CI (workflow **Alcompass**) typechecks, tests and builds the web app on every push that touches `alcompass/`.

## Web limits

| | Android Chrome | iPhone Safari |
| --- | --- | --- |
| Compass | `deviceorientationabsolute` | `webkitCompassHeading`, after the Start tap grants motion access |
| Position | Geolocation API | Geolocation API |
| Calibrating flag | not reported | uncertainty above 30 degrees |

- **HTTPS only.** Browsers block location and compass on plain http, so test on the deployed site.
- **Magnetic north, not true north.** Around Bangalore the difference is under 2 degrees.
- **No step counter.** Browsers have no pedometer API; steps and calories need accelerometer step detection or the native build.
- **Screen must stay on.** A web page gets no sensor data in the background.

## Native build (later)

`src/useSensors.ts` is the native version of the sensor hooks, using `expo-location`. It needs `EXPO_PUBLIC_SHOPS_URL` set to the deployed API. With a USB-connected Android phone: `npx expo run:android`. `android/` and `ios/` are generated by `npx expo prebuild`; never commit them, change `app.json` instead.

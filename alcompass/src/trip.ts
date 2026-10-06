// The walk to the shop: distance and pace from GPS fixes, turned into calories and a verdict.
import { distanceM, type LatLng } from './geo';

export type Fix = LatLng & { accuracy: number | null; at: number };

export type Trip = {
  distanceM: number;
  movingMs: number;
  /** Time and distance spent above wheels speed. */
  fastMs: number;
  fastM: number;
  /** The last fix counted from; small moves accumulate against it rather than being lost. */
  anchor: Fix | null;
};

export type Mode = 'stroll' | 'walk' | 'run' | 'wheels';

export type Verdict = {
  mode: Mode;
  distanceM: number;
  kcal: number;
  /** "Walked 1.2 km · 42 kcal" */
  receipt: string;
  quip: string;
};

export const EMPTY_TRIP: Trip = { distanceM: 0, movingMs: 0, fastMs: 0, fastM: 0, anchor: null };

/** Fixes rougher than this are skipped. */
const MAX_ACCURACY_M = 35;
/** Moves shorter than this are GPS jitter until they add up. */
const MIN_STEP_M = 5;
/** Faster than any vehicle in a city: a GPS jump, not travel. */
const TELEPORT_MPS = 45;
/** Above about 16 km/h nobody is on foot. */
const WHEELS_MPS = 4.5;
/** A jog or run on average. */
const RUN_MPS = 2.4;
/** Under this the trip is a stroll, not a walk. */
const STROLL_M = 60;

// Net energy per kg per km: walking about 0.5, running about 1.0. Weight assumed at 70 kg.
const WEIGHT_KG = 70;
const KCAL_PER_KG_KM: Record<'walk' | 'run', number> = { walk: 0.5, run: 1.0 };
/** A 330 ml lager is about 140 kcal. */
const BEER_ML = 330;
const KCAL_PER_ML = 140 / BEER_ML;
const SIP_ML = 20;

export function addFix(trip: Trip, fix: Fix): Trip {
  if (fix.accuracy != null && fix.accuracy > MAX_ACCURACY_M) return trip;
  const a = trip.anchor;
  if (!a) return { ...trip, anchor: fix };
  const dt = fix.at - a.at;
  if (dt <= 0) return trip;
  const d = distanceM(a, fix);
  if (d < MIN_STEP_M) return trip;
  const speed = d / (dt / 1000);
  if (speed > TELEPORT_MPS) return { ...trip, anchor: fix };
  const fast = speed > WHEELS_MPS;
  return {
    distanceM: trip.distanceM + d,
    movingMs: trip.movingMs + dt,
    fastMs: trip.fastMs + (fast ? dt : 0),
    fastM: trip.fastM + (fast ? d : 0),
    anchor: fix,
  };
}

export function classify(trip: Trip): Mode {
  if (trip.distanceM < STROLL_M) return 'stroll';
  // Wheels when a real share of the trip was at vehicle speed, not one GPS spike.
  if (trip.fastMs >= 20_000 && trip.fastM >= trip.distanceM * 0.4) return 'wheels';
  const avg = trip.movingMs > 0 ? trip.distanceM / (trip.movingMs / 1000) : 0;
  return avg >= RUN_MPS ? 'run' : 'walk';
}

export function caloriesKcal(trip: Trip, mode: Mode): number {
  if (mode === 'wheels') return 0;
  const rate = mode === 'run' ? KCAL_PER_KG_KM.run : KCAL_PER_KG_KM.walk;
  return Math.round((trip.distanceM / 1000) * WEIGHT_KG * rate);
}

function formatKm(m: number): string {
  return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`;
}

// Same trip, same joke: pick a variant from the distance so it does not change on re-render.
function pick(options: string[], seed: number): string {
  return options[Math.abs(Math.floor(seed)) % options.length];
}

function beerShare(ml: number): string {
  const share = ml / BEER_ML;
  if (share < 0.3) return 'a quarter of a beer';
  if (share < 0.45) return 'a third of a beer';
  if (share < 0.6) return 'half a beer';
  if (share < 0.85) return 'two-thirds of a beer';
  return 'almost a whole beer';
}

function earnedQuip(ml: number, seed: number): string {
  if (ml < SIP_ML) {
    return pick(
      [
        "You've earned the smell of a beer. Inhale responsibly.",
        "You've earned a look at a beer. Don't stare too long.",
      ],
      seed,
    );
  }
  if (ml < 100) {
    const sips = Math.round(ml / SIP_ML);
    return pick(
      [
        `You've earned ${sips} ${sips === 1 ? 'sip' : 'sips'} of beer. Pace yourself, champ.`,
        `That's ${sips} ${sips === 1 ? 'sip' : 'sips'} of beer, earned. Don't spend it all at once.`,
      ],
      seed,
    );
  }
  if (ml < BEER_ML) {
    return pick(
      [
        `You've earned ${beerShare(ml)}. The rest is on credit.`,
        `That walk bought you ${beerShare(ml)}. Your liver will invoice the difference.`,
      ],
      seed,
    );
  }
  if (ml < BEER_ML * 2) {
    return pick(
      [
        "You've earned one whole beer. Your legs are proud. Your liver is nervous.",
        'One full beer, earned fair and square. Look at you, athlete.',
      ],
      seed,
    );
  }
  const beers = Math.floor(ml / BEER_ML);
  return `You've earned ${beers} beers. That wasn't a walk, that was a pilgrimage.`;
}

export function verdict(trip: Trip): Verdict {
  const mode = classify(trip);
  const kcal = caloriesKcal(trip, mode);
  const seed = trip.distanceM;
  const dist = formatKm(trip.distanceM);

  if (mode === 'stroll') {
    return {
      mode,
      distanceM: trip.distanceM,
      kcal,
      receipt: `Moved ${dist} · ${kcal} kcal`,
      quip: pick(
        [
          "You barely moved. That earns you the bottle cap. Don't lick it.",
          'Shop was basically next door. Your effort has been noted, and filed under "none".',
        ],
        seed,
      ),
    };
  }

  if (mode === 'wheels') {
    return {
      mode,
      distanceM: trip.distanceM,
      kcal: 0,
      receipt: `On wheels ${dist} · 0 kcal`,
      quip: pick(
        [
          "We noticed you rode down to the liquor store. Bold. The bottle points at shops, not at good decisions: don't drink and ride, get a cab home.",
          "We noticed you came on wheels. 0 kcal burned, 0 sips earned. Whatever you had in mind, the wheels stay parked after the first drink.",
          'We noticed you drove here. Respect the commitment. Now hand the keys to someone sober: the bottle finds shops, not a way past the breathalyser checkpoint.',
        ],
        seed,
      ),
    };
  }

  const ml = kcal / KCAL_PER_ML;
  const verb = mode === 'run' ? 'Ran' : 'Walked';
  const lead = mode === 'run' ? 'You ran here. Thirsty much? ' : '';
  return {
    mode,
    distanceM: trip.distanceM,
    kcal,
    receipt: `${verb} ${dist} · ${kcal} kcal`,
    quip: lead + earnedQuip(ml, seed),
  };
}

import { useEffect, useRef, useState } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { LatLng } from './geo';
import { covers, nearby, parseDataset, type Dataset } from './dataset';
import { logError, track } from './log';
import { CACHE_TTL_MS, cellKey, parseShops, shouldRefetch, type Shop } from './shops';

// Same-origin by default: the web app, the dataset and /api/shops deploy as one Vercel project.
// Native builds (and local web dev without `vercel dev`) set EXPO_PUBLIC_SITE_URL to the deployed site.
const SITE_URL = process.env.EXPO_PUBLIC_SITE_URL || '';
const SHOPS_URL = `${SITE_URL}/api/shops`;
const DATASET_URL = `${SITE_URL}/shops-in.json`;
/** The dataset is rebuilt weekly; a phone re-downloads it at most daily. */
const DATASET_TTL_MS = 24 * 60 * 60 * 1000;

export type ShopsState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; shops: Shop[] }
  | { status: 'error'; shops: Shop[] };

// After a failed fetch, wait this long before trying again.
const RETRY_MS = 30_000;

type CacheEntry = { at: number; shops: Shop[] };

async function readCache(key: string): Promise<Shop[] | null> {
  try {
    const raw = await AsyncStorage.getItem(`shops:${key}`);
    if (!raw) return null;
    const entry = JSON.parse(raw) as CacheEntry;
    // Opening hours are evaluated live on the phone, so a cached list only goes stale with age.
    return Date.now() - entry.at < CACHE_TTL_MS ? entry.shops : null;
  } catch {
    return null;
  }
}

async function writeCache(key: string, shops: Shop[]): Promise<void> {
  try {
    await AsyncStorage.setItem(`shops:${key}`, JSON.stringify({ at: Date.now(), shops } satisfies CacheEntry));
  } catch {
    // A full or blocked store only costs a refetch.
  }
}

let dataset: Promise<Dataset | null> | null = null;

/** The bundled shop list: from memory, then the phone's storage, then the site. Null if unavailable. */
function loadDataset(): Promise<Dataset | null> {
  dataset ??= (async () => {
    try {
      const raw = await AsyncStorage.getItem('dataset:in');
      if (raw) {
        const cached = JSON.parse(raw) as { at: number; d: Dataset };
        if (Date.now() - cached.at < DATASET_TTL_MS) return parseDataset(cached.d);
      }
    } catch {
      // A bad or blocked store only costs a download.
    }
    try {
      const res = await fetch(DATASET_URL);
      if (!res.ok) throw new Error(`dataset ${res.status}`);
      const d = parseDataset(await res.json());
      AsyncStorage.setItem('dataset:in', JSON.stringify({ at: Date.now(), d })).catch(() => {});
      return d;
    } catch (e) {
      logError('shops.dataset', e);
      dataset = null; // try again on the next fetch
      return null;
    }
  })();
  return dataset;
}

// The bundled dataset answers instantly wherever it applies (India). Elsewhere, or if it cannot load,
// /api/shops searches OpenStreetMap live.
async function fetchShops(at: LatLng): Promise<{ shops: Shop[]; source: 'bundled' | 'live' }> {
  const d = await loadDataset();
  if (d && covers(d, at.latitude, at.longitude)) return { shops: nearby(d, at.latitude, at.longitude), source: 'bundled' };
  const url = `${SHOPS_URL}?lat=${at.latitude.toFixed(5)}&lng=${at.longitude.toFixed(5)}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`shops ${res.status}`);
  return { shops: parseShops(await res.json()), source: 'live' };
}

/** Shops near `position`, refetched after moving 500 m and cached per 500 m area for 24 h. */
export function useShops(position: LatLng | null): ShopsState {
  const [state, setState] = useState<ShopsState>({ status: 'idle' });
  const lastFetchAt = useRef<LatLng | null>(null);
  const inFlight = useRef(false);
  const failedAt = useRef(0);

  useEffect(() => {
    if (!position || inFlight.current || !shouldRefetch(lastFetchAt.current, position)) return;
    if (Date.now() - failedAt.current < RETRY_MS) return;
    const at = { latitude: position.latitude, longitude: position.longitude };
    const key = cellKey(at);
    inFlight.current = true;
    setState((s) => (s.status === 'ready' ? s : { status: 'loading' }));

    (async () => {
      const t0 = Date.now();
      try {
        let shops = await readCache(key);
        let source = 'cache';
        if (!shops) {
          ({ shops, source } = await fetchShops(at));
          void writeCache(key, shops);
        }
        track('shops_loaded', { count: shops.length, source, ms: Date.now() - t0 });
        lastFetchAt.current = at;
        setState({ status: 'ready', shops });
      } catch (e) {
        logError('shops.load', e, { ms: Date.now() - t0 });
        // Keep showing the last list if there was one; retry on a later position update.
        failedAt.current = Date.now();
        setState((s) => ({ status: 'error', shops: s.status === 'ready' || s.status === 'error' ? s.shops : [] }));
      } finally {
        inFlight.current = false;
      }
    })();
  }, [position]);

  return state;
}

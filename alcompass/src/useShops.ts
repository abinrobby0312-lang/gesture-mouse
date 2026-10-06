import { useEffect, useRef, useState } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { LatLng } from './geo';
import { CACHE_TTL_MS, cellKey, parseShops, shouldRefetch, type Shop } from './shops';

// Same-origin by default: the web app and /api/shops deploy as one Vercel project.
// Native builds (and local web dev without `vercel dev`) set EXPO_PUBLIC_SHOPS_URL to a deployed one.
const SHOPS_URL = process.env.EXPO_PUBLIC_SHOPS_URL || '/api/shops';

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

async function fetchShops(at: LatLng): Promise<Shop[]> {
  const url = `${SHOPS_URL}?lat=${at.latitude.toFixed(5)}&lng=${at.longitude.toFixed(5)}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`shops ${res.status}`);
  return parseShops(await res.json());
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
      try {
        let shops = await readCache(key);
        if (!shops) {
          shops = await fetchShops(at);
          void writeCache(key, shops);
        }
        lastFetchAt.current = at;
        setState({ status: 'ready', shops });
      } catch {
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

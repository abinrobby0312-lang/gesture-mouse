import type { LatLng } from './geo';

export type Shop = LatLng & { name: string };

/**
 * Build step 3: one hardcoded shop to prove bearing and distance on a walk.
 * Set EXPO_PUBLIC_DEV_SHOP_LAT / _LNG / _NAME in .env.local to a shop near you.
 * Step 5 replaces this with the proxy response.
 */
function devShop(): Shop | null {
  const lat = Number(process.env.EXPO_PUBLIC_DEV_SHOP_LAT);
  const lng = Number(process.env.EXPO_PUBLIC_DEV_SHOP_LNG);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || (lat === 0 && lng === 0)) return null;
  return {
    latitude: lat,
    longitude: lng,
    name: process.env.EXPO_PUBLIC_DEV_SHOP_NAME || 'Test shop',
  };
}

export const DEV_SHOP = devShop();

// Activity and error log, sent to Supabase (free tier). The app can only insert; nothing is read
// back. No coordinates, shop identities or personal data are logged: just what happened and what broke.
import { Platform } from 'react-native';

// The publishable key is designed to ship in clients; the table's RLS allows insert only.
const LOG_URL = 'https://ruhwscwgveyzizpruxyf.supabase.co/rest/v1/alcompass_events';
const LOG_KEY = 'sb_publishable_DJY6ZHam2TEsLjbjf19M-w_fqKVSfqu';
const APP_VERSION = '1.1.0';

const FLUSH_MS = 10_000;
const MAX_BATCH = 20;
const TRAIL = 8;

type Event = {
  at: string;
  session: string;
  kind: 'activity' | 'error';
  name: string;
  data: Record<string, unknown>;
  app_version: string;
  platform: string;
  user_agent: string | null;
};

// Logging needs a real runtime: not tests, not static rendering.
const enabled = typeof fetch === 'function' && (Platform.OS !== 'web' || typeof window !== 'undefined');

/** A fresh random id per app launch, so one visit's events read as a story without identifying anyone. */
const session = Array.from({ length: 4 }, () => Math.random().toString(36).slice(2, 8)).join('');
const userAgent = typeof navigator !== 'undefined' && navigator.userAgent ? navigator.userAgent.slice(0, 300) : null;

let queue: Event[] = [];
let timer: ReturnType<typeof setTimeout> | null = null;
/** The last few activity names, attached to every error so it shows where the visitor was. */
const trail: string[] = [];

function push(kind: Event['kind'], name: string, data: Record<string, unknown>) {
  if (!enabled) return;
  queue.push({
    at: new Date().toISOString(),
    session,
    kind,
    name: name.slice(0, 60),
    data,
    app_version: APP_VERSION,
    platform: Platform.OS,
    user_agent: userAgent,
  });
  if (kind === 'error' || queue.length >= MAX_BATCH) flush();
  else if (!timer) timer = setTimeout(flush, FLUSH_MS);
}

export function flush() {
  if (timer) clearTimeout(timer);
  timer = null;
  if (!queue.length) return;
  const batch = queue;
  queue = [];
  // keepalive lets the last batch leave even as the page closes. A failed send is dropped:
  // logging must never break the app or retry forever.
  fetch(LOG_URL, {
    method: 'POST',
    keepalive: true,
    headers: {
      apikey: LOG_KEY,
      Authorization: `Bearer ${LOG_KEY}`,
      'Content-Type': 'application/json',
      Prefer: 'return=minimal',
    },
    body: JSON.stringify(batch),
  }).catch(() => {});
}

/** Something the visitor did or saw. Keep `data` small and free of coordinates or names. */
export function track(name: string, data: Record<string, unknown> = {}) {
  trail.push(name);
  if (trail.length > TRAIL) trail.shift();
  push('activity', name, data);
}

/** Something that broke, with where it happened and the visitor's last few steps. */
export function logError(where: string, err: unknown, data: Record<string, unknown> = {}) {
  const e = err instanceof Error ? err : new Error(String(err));
  push('error', where, {
    where,
    message: e.message.slice(0, 500),
    stack: e.stack?.slice(0, 1500) ?? null,
    trail: [...trail],
    ...data,
  });
}

let installed = false;

/** Catches errors nothing else caught: uncaught exceptions and unhandled promise rejections. */
export function installGlobalErrorLogging() {
  if (!enabled || installed) return;
  installed = true;
  if (Platform.OS === 'web') {
    window.addEventListener('error', (e) => logError('window.error', e.error ?? e.message, { file: e.filename, line: e.lineno }));
    window.addEventListener('unhandledrejection', (e) => logError('unhandledrejection', e.reason));
    window.addEventListener('pagehide', flush);
    document.addEventListener('visibilitychange', () => document.visibilityState === 'hidden' && flush());
  } else {
    const g = globalThis as unknown as {
      ErrorUtils?: { getGlobalHandler: () => (e: unknown, fatal?: boolean) => void; setGlobalHandler: (h: (e: unknown, fatal?: boolean) => void) => void };
    };
    const prev = g.ErrorUtils?.getGlobalHandler();
    g.ErrorUtils?.setGlobalHandler((e, fatal) => {
      logError(fatal ? 'fatal' : 'uncaught', e);
      flush();
      prev?.(e, fatal);
    });
  }
}

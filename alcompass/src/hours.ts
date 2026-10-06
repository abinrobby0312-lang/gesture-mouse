// Is a shop open right now? Evaluates the common forms of OpenStreetMap's opening_hours tag in the
// phone's own time zone (the user is standing near the shop).
//
// Supported: "24/7"; rules split by ";" where a later rule overrides earlier ones for its days;
// day selectors "Mo-Fr", "Mo,We,Fr", "Mo-Sa,Su"; time spans "10:00-22:30", "10:00-14:00,17:00-23:00",
// spans past midnight "18:00-02:00"; and "off" / "closed". Anything else (holidays, months, week
// numbers, comments) is treated as unknown. Hours are information only: no shop is hidden by them.

/** Minutes from midnight; an end past 1440 runs into the next day. */
type Span = [start: number, end: number];
/** Monday first, as OpenStreetMap writes days. */
type Week = Span[][];

const DAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];
const ALL_DAYS = [0, 1, 2, 3, 4, 5, 6];

function parseTime(s: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(s);
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (h > 48 || min > 59) return null;
  return h * 60 + min;
}

function parseDays(sel: string): number[] | null {
  const out = new Set<number>();
  for (const part of sel.split(',')) {
    const range = part.split('-');
    const a = DAYS.indexOf(range[0]);
    const b = range.length === 2 ? DAYS.indexOf(range[1]) : a;
    if (a < 0 || b < 0 || range.length > 2) return null;
    // Wraps, e.g. "Sa-Mo".
    for (let d = a; ; d = (d + 1) % 7) {
      out.add(d);
      if (d === b) break;
    }
  }
  return [...out];
}

function parseSpans(s: string): Span[] | null {
  const spans: Span[] = [];
  for (const part of s.split(',')) {
    const [a, b] = part.split('-');
    const start = a == null ? null : parseTime(a.trim());
    const endRaw = b == null ? null : parseTime(b.trim());
    if (start == null || endRaw == null) return null;
    spans.push([start, endRaw <= start ? endRaw + 1440 : endRaw]);
  }
  return spans;
}

const cache = new Map<string, Week | null>();

/** The week the tag describes, or null when the tag uses forms this does not understand. */
export function parseHours(tag: string): Week | null {
  const hit = cache.get(tag);
  if (hit !== undefined) return hit;
  const week = parseUncached(tag.trim());
  cache.set(tag, week);
  return week;
}

function parseUncached(tag: string): Week | null {
  if (tag === '24/7') return ALL_DAYS.map(() => [[0, 1440]]);
  const week: Week = ALL_DAYS.map(() => []);
  for (const raw of tag.split(';')) {
    const rule = raw.trim();
    if (!rule) continue;
    // "Mo-Fr 10:00-22:00", "Su off", or a bare "10:00-22:00" for every day.
    const m = /^(?:([A-Za-z,\-]+)\s+)?(.+)$/.exec(rule);
    if (!m) return null;
    const days = m[1] ? parseDays(m[1]) : ALL_DAYS;
    if (!days) return null;
    const body = m[2].trim();
    const spans = body === 'off' || body === 'closed' ? [] : parseSpans(body);
    if (!spans) return null;
    for (const d of days) week[d] = spans;
  }
  return week;
}

export type OpenState = {
  open: boolean;
  /** Epoch ms when it closes, while open. */
  closesAt: number | null;
};

/** Open or closed at `now` by the shop's own hours, or null when it lists none we can read. */
export function openState(hours: string | null, now: number): OpenState | null {
  const week = hours ? parseHours(hours) : null;
  if (!week) return null;
  const date = new Date(now);
  const day = (date.getDay() + 6) % 7; // Monday = 0
  const minute = date.getHours() * 60 + date.getMinutes();
  const midnight = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  const at = (dayOffset: number, m: number) => midnight + (dayOffset * 1440 + m) * 60_000;

  for (const [start, end] of week[day]) {
    if (minute >= start && minute < end) return { open: true, closesAt: at(0, end) };
  }
  // Yesterday's late spans that run past midnight.
  for (const [, end] of week[(day + 6) % 7]) {
    if (end > 1440 && minute < end - 1440) return { open: true, closesAt: at(-1, end) };
  }
  return { open: false, closesAt: null };
}
